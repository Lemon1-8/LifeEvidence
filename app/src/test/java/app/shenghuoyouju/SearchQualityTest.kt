package app.shenghuoyouju

import app.shenghuoyouju.data.*
import app.shenghuoyouju.search.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SearchQualityTest {
    @Test fun evaluateIndependentRetrievalAgainstAnnotatedCases() {
        val json = Json { ignoreUnknownKeys = true }
        val assets = File("src/main/assets")
        val corpus = json.decodeFromString<CorpusSnapshot>(File(assets, "corpus.json").readText())
        val buffer = ByteBuffer.wrap(File(assets, "vectors.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val indexed = IndexedCorpus(corpus, Array(corpus.chunks.size) { FloatArray(512).also { buffer.get(it) } }, "test")
        val lexical = LexicalIndex(corpus)
        val vectors = json.parseToJsonElement(File("../tests/query_vectors.json").readText()).jsonArray.associate { row ->
            row.jsonObject.getValue("query").jsonPrimitive.content to row.jsonObject.getValue("vector").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        }
        val split = System.getProperty("life.search.split", "dev")
        val cases = json.parseToJsonElement(File("../tests/search_cases.json").readText()).jsonArray.map { it.jsonObject }.filter { it.getValue("split").jsonPrimitive.content == split }
        val outcomes = cases.map { case ->
            val query = case.getValue("query").jsonPrimitive.content
            val expected = case.getValue("expect").jsonPrimitive.content
            val terms = case.getValue("targetTerms").jsonArray.map { it.jsonPrimitive.content }
            val candidates = lexical.rank(query)
            val hits = SearchEngine.refine(indexed, query, vectors.getValue(query), candidates)
            val hit = hits.take(3).any { result -> terms.any { result.document.title.contains(it, true) } }
            val passed = when (expected) { "direct" -> hit && hits.take(3).any { !it.relatedOnly }; "none" -> hits.isEmpty(); else -> hits.isEmpty() || hits.filterNot { it.relatedOnly }.isEmpty() || hit } && (case.getValue("id").jsonPrimitive.content != "user-02" || hit)
            buildJsonObject {
                put("id", case.getValue("id")); put("query", query); put("expected", expected); put("pass", passed)
                put("top3", buildJsonArray { hits.take(3).forEach { result -> add(buildJsonObject { put("title", result.document.title); put("relatedOnly", result.relatedOnly); put("score", result.score) }) } })
                put("diagnostic", buildJsonArray {
                    corpus.chunks.mapIndexed { i, chunk -> chunk.documentId to indexed.vectors[i].zip(vectors.getValue(query)).sumOf { (a,b) -> (a*b).toDouble() } }
                        .groupBy { it.first }.mapValues { (_,items) -> items.maxOf { it.second } }.entries.sortedByDescending { it.value }.take(5).forEach { match ->
                            val c = candidates.first { it.document.id == match.key }
                            add(buildJsonObject { put("title", c.document.title); put("cosine", match.value); put("coverage", c.coverage); put("titleMatches", c.titleMatches); put("reliable", c.reliable) })
                        }
                })
            }
        }
        val direct = outcomes.filter { it.getValue("expected").jsonPrimitive.content == "direct" }
        val directPass = direct.count { it.getValue("pass").jsonPrimitive.boolean }
        val none = outcomes.filter { it.getValue("expected").jsonPrimitive.content == "none" }
        val nonePass = none.count { it.getValue("pass").jsonPrimitive.boolean }
        val outDir = File("../artifacts/test-results/search").apply { mkdirs() }
        File(outDir, "$split.json").writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("split", split); put("total", outcomes.size); put("directPass", directPass); put("directTotal", direct.size); put("nonePass", nonePass); put("noneTotal", none.size); put("cases", JsonArray(outcomes))
        }))
        println("Search $split: direct top3 $directPass/${direct.size}; out-of-corpus $nonePass/${none.size}")
        assertTrue("Direct top3 rate below 90%; inspect artifacts/test-results/search/$split.json", directPass.toDouble() / direct.size >= .9)
        assertEquals("Out-of-corpus mismatch", none.size, nonePass)
        assertTrue("One of the user's mandatory cases failed", outcomes.filter { it.getValue("id").jsonPrimitive.content.startsWith("user-") }.all { it.getValue("pass").jsonPrimitive.boolean })
    }
}
