package app.shenghuoyouju.search

import app.shenghuoyouju.data.*
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable

/** Two independent retrieval paths. A rank is never interpreted as a probability. */
class SearchEngine(private val repository: ContentRepository, private val model: EmbeddingModel) {
    private var cachedCorpus: IndexedCorpus? = null
    private var cachedIndex: LexicalIndex? = null
    private val cacheLock = Any()

    fun search(query: String, filters: SearchFilters = SearchFilters(), semantic: Boolean = true): Flow<SearchState> = flow {
        if (query.isBlank()) { emit(SearchState()); return@flow }
        val indexed = requireNotNull(repository.current.value) { "正文尚在准备" }
        val lexical = synchronized(cacheLock) {
            if (cachedCorpus !== indexed) { cachedIndex = LexicalIndex(indexed.corpus, indexed.lexicalFrequencies); cachedCorpus = indexed }
            cachedIndex!!
        }
        val candidates = lexical.rank(query)
        val eligible = indexed.corpus.documents.filter { (filters.kinds.isEmpty() || it.kind in filters.kinds) && (filters.chapter == null || it.chapter == filters.chapter) }.map { it.id }.toSet()
        val fastHits = candidates.filter { it.document.id in eligible && it.reliable }.take(30).map { it.toHit(query, lexical) }
        emit(SearchState(query, fastHits, SearchPhase.LEXICAL))
        if (!semantic) return@flow
        delay(200) // ViewModel already applies the initial 150 ms debounce.
        currentCoroutineContext().ensureActive()
        repository.searchBusy = true
        val embedding = try { model.encodeQuery(query) } finally { repository.searchBusy = false }
        currentCoroutineContext().ensureActive()
        val combined = refine(indexed, query, embedding, candidates, eligible)
        emit(SearchState(query, combined, if (combined.isEmpty()) SearchPhase.EMPTY else SearchPhase.REFINED))
    }.flowOn(Dispatchers.Default)

