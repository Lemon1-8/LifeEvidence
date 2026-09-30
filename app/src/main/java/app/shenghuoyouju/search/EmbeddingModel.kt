package app.shenghuoyouju.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.security.MessageDigest

/** The same INT8 graph produces both bundled document vectors and live device embeddings. */
class EmbeddingModel(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private val manifest by lazy {
        JSONObject(appContext.assets.open("model_manifest.json").bufferedReader().use { it.readText() })
    }
    private val tokenizer by lazy {
        BertTokenizer(appContext.assets.open("vocab.txt").bufferedReader().use { it.readLines() })
    }
    private var loadedSession: OrtSession? = null
    private var closed = false
    val dimension = 512

    suspend fun encodeQuery(text: String): FloatArray = encode("为这个句子生成表示以用于检索相关文章：" + text)

    suspend fun encodePassage(text: String): FloatArray = encode(text)

    private suspend fun encode(text: String): FloatArray = withContext(Dispatchers.Default) {
        mutex.withLock {
            check(!closed) { "模型已经关闭" }
            val session = loadedSession ?: loadSession().also { loadedSession = it }
            val encoded = tokenizer.encode(text, manifest.getInt("tokenLimit"))
            val shape = longArrayOf(1, encoded.inputIds.size.toLong())
            OnnxTensor.createTensor(environment, LongBuffer.wrap(encoded.inputIds), shape).use { ids ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(encoded.attentionMask), shape).use { mask ->
                    OnnxTensor.createTensor(environment, LongBuffer.wrap(encoded.tokenTypeIds), shape).use { types ->
                        session.run(mapOf("input_ids" to ids, "attention_mask" to mask, "token_type_ids" to types)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val vector = (result[0].value as Array<FloatArray>)[0].copyOf()
                            check(vector.size == dimension && vector.all { it.isFinite() }) { "模型输出无效" }
                            vector
                        }
                    }
                }
            }
        }
    }

    private fun loadSession(): OrtSession {
        val expectedHash = manifest.getString("modelSha256")
        val file = File(appContext.noBackupFilesDir, "bge-$expectedHash.onnx")
        if (!file.exists()) {
            val temporary = File.createTempFile("bge-model-", ".tmp", appContext.noBackupFilesDir)
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                appContext.assets.open("model.onnx").use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val length = input.read(buffer)
                            if (length < 0) break
                            digest.update(buffer, 0, length)
                            output.write(buffer, 0, length)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == expectedHash) { "离线模型校验失败" }
                // Concurrent repository/test instances may finish the identical copy first.
                check(temporary.renameTo(file) || file.exists()) { "无法保存离线模型" }
            } finally {
                temporary.delete()
            }
        }
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(4)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(file.absolutePath, options)
        }
    }

    override fun close() {
        runBlocking {
            mutex.withLock {
                closed = true
                loadedSession?.close()
                loadedSession = null
            }
        }
    }
}
