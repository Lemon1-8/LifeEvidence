package app.shenghuoyouju.data

import kotlinx.serialization.Serializable

@Serializable data class ContentDocument(
    val id: String, val path: String, val kind: String, val chapter: String,
    val title: String, val intro: String, val body: String, val sourceUrl: String,
    val contentHash: String,
)
@Serializable data class ContentChunk(val id: String, val documentId: String, val text: String, val start: Int)
@Serializable data class CorpusSnapshot(val sourceCommit: String, val documents: List<ContentDocument>, val chunks: List<ContentChunk>)
data class SearchHit(val document: ContentDocument, val snippet: String, val offset: Int, val score: Float, val relatedOnly: Boolean = false)
enum class SearchPhase { LEXICAL, REFINED, EMPTY }
data class SearchState(val query: String = "", val hits: List<SearchHit> = emptyList(), val phase: SearchPhase = SearchPhase.EMPTY)
data class SearchFilters(val kinds: Set<String> = emptySet(), val chapter: String? = null)