    companion object {
        fun refine(indexed: IndexedCorpus, query: String, embedding: FloatArray,
            candidates: List<LexicalCandidate> = LexicalIndex(indexed.corpus).rank(query),
            eligible: Set<String> = indexed.corpus.documents.map { it.id }.toSet()): List<SearchHit> {
        val best = HashMap<String, Pair<Float, ContentChunk>>()
        indexed.corpus.chunks.forEachIndexed { index, chunk ->
            if (chunk.documentId in eligible) {
                var dot = 0f
                val vector = indexed.vectors[index]
                for (i in embedding.indices) dot += embedding[i] * vector[i]
                if (dot > (best[chunk.documentId]?.first ?: -1f)) best[chunk.documentId] = dot to chunk
            }
        }
        val semanticRanks = best.entries.sortedByDescending { it.value.first }.mapIndexed { i, e -> e.key to i }.toMap()
        val lexicalRanks = candidates.mapIndexed { i, c -> c.document.id to i }.toMap()
        val queryTokens = QueryTerms.tokens(QueryTerms.expand(query))
        // In a situation + question sentence, the final clause is usually the thing being asked about.
        val focus = QueryTerms.cleanQuestion(query.substringAfterLast('，').substringAfterLast(',')).trim()
        val focusTokens = QueryTerms.tokens(focus).filter { it.length >= 2 }
        val combined = candidates.mapNotNull { candidate ->
            val pair = best[candidate.document.id] ?: return@mapNotNull null
            val cosine = pair.first
            val focusedTitle = focusTokens.isNotEmpty() && focusTokens.size <= 3 && focusTokens.all { candidate.document.title.take(10).contains(it, true) }
            // A literal short title keyword is already strong evidence. Sentence embeddings
            // can under-score underspecified inputs such as 社保 or 感冒.
            val literalKeyword = query.trim().takeIf { it.length in 2..6 && it.all { c -> c.isLetterOrDigit() } }
            val exactTitleKeyword = literalKeyword != null && candidate.document.title.contains(literalKeyword, true) && candidate.reliable
            val direct = exactTitleKeyword || (cosine >= DIRECT_THRESHOLD && (candidate.coverage >= .18 || cosine >= SEMANTIC_ONLY_THRESHOLD)) || (candidate.reliable && cosine >= LEXICAL_SUPPORT_THRESHOLD) || (focusedTitle && candidate.coverage >= .4 && cosine >= LEXICAL_SUPPORT_THRESHOLD)
            val related = !direct && cosine >= RELATED_THRESHOLD && candidate.coverage >= .15 && candidate.titleMatches > 0
            if (!direct && !related) return@mapNotNull null
            val rank = 1f / (60 + semanticRanks.getValue(candidate.document.id)) + 1f / (60 + lexicalRanks.getValue(candidate.document.id))
            val scopeTerms = listOf("孩子", "儿童", "婴儿", "新生儿", "孕妇", "怀孕", "老人")
            val unrequestedScope = scopeTerms.any { candidate.document.title.contains(it) && !query.contains(it) }
            val score = rank + cosine * .06f + (if (direct) .02f else 0f) + (if (focusedTitle) .025f else 0f) - (if (unrequestedScope) .008f else 0f)
            val chunk = pair.second
            SearchHit(candidate.document, excerpt(chunk.text, queryTokens), chunk.start, score, relatedOnly = related)
        }.sortedWith(compareBy<SearchHit> { it.relatedOnly }.thenByDescending { it.score }).take(30)
        return combined
        }
        // Calibrated against the development split; held-out cases are reported separately.
        const val DIRECT_THRESHOLD = .68f
        const val SEMANTIC_ONLY_THRESHOLD = .70f
        const val LEXICAL_SUPPORT_THRESHOLD = .60f
        const val RELATED_THRESHOLD = .64f
        fun excerpt(text: String, tokens: Set<String>): String {
            val clean = text.replace(Regex("https?://\\S+"), "").replace(Regex("[#*_>`]"), "").replace(Regex("\\s+"), " ").trim()
            val first = tokens.filter { it.length >= 2 }.map { clean.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
            val start = (first - 35).coerceAtLeast(0)
            return (if (start > 0) "…" else "") + clean.substring(start, (start + 160).coerceAtMost(clean.length)) + if (start + 160 < clean.length) "…" else ""
        }
    }
}

data class LexicalCandidate(val document: ContentDocument, val score: Double, val coverage: Double, val titleMatches: Int, val reliable: Boolean)

@Serializable data class LexicalAsset(val sourceCommit: String, val documentIds: List<String>, val frequencies: List<Map<String, Int>>)

class LexicalIndex(corpus: CorpusSnapshot, precomputed: List<Map<String, Int>>? = null) {
    private val documents = corpus.documents
    private val frequencies = precomputed ?: buildFrequencies(corpus)
    private val lengths = frequencies.map { it.values.sum().coerceAtLeast(1) }
    private val averageLength = lengths.average().coerceAtLeast(1.0)
    private val documentFrequency = HashMap<String, Int>().apply { frequencies.forEach { map -> map.keys.forEach { token -> this[token] = getOrDefault(token, 0) + 1 } } }

    companion object {
        fun buildFrequencies(corpus: CorpusSnapshot): List<Map<String, Int>> {
            val textByDocument = corpus.chunks.groupBy { it.documentId }.mapValues { (_, chunks) -> chunks.joinToString("\n") { it.text } }
            return corpus.documents.map { doc ->
                val text = doc.title + " " + doc.title + " " + doc.title + " " + doc.intro + " " + textByDocument[doc.id].orEmpty()
                QueryTerms.tokenList(text).groupingBy { it }.eachCount()
            }
        }
    }

