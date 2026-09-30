package app.shenghuoyouju

import app.shenghuoyouju.data.CorpusSnapshot
import app.shenghuoyouju.search.LexicalAsset
import app.shenghuoyouju.search.LexicalIndex
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LexicalAssetTest {
    private val assets = File("src/main/assets").takeIf { it.exists() } ?: File("app/src/main/assets")
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun everyPrecomputedFrequencyEqualsTheRuntimeTokenizer() {
        val corpus = json.decodeFromString<CorpusSnapshot>(File(assets, "corpus.json").readText())
        val precomputed = json.decodeFromString<LexicalAsset>(File(assets, "lexical.json").readText())
        assertEquals(corpus.sourceCommit, precomputed.sourceCommit)
        assertEquals(corpus.documents.map { it.id }, precomputed.documentIds)
        val runtime = LexicalIndex.buildFrequencies(corpus)
        assertEquals(runtime.size, precomputed.frequencies.size)
        corpus.documents.forEachIndexed { index, document ->
            assertEquals("${document.id}: ${document.title}", runtime[index], precomputed.frequencies[index])
        }
    }
}
