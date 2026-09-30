package app.shenghuoyouju

import app.shenghuoyouju.data.ContentDocument
import app.shenghuoyouju.data.CorpusSnapshot
import app.shenghuoyouju.ui.buildDirectoryTree
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DirectoryTreeTest {
    @Test fun groupingKeepsFirstChapterAppearanceAndDocumentSourceOrder() {
        val documents = listOf(
            document("1", chapter = "第二章"),
            document("2", chapter = "第一章"),
            document("3", chapter = "第二章"),
        )
        val chapters = buildDirectoryTree(documents)

        assertEquals(listOf("第二章", "第一章"), chapters.map { it.title })
        assertEquals(listOf("1", "3"), chapters.first().documents.map { it.id })
        assertEquals(listOf(2, 1), chapters.map { it.totalCount })
    }

    @Test fun blankChaptersUseFallbackWithoutMergingAnExplicitChapterOfTheSameName() {
        val chapters = buildDirectoryTree(listOf(
            document("1", chapter = ""),
            document("2", chapter = "  "),
            document("3", chapter = "生活资料"),
            document("4", chapter = "  普通章节  "),
            document("5", chapter = "普通章节"),
        ))

        assertEquals(listOf("生活资料", "生活资料", "普通章节"), chapters.map { it.title })
        assertEquals(listOf(2, 1, 2), chapters.map { it.totalCount })
        assertEquals(chapters.size, chapters.map { it.key }.distinct().size)
    }

    @Test fun sameChapterNamesAcrossKindsRemainSeparateAndKeysDoNotCollide() {
        val chapters = buildDirectoryTree(listOf(
            document("1", chapter = "同名章节", kind = "entry"),
            document("2", chapter = "同名章节", kind = "article"),
            document("3", chapter = "b:c", kind = "a"),
            document("4", chapter = "c", kind = "a:b"),
        ))

        assertEquals(4, chapters.size)
        assertEquals(4, chapters.map { it.key }.distinct().size)
        assertEquals(listOf("entry", "article", "a", "a:b"), chapters.map { it.kind })
    }

    @Test fun titleQueryTrimsWhitespaceIgnoresCaseAndKeepsOriginalCountAndKey() {
        val documents = listOf(
            document("1", title = "认识 HR"),
            document("2", title = "认识 RR"),
            document("3", title = "HR 的含义"),
        )
        val filtered = buildDirectoryTree(documents, query = "  hr  ").single()

        assertEquals(listOf("1", "3"), filtered.documents.map { it.id })
        assertEquals(3, filtered.totalCount)
        assertEquals(buildDirectoryTree(documents).single().key, filtered.key)
    }

    @Test fun chapterQueryIncludesEveryDocumentInTheMatchingChapter() {
        val documents = listOf(
            document("1", chapter = "不要早死", title = "系安全带"),
            document("2", chapter = "不要早死", title = "戴头盔"),
            document("3", chapter = "不要浪费钱", title = "货比三家"),
        )
        val chapters = buildDirectoryTree(documents, query = "早死")

        assertEquals(1, chapters.size)
        assertEquals(listOf("1", "2"), chapters.single().documents.map { it.id })
    }

    @Test fun everyQueryWordMustMatchTheChapterOrDocumentTitle() {
        val documents = listOf(
            document("1", chapter = "不要早死", title = "系安全带"),
            document("2", chapter = "不要早死", title = "戴头盔"),
            document("3", chapter = "出行", title = "系安全带"),
        )
        val chapters = buildDirectoryTree(documents, query = "  早死\t安全带\n")

        assertEquals(listOf("1"), chapters.single().documents.map { it.id })
        assertEquals(2, chapters.single().totalCount)
        assertTrue(buildDirectoryTree(documents, query = "早死 不存在").isEmpty())
    }

    @Test fun directoryQueryDoesNotMatchTheBodyOrIntroduction() {
        val documents = listOf(document("1").copy(intro = "简介词", body = "正文词"))

        assertTrue(buildDirectoryTree(documents, query = "简介词").isEmpty())
        assertTrue(buildDirectoryTree(documents, query = "正文词").isEmpty())
    }

    @Test fun kindAndQueryFiltersApplyTogetherAndEmptyResultsHaveNoChapters() {
        val documents = listOf(
            document("1", kind = "entry", title = "HR"),
            document("2", kind = "term", title = "HR"),
            document("3", kind = "term", title = "RR"),
        )
        val chapters = buildDirectoryTree(documents, kind = "term", query = "hr")

        assertEquals(listOf("2"), chapters.single().documents.map { it.id })
        assertEquals(2, chapters.single().totalCount)
        assertTrue(buildDirectoryTree(documents, kind = "article").isEmpty())
        assertTrue(buildDirectoryTree(documents, kind = "term", query = "不存在").isEmpty())
        assertTrue(buildDirectoryTree(emptyList()).isEmpty())
        assertEquals(buildDirectoryTree(documents), buildDirectoryTree(documents, query = " \t\n "))
    }

    @Test fun bundledCorpusKeepsEveryDocumentUnderItsRealChapter() {
        val assets = File("src/main/assets").takeIf { it.exists() } ?: File("app/src/main/assets")
        val corpus = Json { ignoreUnknownKeys = true }.decodeFromString<CorpusSnapshot>(File(assets, "corpus.json").readText())
        val chapters = buildDirectoryTree(corpus.documents)
        val expected = corpus.documents.groupBy { it.kind to it.chapter.trim() }

        assertEquals(expected.keys.toList(), chapters.map { it.kind to it.title })
        assertEquals(corpus.documents.size, chapters.sumOf { it.documents.size })
        assertEquals(corpus.documents.size, chapters.flatMap { it.documents }.map { it.id }.distinct().size)
        chapters.forEach { chapter ->
            assertEquals(expected.getValue(chapter.kind to chapter.title), chapter.documents)
        }
        assertEquals("长文", chapters.single { it.kind == "article" }.title)
        assertEquals("读懂数字（术语表）", chapters.single { it.kind == "term" }.title)
        assertEquals("不要早死", chapters.first { it.kind == "entry" }.title)
    }

    private fun document(
        id: String,
        chapter: String = "测试章节",
        kind: String = "entry",
        title: String = "条目 $id",
    ) = ContentDocument(id, "book/test.md", kind, chapter, title, "", "", "", "")
}