    fun rank(query: String): List<LexicalCandidate> {
        val originalTokens = QueryTerms.tokens(QueryTerms.cleanQuestion(query))
        val expanded = QueryTerms.tokens(QueryTerms.expand(query))
        return documents.mapIndexed { index, doc ->
            val frequency = frequencies[index]
            var score = 0.0
            expanded.forEach { token ->
                val tf = frequency[token]?.toDouble() ?: return@forEach
                val df = documentFrequency[token] ?: 0
                val idf = ln(1.0 + (documents.size - df + .5) / (df + .5))
                score += idf * tf * 2.2 / (tf + 1.2 * (.25 + .75 * lengths[index] / averageLength))
            }
            val matched = originalTokens.count { it in frequency }
            val coverage = matched.toDouble() / originalTokens.size.coerceAtLeast(1)
            val titleMatches = expanded.count { doc.title.contains(it, ignoreCase = true) }
            val reliable = score > 0 && coverage >= .55 && (titleMatches > 0 || originalTokens.size <= 2 && matched == originalTokens.size)
            LexicalCandidate(doc, score + titleMatches * 2.0, coverage, titleMatches, reliable)
        }.sortedByDescending { it.score }
    }

    fun snippet(document: ContentDocument, query: String): Pair<String, Int> {
        val terms = QueryTerms.tokens(QueryTerms.expand(query))
        val paragraphs = document.body.split(Regex("\\n\\s*\\n"))
        val best = paragraphs.maxByOrNull { paragraph -> terms.count { paragraph.contains(it, true) } } ?: document.intro
        return SearchEngine.excerpt(best, terms) to document.body.indexOf(best).coerceAtLeast(0)
    }
}

private fun LexicalCandidate.toHit(query: String, index: LexicalIndex): SearchHit {
    val (snippet, offset) = index.snippet(document, query)
    return SearchHit(document, snippet, offset, score.toFloat())
}

object QueryTerms {
    private val filler = Regex("怎么办|怎么做|是什么|什么情况|有哪些|为什么|到底|怎样|如何|能不能|可不可以|有没有|要不要|能否|怎么|什么|哪些|应该|需要|一下|一直|有点|完全|才能|请问|帮我|我想|我们|你们|[的了呢吗呀啊嘛么]")
    private val aliases = listOf(
        listOf("欠薪", "不给钱", "不发工资", "拖工资", "拖着不给", "拖欠工资", "讨薪"),
        listOf("离职", "辞职", "不干了", "走人", "失业"),
        listOf("社保", "社会保险", "五险", "养老保险", "医保"),
        listOf("失眠", "睡不着", "睡不好", "入睡困难"),
        listOf("劳动仲裁", "劳动争议", "公司欠钱"),
        listOf("押金", "租房", "房东", "退租"),
        listOf("诈骗", "被骗", "骗钱", "上当"),
        listOf("手机丢失", "手机丢了", "丢手机", "手机被偷"),
        listOf("账户安全", "账号被盗", "盗号", "密码泄露"),
        listOf("心肺复苏", "没呼吸", "心脏骤停", "按压"),
        listOf("拖延", "拖着不做", "迟迟不动", "不想开始"),
        listOf("注意力", "精力", "分心", "老被打断"),
        listOf("保健品", "补品", "营养品"),
        listOf("工伤", "上班受伤", "工作受伤"),
        listOf("感冒", "流鼻涕", "鼻塞", "伤风"),
        listOf("异地就医", "外地看病", "跨省看病"),
        listOf("戒烟", "不抽烟", "烟瘾"),
        listOf("担保", "作保", "替人签字", "替朋友担保"),
    )
    fun cleanQuestion(text: String): String = filler.replace(text.lowercase(), " ")
    fun expand(text: String): String {
        val additions = aliases.filter { group -> group.any { text.contains(it, true) } }.flatMap { it.take(2) }
        return cleanQuestion(text) + " " + additions.joinToString(" ")
    }
    fun tokens(text: String): Set<String> = tokenList(text).toSet()
    fun tokenList(text: String): List<String> = buildList {
        Regex("[\\p{IsHan}]+|[a-zA-Z0-9]+").findAll(text.lowercase()).forEach { match ->
            val part = match.value
            if (part[0].code in 0x3400..0x9fff) {
                if (part.length == 1) add(part) else for (i in 0 until part.length - 1) add(part.substring(i, i + 2))
            } else if (part.length > 1) add(part)
        }
    }
}
