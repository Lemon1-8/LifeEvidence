package app.shenghuoyouju

import app.shenghuoyouju.data.CorpusParser
import app.shenghuoyouju.data.CorpusSnapshot
import app.shenghuoyouju.search.BertTokenizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class AssetParityTest {
    private val assets = File("src/main/assets").takeIf { it.exists() } ?: File("app/src/main/assets")
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun tokenizerMatchesPinnedHuggingFaceForChineseEnglishMixedSpecialAndTruncation() {
        val tokenizer = BertTokenizer(File(assets, "vocab.txt").readLines())
        val fixtures = json.parseToJsonElement(File(assets, "tokenizer_golden.json").readText()).jsonArray
        for (fixture in fixtures) {
            val item = fixture.jsonObject
            val text = item.getValue("text").jsonPrimitive.content
            val expected = item.getValue("inputIds").jsonArray.map { it.jsonPrimitive.long }.toLongArray()
            val actual = tokenizer.encode(text, item.getValue("maxLength").jsonPrimitive.int)
            assertArrayEquals(text, expected, actual.inputIds)
            assertTrue(actual.attentionMask.all { it == 1L })
            assertTrue(actual.tokenTypeIds.all { it == 0L })
        }
    }

    @Test fun mobileParserMatchesEveryBundledDocumentChunkAndOffset() {
        val original = json.decodeFromString<CorpusSnapshot>(File(assets, "corpus.json").readText())
        val files = json.parseToJsonElement(File(assets, "parser_source_fixture.json").readText()).jsonObject
            .mapValues { it.value.jsonPrimitive.content }
        val actual = CorpusParser.parse(original.sourceCommit, files)
        assertEquals(original.documents.size, actual.documents.size)
        assertEquals(original.chunks.size, actual.chunks.size)
        original.documents.zip(actual.documents).forEach { (expected, parsed) -> assertEquals(expected.title, expected, parsed) }
        original.chunks.zip(actual.chunks).forEach { (expected, parsed) -> assertEquals(expected.id, expected, parsed) }
        val tokenizer = BertTokenizer(File(assets, "vocab.txt").readLines())
        assertTrue(actual.chunks.all { tokenizer.encode(it.text, 1024).inputIds.size <= 350 })
    }

    @Test fun vectorsAreFiniteNormalizedAndManifestHashesMatch() {
        val manifest = json.parseToJsonElement(File(assets, "model_manifest.json").readText()).jsonObject
        val hashes = mapOf("model.onnx" to "modelSha256", "vocab.txt" to "tokenizerSha256",
            "corpus.json" to "corpusSha256", "vectors.bin" to "vectorsSha256", "lexical.json" to "lexicalSha256")
        for ((file, key) in hashes) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(assets, file).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(file, manifest.getValue(key).jsonPrimitive.content, hash)
        }
        val bytes = File(assets, "vectors.bin").readBytes()
        val count = manifest.getValue("chunkCount").jsonPrimitive.int
        assertEquals(count * 512 * 4, bytes.size)
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        repeat(count) {
            var squaredNorm = 0f
            repeat(512) {
                val value = floats.get()
                assertTrue(value.isFinite())
                squaredNorm += value * value
            }
            assertEquals(1f, squaredNorm, 0.001f)
        }
    }

    @Test fun parserRejectsMissingFilesAndUnknownFormat() {
        val commit = "a".repeat(40)
        val readme = "[正文](book/01-test.md)\n## 读懂数字（术语表）\n## 目录"
        assertThrows(IllegalArgumentException::class.java) { CorpusParser.parse(commit, mapOf("README.md" to readme)) }
        assertThrows(IllegalArgumentException::class.java) {
            CorpusParser.parse(commit, mapOf("README.md" to readme, "book/01-test.md" to "# 标题\n格式已经变化"))
        }
    }
}
