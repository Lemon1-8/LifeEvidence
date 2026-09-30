package app.shenghuoyouju.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.shenghuoyouju.search.EmbeddingModel
import app.shenghuoyouju.search.LexicalAsset
import app.shenghuoyouju.search.LexicalIndex
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class IndexedCorpus(val corpus: CorpusSnapshot, val vectors: Array<FloatArray>, val modelHash: String,
    val lexicalFrequencies: List<Map<String, Int>>? = null)

/** Publication happens only after a complete candidate has been parsed, embedded and persisted. */
class ContentRepository(private val context: Context, private val model: EmbeddingModel) {
    val json = Json { ignoreUnknownKeys = true }
    private val dao = UserDatabase.get(context).userDao()
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val updateLock = Mutex()
    val current = MutableStateFlow<IndexedCorpus?>(null)
    val updateStatus = MutableStateFlow("内容已内置，可离线使用")
    val updating = MutableStateFlow(false)
    @Volatile var searchBusy = false
    @Volatile private var searchPriorityUntil = 0L

    fun prioritizeSearch() { searchPriorityUntil = android.os.SystemClock.elapsedRealtime() + 1_000 }

    suspend fun load(): IndexedCorpus = withContext(Dispatchers.IO) {
        val manifest = json.parseToJsonElement(context.assets.open("model_manifest.json").bufferedReader().use { it.readText() }).jsonObject
        val modelHash = listOf("modelSha256", "tokenizerSha256", "indexVersion").joinToString(":") { manifest[it]!!.jsonPrimitive.content }
        val active = dao.activeSnapshot()
        val persisted = active?.takeIf { it.modelHash == modelHash }?.let {
            runCatching {
                val dir = File(context.filesDir, it.directory)
                val corpus = json.decodeFromString<CorpusSnapshot>(File(dir, "corpus.json").readText())
                validate(corpus, readVectors(File(dir, "vectors.bin").readBytes(), corpus.chunks.size), modelHash)
                    .copy(lexicalFrequencies = readLexical(File(dir, "lexical.json").readText(), corpus))
            }.getOrNull()
        }
        val result = persisted ?: run {
            val corpus = json.decodeFromString<CorpusSnapshot>(context.assets.open("corpus.json").bufferedReader().use { it.readText() })
            validate(corpus, readVectors(context.assets.open("vectors.bin").use { it.readBytes() }, corpus.chunks.size), modelHash)
                .copy(lexicalFrequencies = readLexical(context.assets.open("lexical.json").bufferedReader().use { it.readText() }, corpus))
        }
        current.value = result
        result
    }

