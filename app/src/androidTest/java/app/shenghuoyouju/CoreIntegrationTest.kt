package app.shenghuoyouju

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import app.shenghuoyouju.data.ActiveSnapshotRow
import app.shenghuoyouju.data.ContentRepository
import app.shenghuoyouju.data.CorpusParser
import app.shenghuoyouju.data.CorpusSnapshot
import app.shenghuoyouju.data.SearchState
import app.shenghuoyouju.data.UserDatabase
import app.shenghuoyouju.search.EmbeddingModel
import app.shenghuoyouju.search.SearchEngine
import java.io.File
import java.io.InterruptedIOException
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Runs against the installed, shrunk release APK and its real bundled CPU ONNX model. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CoreIntegrationTest {
    private lateinit var context: Context
    private lateinit var device: UiDevice
    private lateinit var model: EmbeddingModel
    private lateinit var database: UserDatabase
    private var originalActive: ActiveSnapshotRow? = null
    private var wifiWasEnabled = false
    private var dataWasEnabled = false
    private var originalAutoAttempt: String? = null
    private var existingSnapshotDirectories = emptySet<String>()
    private var setupComplete = false

    @Before fun isolateFromActivityAndUseBundledOfflineSnapshot() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        device = UiDevice.getInstance(instrumentation)
        // A preceding UI test may have left an Activity and its automatic updater alive.
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED)
                .flatMap { monitor.getActivitiesInStage(it) }.distinct().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        originalAutoAttempt = preferences.getString("lastAutoAttempt", null)
        preferences.edit().putString("lastAutoAttempt", java.time.LocalDate.now().toString()).commit()
        wifiWasEnabled = device.executeShellCommand("settings get global wifi_on").trim() == "1"
        dataWasEnabled = device.executeShellCommand("settings get global mobile_data").trim() == "1"
        device.executeShellCommand("svc wifi disable")
        device.executeShellCommand("svc data disable")
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (hasInternetNetwork(manager) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        database = UserDatabase.get(context)
        originalActive = database.userDao().activeSnapshot()
        existingSnapshotDirectories = File(context.filesDir, "snapshots").listFiles()?.map { it.name }?.toSet().orEmpty()
        withContext(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("DELETE FROM active_snapshot") }
        model = EmbeddingModel(context)
        setupComplete = true
        assertFalse("核心验收必须在无线网络与移动数据均断开的环境执行", hasInternetNetwork(manager))
    }

    @After fun restoreDatabasePreferencesAndNetwork() = runBlocking {
        if (!setupComplete) return@runBlocking
        try {
            withContext(Dispatchers.IO) {
                if (originalActive == null) database.openHelper.writableDatabase.execSQL("DELETE FROM active_snapshot")
                else database.userDao().activate(requireNotNull(originalActive))
                // Only directories created by this test run are removed, after restoring the DB pointer.
                File(context.filesDir, "snapshots").listFiles()?.filter {
                    it.isDirectory && it.name !in existingSnapshotDirectories &&
                        (it.name.startsWith(SUCCESS_COMMIT) || it.name.startsWith(INTERRUPTED_COMMIT))
                }?.forEach { it.deleteRecursively() }
            }
        } finally {
            model.close()
            val editor = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            if (originalAutoAttempt == null) editor.remove("lastAutoAttempt")
            else editor.putString("lastAutoAttempt", originalAutoAttempt)
            editor.commit()
            if (wifiWasEnabled) device.executeShellCommand("svc wifi enable")
            if (dataWasEnabled) device.executeShellCommand("svc data enable")
        }
        Unit
    }

    @Test fun a_realModelMatchesHostGoldenAndRecordsColdWarmTimings() = runBlocking {
        val graphicsLoadStarted = SystemClock.elapsedRealtimeNanos()
        System.loadLibrary("androidx.graphics.path")
        val graphicsLoadMs = elapsedMs(graphicsLoadStarted)
        val golden = JSONArray(context.assets.open("embedding_golden.json").bufferedReader().use { it.readText() })
        assertEquals(5, golden.length())
        val cachePresentBefore = context.noBackupFilesDir.listFiles()?.any { it.name.startsWith("bge-") && it.extension == "onnx" } == true
        val comparisons = JSONArray()
        val hotDurations = mutableListOf<Double>()
        var coldDuration = 0.0
        for (index in 0 until golden.length()) {
            val item = golden.getJSONObject(index)
            val query = item.getString("query")
            val expectedJson = item.getJSONArray("vector")
            val expected = FloatArray(expectedJson.length()) { expectedJson.getDouble(it).toFloat() }
            val started = SystemClock.elapsedRealtimeNanos()
            val actual = model.encodeQuery(query)
            val duration = elapsedMs(started)
            if (index == 0) coldDuration = duration else hotDurations += duration
            assertEquals(512, actual.size)
            assertTrue(actual.all { it.isFinite() })
            val cosine = cosine(expected, actual)
            var maximumAbsoluteError = 0f
            expected.indices.forEach { maximumAbsoluteError = maxOf(maximumAbsoluteError, kotlin.math.abs(expected[it] - actual[it])) }
            comparisons.put(JSONObject().put("query", query).put("cosineToHost", cosine)
                .put("maxAbsoluteError", maximumAbsoluteError.toDouble()).put("durationMs", duration))
        }
        // Three extra passes give 19 warmed samples; no network, mock, or generated vectors.
        repeat(3) {
            for (index in 0 until golden.length()) {
                val started = SystemClock.elapsedRealtimeNanos()
                model.encodeQuery(golden.getJSONObject(index).getString("query"))
                hotDurations += elapsedMs(started)
            }
        }
        report("model-parity", JSONObject().put("modelCachePresentBefore", cachePresentBefore)
            .put("graphicsPathExplicitNativeLoad", true).put("graphicsPathLoadMs", graphicsLoadMs)
            .put("coldSessionMs", coldDuration).put("warmSamplesMs", JSONArray(hotDurations))
            .put("warmP50Ms", percentile(hotDurations, .5)).put("warmP95Ms", percentile(hotDurations, .95))
            .put("comparisons", comparisons))
        for (index in 0 until comparisons.length()) {
            val result = comparisons.getJSONObject(index)
            assertTrue("${result.getString("query")} 端上/主机向量不一致：$result", result.getDouble("cosineToHost") > .995)
        }
    }

    @Test fun b_offlineBundledCorpusAndFiveActualSearches() = runBlocking {
        val repository = ContentRepository(context, model)
        val started = SystemClock.elapsedRealtimeNanos()
        val indexed = repository.load()
        val loadingMs = elapsedMs(started)
        assertEquals(686, indexed.corpus.documents.size)
        assertEquals(3827, indexed.corpus.chunks.size)
        assertEquals(3827, indexed.vectors.size)
        assertTrue(indexed.vectors.all { it.size == 512 })
        val engine = SearchEngine(repository, model)
        val queries = listOf("离职了，社保怎么办", "轻微感冒，吃点什么药？", "到底要怎么才能完全防晒", "钓鱼的技巧有哪些", "电脑开不了机了，是什么情况")
        val finalStates = mutableListOf<SearchState>()
        val cases = JSONArray()
        for (query in queries) {
            val phases = JSONArray()
            val searchStarted = SystemClock.elapsedRealtimeNanos()
            var final: SearchState? = null
            engine.search(query).collect { state ->
                final = state
                phases.put(JSONObject().put("phase", state.phase.name).put("elapsedMs", elapsedMs(searchStarted))
                    .put("titles", JSONArray(state.hits.map { it.document.title })))
            }
            val result = requireNotNull(final)
            finalStates += result
            cases.put(JSONObject().put("query", query).put("phases", phases)
                .put("finalHits", JSONArray(result.hits.map { hit ->
                    JSONObject().put("id", hit.document.id).put("title", hit.document.title)
                        .put("relatedOnly", hit.relatedOnly).put("score", hit.score.toDouble())
                })))
        }
        report("offline-search", JSONObject().put("sourceCommit", indexed.corpus.sourceCommit)
            .put("documents", indexed.corpus.documents.size).put("chunks", indexed.corpus.chunks.size)
            .put("loadMs", loadingMs).put("cases", cases))
        assertTrue("社保断缴条目必须进入前三", finalStates[0].hits.take(3).any { it.document.title.contains("社保断缴") })
        assertTrue("感冒问题须展示有关用药的原文", finalStates[1].hits.take(3).any {
            it.document.title.contains("感冒") && it.document.body.contains("药")
        })
        assertTrue("防晒问题不可将零散材料标作完整直接匹配", finalStates[2].hits.all { it.relatedOnly })
        assertTrue("钓鱼技巧不可误推网络钓鱼", finalStates[3].hits.isEmpty())
        assertTrue("电脑开不了机不可误推账号安全或服务器运维", finalStates[4].hits.isEmpty())
    }

    @Test fun c_realIncrementalEmbeddingPublishesAtomicallyAndSurvivesReload() = runBlocking {
        val repository = ContentRepository(context, model)
        val baseline = repository.load()
        val dao = database.userDao()
        val pointerBefore = dao.activeSnapshot()
        val changedDocument = baseline.corpus.documents.first()
        val chunkIndex = baseline.corpus.chunks.indexOfFirst { it.documentId == changedDocument.id }
        assertTrue(chunkIndex >= 0)
        val originalChunk = baseline.corpus.chunks[chunkIndex]
        val marker = "自动化测试标记：离线增量更新验证，非上游建议。"
        val newBody = changedDocument.body + "\n\n" + marker
        val newText = originalChunk.text.take(290) + "\n" + marker
        val newChunk = originalChunk.copy(id = sha(changedDocument.id + "\n" + newText).take(24), text = newText)
        val candidate = baseline.corpus.copy(sourceCommit = INTERRUPTED_COMMIT,
            documents = baseline.corpus.documents.map { if (it.id == changedDocument.id) it.copy(body = newBody, contentHash = sha(newBody)) else it },
            chunks = baseline.corpus.chunks.mapIndexed { index, chunk -> if (index == chunkIndex) newChunk else chunk })
        var reachedPublishBoundary = false
        val interruptedAt = SystemClock.elapsedRealtimeNanos()
        val failure = rejected {
            repository.installCandidate(candidate) {
                reachedPublishBoundary = true
                throw InterruptedIOException("模拟候选正文与索引已落盘、激活前被中断")
            }
        }
        val interruptedMs = elapsedMs(interruptedAt)
        assertTrue(failure is InterruptedIOException)
        assertTrue(reachedPublishBoundary)
        assertSame("中断不得更换正在使用的快照", baseline, repository.current.value)
        assertEquals("中断不得改变数据库指针", pointerBefore, dao.activeSnapshot())
        assertEquals(baseline.corpus.sourceCommit, ContentRepository(context, model).load().corpus.sourceCommit)

        val accepted = candidate.copy(sourceCommit = SUCCESS_COMMIT)
        val successAt = SystemClock.elapsedRealtimeNanos()
        repository.installCandidate(accepted)
        val publishMs = elapsedMs(successAt)
        val published = requireNotNull(repository.current.value)
        assertEquals(SUCCESS_COMMIT, published.corpus.sourceCommit)
        assertEquals(SUCCESS_COMMIT, dao.activeSnapshot()?.sourceCommit)
        assertEquals(newBody, published.corpus.documents.first { it.id == changedDocument.id }.body)
        val expectedChangedVector = model.encodePassage(newText)
        assertTrue(cosine(expectedChangedVector, published.vectors[chunkIndex]) > .9999)
        assertFalse("变动段落必须重新产生真实向量", baseline.vectors[chunkIndex].contentEquals(published.vectors[chunkIndex]))
        for (index in baseline.vectors.indices) {
            if (index != chunkIndex) assertArrayEquals("未修改块应复用原向量", baseline.vectors[index], published.vectors[index], 0f)
        }
        val restored = ContentRepository(context, model).load()
        assertEquals(accepted, restored.corpus)
        restored.vectors.indices.forEach { assertArrayEquals(published.vectors[it], restored.vectors[it], 0f) }
        assertFalse("旧快照不能被候选内容原地改写", baseline.corpus.documents.first { it.id == changedDocument.id }.body.contains(marker))
        report("atomic-update", JSONObject().put("interruptedBeforeActivate", reachedPublishBoundary)
            .put("rollbackPointerUnchanged", true).put("interruptedMs", interruptedMs).put("publishedCommit", SUCCESS_COMMIT)
            .put("publishMs", publishMs).put("recomputedChunks", 1).put("reusedChunks", baseline.vectors.size - 1)
            .put("changedVectorCosineToFreshInference", cosine(expectedChangedVector, restored.vectors[chunkIndex]))
            .put("reloadedPersistedSnapshot", true))
    }

    @Test fun d_malformedSourceAndCandidatesNeverActivate() = runBlocking {
        val repository = ContentRepository(context, model)
        val baseline = repository.load()
        val pointer = database.userDao().activeSnapshot()
        val failures = JSONArray()
        val brokenReadme = "[正文](book/01-test.md)\n## 读懂数字（术语表）\n## 目录"
        fun scenario(name: String, action: suspend () -> Unit) = name to action
        val scenarios = listOf(
            scenario("missing-source-file") { CorpusParser.parse(SUCCESS_COMMIT, mapOf("README.md" to brokenReadme)); Unit },
            scenario("unrecognized-entry-format") { CorpusParser.parse(SUCCESS_COMMIT,
                mapOf("README.md" to brokenReadme, "book/01-test.md" to "# 原标题\n上游格式已变化，不能按旧格式导入")); Unit },
            scenario("invalid-source-commit") { CorpusParser.parse("not-a-commit", mapOf("README.md" to brokenReadme)); Unit },
            scenario("empty-candidate") { repository.installCandidate(CorpusSnapshot(SUCCESS_COMMIT, emptyList(), emptyList())) },
            scenario("orphan-chunk") { repository.installCandidate(baseline.corpus.copy(sourceCommit = SUCCESS_COMMIT,
                chunks = baseline.corpus.chunks.mapIndexed { index, chunk -> if (index == 0) chunk.copy(documentId = "missing-document") else chunk })) },
            scenario("duplicate-document") { repository.installCandidate(baseline.corpus.copy(sourceCommit = SUCCESS_COMMIT,
                documents = baseline.corpus.documents + baseline.corpus.documents.first())) },
        )
        for ((name, block) in scenarios) {
            val failure = rejected(block)
            assertTrue("$name 应拒绝不兼容数据", failure is IllegalArgumentException)
            assertSame(name, baseline, repository.current.value)
            assertEquals(name, pointer, database.userDao().activeSnapshot())
            failures.put(JSONObject().put("scenario", name).put("exception", failure.javaClass.simpleName)
                .put("message", failure.message).put("currentAndPointerUnchanged", true))
        }
        report("malformed-candidates", JSONObject().put("rejectedCases", failures))
    }

    private suspend fun rejected(block: suspend () -> Unit): Exception {
        var error: Exception? = null
        try { block() } catch (failure: Exception) { error = failure }
        assertNotNull("操作应拒绝测试输入", error)
        return requireNotNull(error)
    }

    private fun report(name: String, result: JSONObject) {
        result.put("sdk", Build.VERSION.SDK_INT).put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE)).put("device", Build.MODEL)
            .put("recordedAtEpochMs", System.currentTimeMillis())
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "test-results").apply { mkdirs() }
        File(directory, "$name.json").writeText(result.toString(2))
        Log.i("LifeEvidenceCoreTest", "$name ${result}")
    }

    private fun hasInternetNetwork(manager: ConnectivityManager): Boolean =
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    private fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    private fun percentile(samples: List<Double>, fraction: Double) = samples.sorted()[(ceil(samples.size * fraction).toInt() - 1).coerceAtLeast(0)]
    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var aNorm = 0.0; var bNorm = 0.0
        a.indices.forEach { dot += a[it] * b[it]; aNorm += a[it] * a[it]; bNorm += b[it] * b[it] }
        return dot / sqrt(aNorm * bNorm)
    }
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val INTERRUPTED_COMMIT = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val SUCCESS_COMMIT = "cccccccccccccccccccccccccccccccccccccccc"
    }
}
