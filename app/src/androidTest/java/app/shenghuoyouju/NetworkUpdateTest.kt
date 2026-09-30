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
import app.shenghuoyouju.data.ContentRepository
import app.shenghuoyouju.data.GitHead
import app.shenghuoyouju.data.SearchPhase
import app.shenghuoyouju.data.UserDatabase
import app.shenghuoyouju.search.EmbeddingModel
import app.shenghuoyouju.search.SearchEngine
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ResponseCache
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in separately with -e class app.shenghuoyouju.NetworkUpdateTest; CoreIntegrationTest stays offline. */
@RunWith(AndroidJUnit4::class)
class NetworkUpdateTest {
    @Test fun realGithubCheckDailyGuardAndOfflineFailureKeepSnapshot() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED)
                .flatMap { monitor.getActivitiesInStage(it) }.distinct().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val originalAutoAttempt = preferences.getString("lastAutoAttempt", null)
        val wifiWasEnabled = device.executeShellCommand("settings get global wifi_on").trim() == "1"
        val dataWasEnabled = device.executeShellCommand("settings get global mobile_data").trim() == "1"
        val originalProxySelector = ProxySelector.getDefault()
        val originalResponseCache = ResponseCache.getDefault()
        val database = UserDatabase.get(context)
        val originalActive = database.userDao().activeSnapshot()
        val initialDirectories = File(context.filesDir, "snapshots").listFiles()?.map { it.name }?.toSet().orEmpty()
        val model = EmbeddingModel(context)
        val repository = ContentRepository(context, model)
        val result = JSONObject().put("endpoint", HEAD_URL).put("transport", GitHead.TRANSPORT).put("completed", false)
        try {
            withContext(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("DELETE FROM active_snapshot") }
            val bundled = repository.load()
            // State injection only: this identifier is not a historical upstream commit.
            // Real bundled documents/vectors remain intact, so production update must download,
            // parse and persist the real HEAD while reusing unchanged paragraph embeddings.
            val testOldVersion = "a".repeat(40)
            repository.current.value = bundled.copy(corpus = bundled.corpus.copy(sourceCommit = testOldVersion))
            result.put("forcedStaleLocalVersion", true).put("injectedLocalVersion", testOldVersion)
                .put("injectedVersionMeaning", "test-only identifier; not upstream history")
            preferences.edit().putString("lastAutoAttempt", LocalDate.now().toString()).commit()
            device.executeShellCommand("svc wifi enable")
            val wifiDeadline = SystemClock.elapsedRealtime() + 20_000
            while (!repository.onWifi() && SystemClock.elapsedRealtime() < wifiDeadline) delay(100)
            assertTrue("在线测试需要实际 Wi-Fi 连接", repository.onWifi())
            val observedHead = fetchHead()
            assertTrue(observedHead.matches(Regex("[a-f0-9]{40}")))

            val onlineStarted = SystemClock.elapsedRealtimeNanos()
            repository.update(manual = true)
            val installed = requireNotNull(repository.current.value)
            result.put("bundledCommit", bundled.corpus.sourceCommit).put("observedGithubHead", observedHead)
                .put("manualOnlineStatus", repository.updateStatus.value).put("manualOnlineMs", elapsedMs(onlineStarted))
                .put("installedCommit", installed.corpus.sourceCommit)
            assertTrue("手动更新必须实际完成下载、解析与持久化：${repository.updateStatus.value}", repository.updateStatus.value.startsWith("内容已更新"))
            assertTrue(installed.corpus.documents.isNotEmpty())
            assertEquals(installed.corpus.sourceCommit, database.userDao().activeSnapshot()?.sourceCommit)
            assertTrue(installed.corpus.sourceCommit.matches(Regex("[a-f0-9]{40}")))
            // Allow HEAD to advance between the independent request and the production check.
            if (installed.corpus.sourceCommit != observedHead) assertEquals(fetchHead(), installed.corpus.sourceCommit)
            assertTrue("文档来源必须全部固定到同一个完整提交", installed.corpus.documents.all {
                it.sourceUrl.startsWith("https://github.com/eternity4719/HowToLiveBetter/blob/${installed.corpus.sourceCommit}/")
            })

            preferences.edit().remove("lastAutoAttempt").commit()
            assertTrue(repository.onWifi())
            repository.update(manual = false)
            val firstAutomaticStatus = repository.updateStatus.value
            val today = LocalDate.now().toString()
            assertEquals("自动尝试必须记录本地日期", today, preferences.getString("lastAutoAttempt", null))
            // The public status makes an early return observable without replacing production fetch.
            val unchangedSentinel = "自动化检查：同日第二次不应发起请求"
            repository.updateStatus.value = unchangedSentinel
            val beforeSecond = repository.current.value
            val pointerBeforeSecond = database.userDao().activeSnapshot()
            assertTrue("日限制必须在 Wi-Fi 仍开启时验证", repository.onWifi())
            repository.update(manual = false)
            assertEquals(unchangedSentinel, repository.updateStatus.value)
            assertSame(beforeSecond, repository.current.value)
            assertEquals(pointerBeforeSecond, database.userDao().activeSnapshot())
            result.put("firstAutomaticStatus", firstAutomaticStatus).put("automaticAttemptDate", today)
                .put("sameDaySecondAutomaticCheckSkipped", true)
                .put("automaticFirstAttemptOutcome", if (successful(firstAutomaticStatus)) "success" else "failure")
                .put("automaticFailureForced", false)
            assertTrue("首次自动检查必须有成功或失败的明确结果", successful(firstAutomaticStatus) || firstAutomaticStatus.startsWith("更新未完成"))

            // Process-local fault injection: Android's RouteSelector appends DIRECT even if a
            // selector returns only a proxy. Rethrow the actual socket failure from connectFailed
            // to stop that recovery, without replacing fetch or fabricating an HTTP response.
            val beforeProxyFailure = repository.current.value
            val pointerBeforeProxyFailure = database.userDao().activeSnapshot()
            val failingProxy = UnreachableProxySelector()
            try {
                ResponseCache.setDefault(null)
                ProxySelector.setDefault(failingProxy)
                assertSame(failingProxy, ProxySelector.getDefault())
                result.put("faultInjection", "unreachable-process-proxy-no-direct-fallback")
                    .put("faultInjectionScope", "test-process ProxySelector and ResponseCache; Wi-Fi remains connected")
                    .put("directFallbackBlockedBy", "rethrow original IOException after actual proxy socket connection failure")
                    .put("responseCacheDisabledForFailureChecks", true)
                    .put("routeSelectorSource", ROUTE_SELECTOR_SOURCE)
                assertTrue("自动失败场景必须保持 Wi-Fi 连接", repository.onWifi())
                preferences.edit().remove("lastAutoAttempt").commit()
                repository.update(manual = false)
                val automaticFailure = repository.updateStatus.value
                result.put("proxySelectionCount", failingProxy.selections.get())
                    .put("proxyConnectFailedCount", failingProxy.connectionFailures.get())
                    .put("proxySocketFailure", failingProxy.lastFailure)
                    .put("forcedAutomaticFailureStatus", automaticFailure)
                assertTrue("必须实际调用测试代理选择器，不能命中旧连接或缓存", failingProxy.selections.get() > 0)
                assertTrue("必须观察到真实代理 socket 连接失败", failingProxy.connectionFailures.get() > 0)
                assertTrue("不可用代理必须让实际生产请求失败：$automaticFailure", automaticFailure.startsWith("更新未完成"))
                assertEquals(LocalDate.now().toString(), preferences.getString("lastAutoAttempt", null))
                assertSame(beforeProxyFailure, repository.current.value)
                assertEquals(pointerBeforeProxyFailure, database.userDao().activeSnapshot())
                val failureSentinel = "自动化检查：失败后同日不应重试"
                repository.updateStatus.value = failureSentinel
                val selectionsBeforeRetry = failingProxy.selections.get()
                val failuresBeforeRetry = failingProxy.connectionFailures.get()
                repository.update(manual = false)
                assertEquals(failureSentinel, repository.updateStatus.value)
                assertEquals("当天重试不得再次选择代理", selectionsBeforeRetry, failingProxy.selections.get())
                assertEquals("当天重试不得再次尝试连接", failuresBeforeRetry, failingProxy.connectionFailures.get())
                assertSame(beforeProxyFailure, repository.current.value)
                assertEquals(pointerBeforeProxyFailure, database.userDao().activeSnapshot())
                result.put("automaticFailureForced", true).put("failedAutomaticCheckNotRetriedSameDay", true)
                    .put("sameDayRetryAddedProxySelections", 0).put("sameDayRetryAddedSocketFailures", 0)
            } finally {
                ProxySelector.setDefault(originalProxySelector)
            }

            val beforeOffline = requireNotNull(repository.current.value)
            val pointerBeforeOffline = database.userDao().activeSnapshot()
            device.executeShellCommand("svc wifi disable")
            device.executeShellCommand("svc data disable")
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val offlineDeadline = SystemClock.elapsedRealtime() + 10_000
            while (hasInternetNetwork(manager) && SystemClock.elapsedRealtime() < offlineDeadline) delay(100)
            assertFalse("失败场景须真正断网", hasInternetNetwork(manager))
            val offlineStarted = SystemClock.elapsedRealtimeNanos()
            repository.update(manual = true)
            result.put("manualOfflineStatus", repository.updateStatus.value).put("manualOfflineMs", elapsedMs(offlineStarted))
            assertTrue("断网必须报告失败", repository.updateStatus.value.startsWith("更新未完成"))
            assertSame("失败不得更换内存快照", beforeOffline, repository.current.value)
            assertEquals("失败不得更换持久化指针", pointerBeforeOffline, database.userDao().activeSnapshot())
            val reloadedRepository = ContentRepository(context, model)
            val reloaded = reloadedRepository.load()
            assertEquals(beforeOffline.corpus, reloaded.corpus)
            assertEquals(beforeOffline.vectors.size, reloaded.vectors.size)
            assertFalse("更新后的检索必须仍然断网", hasInternetNetwork(manager))
            val offlineQuery = "离职了，社保怎么办"
            val searchStarted = SystemClock.elapsedRealtimeNanos()
            val states = SearchEngine(reloadedRepository, model).search(offlineQuery).toList()
            val finalState = states.last()
            val topThree = finalState.hits.take(3)
            result.put("updatedOfflineSearch", JSONObject().put("query", offlineQuery)
                .put("sourceCommit", reloaded.corpus.sourceCommit).put("phase", finalState.phase.name)
                .put("elapsedMs", elapsedMs(searchStarted))
                .put("phases", JSONArray(states.map { it.phase.name }))
                .put("titles", JSONArray(topThree.map { it.document.title }))
                .put("sourceUrls", JSONArray(topThree.map { it.document.sourceUrl }))
                .put("bodyLengths", JSONArray(topThree.map { it.document.body.length })))
            assertEquals("必须完成真实模型推理与融合检索", SearchPhase.REFINED, finalState.phase)
            assertEquals(installed.corpus.sourceCommit, reloaded.corpus.sourceCommit)
            assertTrue("更新后断网，社保断缴条目仍须进入前三", topThree.any { it.document.title.contains("社保断缴") })
            topThree.forEach { hit ->
                assertTrue("离线结果原文必须非空", hit.document.body.isNotBlank())
                assertTrue("结果出处必须指向刚更新的完整提交", hit.document.sourceUrl.startsWith(
                    "https://github.com/eternity4719/HowToLiveBetter/blob/${installed.corpus.sourceCommit}/"))
                assertEquals("检索结果与重新加载的完整原文必须一致",
                    reloaded.corpus.documents.first { it.id == hit.document.id }, hit.document)
            }
            result.put("manualOfflineFailureKeptVersionAndPointer", true)
                .put("reloadedOfflineCommit", reloaded.corpus.sourceCommit).put("completed", true)
        } catch (failure: Throwable) {
            result.put("failure", failure.toString())
            throw failure
        } finally {
            try {
                result.put("sdk", Build.VERSION.SDK_INT).put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
                    .put("recordedAtEpochMs", System.currentTimeMillis())
                val directory = File(requireNotNull(context.getExternalFilesDir(null)), "test-results").apply { mkdirs() }
                File(directory, "network-update.json").writeText(result.toString(2))
                Log.i("LifeEvidenceNetworkTest", result.toString())
            } finally {
                ProxySelector.setDefault(originalProxySelector)
                ResponseCache.setDefault(originalResponseCache)
                withContext(Dispatchers.IO) {
                    if (originalActive == null) database.openHelper.writableDatabase.execSQL("DELETE FROM active_snapshot")
                    else database.userDao().activate(originalActive)
                    File(context.filesDir, "snapshots").listFiles()?.filter {
                        it.isDirectory && it.name !in initialDirectories && it.name.matches(Regex("[a-f0-9]{40}-[0-9]+"))
                    }?.forEach { it.deleteRecursively() }
                }
                model.close()
                val editor = preferences.edit()
                if (originalAutoAttempt == null) editor.remove("lastAutoAttempt") else editor.putString("lastAutoAttempt", originalAutoAttempt)
                editor.commit()
                device.executeShellCommand(if (wifiWasEnabled) "svc wifi enable" else "svc wifi disable")
                device.executeShellCommand(if (dataWasEnabled) "svc data enable" else "svc data disable")
            }
        }
        Unit
    }

    private suspend fun fetchHead(): String = withContext(Dispatchers.IO) {
        val connection = URL(HEAD_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "LifeEvidence-NetworkAcceptance/1.0")
        try {
            check(connection.responseCode == 200) { "独立 GitHub 请求失败：${connection.responseCode}" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 2 * 1024 * 1024) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            check(bytes.size <= 2 * 1024 * 1024) { "独立 Git 引用响应超出支持大小" }
            GitHead.parse(bytes)
        } finally { connection.disconnect() }
    }

    private fun successful(status: String) = status.startsWith("已是最新内容") || status.startsWith("内容已更新")
    private class UnreachableProxySelector : ProxySelector() {
        val selections = AtomicInteger()
        val connectionFailures = AtomicInteger()
        @Volatile var lastFailure: String? = null
        private val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))

        override fun select(uri: URI): MutableList<Proxy> {
            selections.incrementAndGet()
            return mutableListOf(proxy)
        }

        override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) {
            connectionFailures.incrementAndGet()
            lastFailure = "${failure.javaClass.name}: ${failure.message}"
            // Kotlin can propagate the original checked IOException from this Java callback.
            // This exits Android's automatic DIRECT recovery only after the real socket failed.
            throw failure
        }
    }
    private fun hasInternetNetwork(manager: ConnectivityManager) =
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    private fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

    companion object {
        private const val HEAD_URL = GitHead.URL
        private const val ROUTE_SELECTOR_SOURCE = "https://android.googlesource.com/platform/external/okhttp/+/refs/heads/android15-release/okhttp/src/main/java/com/squareup/okhttp/internal/http/RouteSelector.java"
    }
}