    fun onWifi(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun update(manual: Boolean = false) {
        if (!updateLock.tryLock()) return
        try {
            if (!manual) {
                if (!onWifi()) return
                val today = LocalDate.now().toString()
                if (preferences.getString("lastAutoAttempt", "") == today) return
                preferences.edit().putString("lastAutoAttempt", today).apply()
            }
            updating.value = true
            updateStatus.value = "正在检查内容更新…"
            withContext(Dispatchers.IO) {
                val commit = GitHead.parse(fetchBytes(GitHead.URL))
                if (current.value?.corpus?.sourceCommit == commit) {
                    updateStatus.value = "已是最新内容 · ${commit.take(7)}"
                    return@withContext
                }
                val files = linkedMapOf("README.md" to fetch(rawUrl(commit, "README.md")))
                val paths = CorpusParser.discoverPaths(files.getValue("README.md")).filter { it != "README.md" }
                require(paths.isNotEmpty()) { "未找到正文目录，继续使用原有内容" }
                paths.forEachIndexed { index, path ->
                    ensureActive()
                    updateStatus.value = "下载正文 ${index + 1}/${paths.size}"
                    files[path] = fetch(rawUrl(commit, path))
                }
                installCandidate(CorpusParser.parse(commit, files))
                updateStatus.value = "内容已更新 · ${commit.take(7)}"
            }
        } catch (cancelled: CancellationException) {
            updateStatus.value = "更新已暂停，原有内容仍可使用"
            throw cancelled
        } catch (failure: Exception) {
            updateStatus.value = "更新未完成，原有内容仍可使用：${failure.message?.take(110) ?: "请稍后重试"}"
        } finally {
            updating.value = false
            updateLock.unlock()
        }
    }

    /** Also used by instrumentation with altered or interrupted candidates. */
    suspend fun installCandidate(candidate: CorpusSnapshot, beforeActivate: suspend () -> Unit = {}) = withContext(Dispatchers.IO) {
        val previous = requireNotNull(current.value)
        require(candidate.documents.isNotEmpty() && candidate.chunks.isNotEmpty()) { "候选内容为空" }
        val oldVectors = previous.corpus.chunks.mapIndexed { index, chunk -> chunk.id to (chunk.text to previous.vectors[index]) }.toMap()
        val vectors = Array(candidate.chunks.size) { FloatArray(512) }
        candidate.chunks.forEachIndexed { index, chunk ->
            ensureActive()
            while (searchBusy || android.os.SystemClock.elapsedRealtime() < searchPriorityUntil) delay(100)
            val cached = oldVectors[chunk.id]
            vectors[index] = if (cached?.first == chunk.text) cached.second else model.encodePassage(chunk.text)
            updateStatus.value = "整理离线索引 ${index + 1}/${candidate.chunks.size}"
            yield()
        }
        val frequencies = LexicalIndex.buildFrequencies(candidate)
        val indexed = validate(candidate, vectors, previous.modelHash).copy(lexicalFrequencies = frequencies)
        val parent = File(context.filesDir, "snapshots").apply { mkdirs() }
        val directory = File(parent, "${candidate.sourceCommit.take(40)}-${System.nanoTime()}").apply { mkdirs() }
        File(directory, "corpus.json").writeText(json.encodeToString(candidate))
        File(directory, "lexical.json").writeText(json.encodeToString(LexicalAsset(candidate.sourceCommit, candidate.documents.map { it.id }, frequencies)))
        File(directory, "vectors.bin").outputStream().use { output ->
            val buffer = ByteBuffer.allocate(512 * 4).order(ByteOrder.LITTLE_ENDIAN)
            vectors.forEach { vector -> buffer.clear(); vector.forEach { buffer.putFloat(it) }; output.write(buffer.array()) }
        }
        // Reread persisted candidate before publishing it; no old files are modified.
        validate(json.decodeFromString(File(directory, "corpus.json").readText()), readVectors(File(directory, "vectors.bin").readBytes(), vectors.size), previous.modelHash)
        readLexical(File(directory, "lexical.json").readText(), candidate)
        beforeActivate()
        dao.activate(ActiveSnapshotRow(directory = "snapshots/${directory.name}", sourceCommit = candidate.sourceCommit, modelHash = previous.modelHash))
        current.value = indexed
    }

    private fun validate(corpus: CorpusSnapshot, vectors: Array<FloatArray>, modelHash: String): IndexedCorpus {
        require(corpus.documents.isNotEmpty() && corpus.documents.map { it.id }.toSet().size == corpus.documents.size) { "正文标识重复或缺失" }
        val documentIds = corpus.documents.map { it.id }.toSet()
        require(corpus.chunks.isNotEmpty() && corpus.chunks.size == vectors.size)
        require(corpus.chunks.map { it.id }.toSet().size == corpus.chunks.size) { "段落标识重复" }
        require(corpus.chunks.all { it.documentId in documentIds && it.text.isNotBlank() })
        require(vectors.all { it.size == 512 && it.all(Float::isFinite) && it.any { x -> x != 0f } }) { "向量索引损坏" }
        return IndexedCorpus(corpus, vectors, modelHash)
    }

    private fun readVectors(bytes: ByteArray, count: Int): Array<FloatArray> {
        require(bytes.size == count * 512 * 4) { "索引长度与正文不一致" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return Array(count) { FloatArray(512).also { buffer.get(it) } }
    }

    private fun readLexical(text: String, corpus: CorpusSnapshot): List<Map<String, Int>> {
        val data = json.decodeFromString<LexicalAsset>(text)
        require(data.sourceCommit == corpus.sourceCommit && data.documentIds == corpus.documents.map { it.id }) { "词法索引与正文版本不一致" }
        require(data.frequencies.size == corpus.documents.size && data.frequencies.all { row -> row.isNotEmpty() && row.values.all { it > 0 } }) { "词法索引损坏" }
        return data.frequencies
    }

    private fun rawUrl(commit: String, path: String): String {
        require(path == "README.md" || ((path.startsWith("book/") || path.startsWith("docs/")) && path.endsWith(".md") && ".." !in path))
        return "https://raw.githubusercontent.com/eternity4719/HowToLiveBetter/$commit/" + path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }

    private fun fetch(url: String): String = fetchBytes(url).toString(Charsets.UTF_8)

    private fun fetchBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", "LifeEvidence/1.0")
        return try {
            require(connection.responseCode == 200) { "网络请求失败 (${connection.responseCode})" }
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
            require(bytes.size <= 2 * 1024 * 1024) { "上游文件超出支持大小" }
            bytes
        } finally { connection.disconnect() }
    }
}
