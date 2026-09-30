package app.shenghuoyouju.ui

import app.shenghuoyouju.data.ContentDocument

data class DirectoryChapter(
    val key: String,
    val kind: String,
    val title: String,
    val documents: List<ContentDocument>,
    val totalCount: Int,
)

/** Source chapters form the first level; their documents form the second level. */
fun buildDirectoryTree(
    documents: List<ContentDocument>,
    kind: String = "",
    query: String = "",
): List<DirectoryChapter> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return documents.asSequence()
        .filter { kind.isEmpty() || it.kind == kind }
        .groupBy { it.kind to it.chapter.trim() }
        .mapNotNull { (identity, chapterDocuments) ->
            val (chapterKind, chapter) = identity
            val title = chapter.ifEmpty { "生活资料" }
            // Each word must occur in the chapter or document title; bodies are not searched.
            val matches = chapterDocuments.filter { document ->
                words.all { word -> title.contains(word, ignoreCase = true) || document.title.contains(word, ignoreCase = true) }
            }
            if (matches.isEmpty()) null else DirectoryChapter(
                // Length prefixes keep distinct identities unique even when names contain separators.
                key = "chapter:${chapterKind.length}:$chapterKind:${chapter.length}:$chapter",
                kind = chapterKind,
                title = title,
                documents = matches,
                totalCount = chapterDocuments.size,
            )
        }
}
