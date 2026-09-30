package app.shenghuoyouju.data

import java.security.MessageDigest

/** Deterministic twin of tools/build_assets.py. UTF-16 offsets match Android String indices. */
object CorpusParser {
    fun discoverPaths(readme: String): List<String> = listOf("README.md") +
        Regex("\\]\\(((?:book|docs)/[^)#]+\\.md)\\)").findAll(readme).map { it.groupValues[1] }
            .filter { it.startsWith("book/") || (it.count { c -> c == '/' } == 1 && it != "docs/引用对照.md") }
            .toSet().sorted()

    fun parse(sourceCommit: String, files: Map<String, String>): CorpusSnapshot {
        require(sourceCommit.matches(Regex("[a-f0-9]{40}"))) { "内容版本必须为完整提交" }
        val documents = mutableListOf<ContentDocument>()
        val chunks = linkedMapOf<String, ContentChunk>()

        fun add(path: String, kind: String, chapter: String, title: String, intro: String,
                body: String, indexedBody: String, indexedOffset: Int) {
            val normalizedPath = path.replace(Regex("book/\\d+-"), "book/")
            val normalizedTitle = normalizeTitle(title)
            val id = sha("$normalizedPath\n$normalizedTitle").take(24)
            documents += ContentDocument(id, path, kind, chapter, normalizedTitle, intro, body,
                "https://github.com/eternity4719/HowToLiveBetter/blob/$sourceCommit/$path", sha(body))
            val context = takeSafe(normalizedTitle, 100) + "\n"
            val limit = 340 - context.length
            val seen = mutableSetOf<String>()
            var hasChunk = false
            for (match in Regex("[^\n]+").findAll(indexedBody)) {
                val line = match.value
                if (line.startsWith("#") || line.startsWith("<!--") || line.startsWith("- 来源：")) continue
                val text = clean(line)
                if (text.isEmpty() || !seen.add(text)) continue
                val pieces = mutableListOf<String>()
                var current = ""
                for (sentenceMatch in Regex("[^。！？；]+[。！？；]?").findAll(text)) {
                    var sentence = sentenceMatch.value
                    while (sentence.length > limit) {
                        if (current.isNotEmpty()) { pieces += current; current = "" }
                        val part = takeSafe(sentence, limit)
                        pieces += part
                        sentence = sentence.drop(part.length)
                    }
                    if ((current + sentence).length > limit) { pieces += current; current = "" }
                    current += sentence
                }
                if (current.isNotEmpty()) pieces += current
                for (piece in pieces) {
                    val passage = context + piece
                    val chunkId = sha("$id\n$passage").take(24)
                    chunks[chunkId] = ContentChunk(chunkId, id, passage, indexedOffset + match.range.first)
                    hasChunk = true
                }
            }
            if (!hasChunk) {
                val passage = takeSafe(context + clean(indexedBody), 340)
                val chunkId = sha("$id\n$passage").take(24)
                chunks[chunkId] = ContentChunk(chunkId, id, passage, indexedOffset)
            }
        }

        val readme = requireNotNull(files["README.md"]) { "缺少目录" }.replace("\r\n", "\n")
        val paths = discoverPaths(readme)
        require(paths.any { it.startsWith("book/") }) { "无法识别正文目录" }
        for (path in paths) {
            if (path == "README.md") continue
            val text = requireNotNull(files[path]) { "缺少正文：$path" }.replace("\r\n", "\n")
            val titleMatch = requireNotNull(Regex("^# (.+)$", RegexOption.MULTILINE).find(text)) { "无法识别标题：$path" }
            val chapter = normalizeTitle(titleMatch.groupValues[1])
            if (path.startsWith("book/")) {
                val entries = Regex("^### (\\d+[.、．] .+)$", RegexOption.MULTILINE).findAll(text).toList()
                require(entries.isNotEmpty()) { "无法识别条目：$path" }
                val preface = text.substring(titleMatch.range.last + 1, entries.first().range.first).trim()
                for ((index, entry) in entries.withIndex()) {
                    val raw = text.substring(entry.range.first, entries.getOrNull(index + 1)?.range?.first ?: text.length).trim()
                    val summary = Regex("^- 说人话：(.*)$", RegexOption.MULTILINE).find(raw)?.groupValues?.get(1)
                    val prefix = if (preface.isNotEmpty()) "## 本章说明\n\n$preface\n\n" else ""
                    add(path, "entry", chapter, entry.groupValues[1], summary?.let(::clean) ?: chapter,
                        prefix + raw, raw, prefix.length)
                }
            } else {
                val body = text.substring(titleMatch.range.first).trim()
                val first = Regex("\n\n([^#\n][^\n]*)").find(body)?.groupValues?.get(1)
                add(path, "article", "长文", chapter, first?.let(::clean) ?: chapter, body, body, 0)
            }
        }
        require(readme.contains("## 读懂数字（术语表）")) { "无法识别术语表" }
        val glossary = readme.substringAfter("## 读懂数字（术语表）").substringBefore("## 目录")
        for (line in glossary.lines()) {
            val match = Regex("\\| ([^|]+) \\| ([^|]+) \\|").find(line) ?: continue
            val term = match.groupValues[1].trim()
            if (term == "术语" || term == "---") continue
            val definition = match.groupValues[2].trim()
            val body = "# $term\n\n$definition"
            add("README.md", "term", "读懂数字（术语表）", term, definition, body, body, 0)
        }
        require(documents.map { it.id }.distinct().size == documents.size) { "存在重复文档标识" }
        require(documents.isNotEmpty() && chunks.isNotEmpty()) { "新版本没有可检索内容" }
        return CorpusSnapshot(sourceCommit, documents, chunks.values.toList())
    }

    private fun normalizeTitle(title: String) = title.replace(Regex("^\\d+[.、．]\\s*"), "").trim()
    private fun clean(text: String): String = text.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("<https?://[^>]+>"), "").replace(Regex("https?://\\S+"), "")
        .replace(Regex("<[^>]+>"), "").replace(Regex("^[#>\\-]+\\s*", RegexOption.MULTILINE), "")
        .replace("**", "").replace("`", "").replace(Regex("\\s+"), " ").trim()
    private fun takeSafe(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val end = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
        return text.substring(0, end)
    }
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
