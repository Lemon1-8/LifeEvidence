package app.shenghuoyouju

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.shenghuoyouju.data.ContentDocument
import app.shenghuoyouju.data.SearchHit
import app.shenghuoyouju.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Directory interactions use isolated in-memory content and never touch the user's database. */
@RunWith(AndroidJUnit4::class)
class DirectoryNavigationTest {
    @get:Rule val compose = createComposeRule()

    private val work = (0 until 18).map { index ->
        document("work$index", "book/01-work.md", "entry", "工作与保障", "社保申领 $index")
    }
    private val health = (0 until 3).map { index ->
        document("health$index", "book/02-health.md", "entry", "身体与用药", "用药提醒 $index")
    }
    private val article = document("article", "docs/work.md", "article", "长文", "社保长文")
    private val term = document("term", "README.md", "term", "读懂数字（术语表）", "相对风险")
    private val documents = work + health + article + term
    private val removed = document("removed", "book/old.md", "entry", "旧章节", "保留的旧版收藏")

    @Test
    fun chaptersStartCollapsedAndToggleIndependently() {
        showApp()
        openDirectory()
        chapter(work.first()).assert(stateDescription("已折叠"))
        chapter(health.first()).assert(stateDescription("已折叠"))
        compose.onAllNodes(tagStartingWith("document_")).assertCountEquals(0)

        chapter(health.first()).performClick()
        chapter(health.first()).assert(stateDescription("已展开"))
        compose.onNodeWithTag("document_${health.first().id}").assertIsDisplayed()
        chapter(work.first()).performClick()
        chapter(work.first()).assert(stateDescription("已展开"))
        compose.onNodeWithTag("document_${work.first().id}").assertIsDisplayed()
        chapter(work.first()).performClick()

        chapter(work.first()).assert(stateDescription("已折叠"))
        compose.onNodeWithTag("document_${work.first().id}").assertDoesNotExist()
        chapter(health.first()).assert(stateDescription("已展开"))
        compose.onNodeWithTag("document_${health.first().id}").assertIsDisplayed()
    }

    @Test
    fun readerBackAndTabChangesKeepDirectoryPosition() {
        showApp()
        openDirectory()
        chapter(work.first()).performClick()
        val selected = work[12]
        scrollToDocument(selected)
        val position = scrollPosition()
        assertTrue("测试必须先离开列表顶部", position > 0f)
        compose.onNodeWithTag("document_${selected.id}").performClick()
        compose.onNodeWithTag("back_button").assertContentDescriptionEquals("返回目录").performClick()
        assertEquals(position, scrollPosition(), .1f)
        compose.onNodeWithTag("document_${selected.id}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reader_body").assertIsDisplayed()
        compose.onNodeWithTag("back_button").assertIsDisplayed().assertContentDescriptionEquals("返回目录")

        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("directory_list").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("directory_list").assertIsDisplayed()
        assertEquals(position, scrollPosition(), .1f)
        compose.onNodeWithTag("nav_search").performClick()
        openDirectory()
        assertEquals(position, scrollPosition(), .1f)
        compose.onNodeWithTag("document_${selected.id}").assertIsDisplayed()
    }

    @Test
    fun savedStateRestoresQueryFilterAndManualExpansion() {
        val restoration = StateRestorationTester(compose)
        showApp(restoration)
        openDirectory()
        chapter(health.first()).performClick()
        compose.onNodeWithTag("directory_query").performTextReplacement("社保")
        compose.onNodeWithTag("directory_kind_entry").performClick()
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("nav_directory").assertIsSelected()
        compose.onNodeWithTag("directory_query").assertTextContains("社保")
        compose.onNodeWithTag("directory_kind_entry").assertIsSelected()
        chapter(work.first()).assert(stateDescription("已展开"))
        compose.onNodeWithTag("document_${work.first().id}").assertIsDisplayed()
        compose.onNodeWithTag("directory_clear_query").performClick()
        chapter(work.first()).assert(stateDescription("已折叠"))
        chapter(health.first()).assert(stateDescription("已展开"))
        compose.onNodeWithTag("document_${health.first().id}").assertIsDisplayed()
    }

    @Test
    fun queryAndKindsFilterTogetherAndResetScroll() {
        showApp()
        openDirectory()
        chapter(work.first()).performClick()
        scrollToDocument(work[12])
        compose.onNodeWithTag("directory_query").performTextReplacement("社保")
        assertEquals(0f, scrollPosition(), .001f)
        chapter(work.first()).assert(stateDescription("已展开"))
        scrollToDocument(work[12])
        compose.onNodeWithTag("directory_kind_article").performClick()
        assertEquals(0f, scrollPosition(), .001f)
        compose.onNodeWithTag("document_${article.id}").assertIsDisplayed()
        chapter(work.first()).assertDoesNotExist()

        compose.onNodeWithTag("directory_kind_entry").performClick()
        compose.onNodeWithTag("document_${work.first().id}").assertIsDisplayed()
        chapter(article).assertDoesNotExist()
        compose.onNodeWithTag("directory_kind_term").performClick()
        compose.onNodeWithTag("directory_empty").assertIsDisplayed()
        compose.onNodeWithTag("directory_clear_query").assertContentDescriptionEquals("清空目录筛选").performClick()
        chapter(term).assert(stateDescription("已折叠")).performClick()
        compose.onNodeWithTag("document_${term.id}").assertIsDisplayed()
    }

    @Test
    fun emptyDirectoryQueryCanContinueInFullTextSearch() {
        showApp()
        openDirectory()
        val query = "标题中没有的正文问题"
        compose.onNodeWithTag("directory_query").performTextReplacement(query)
        compose.onNodeWithTag("directory_empty").assertIsDisplayed()
        compose.onNodeWithTag("directory_full_search").performClick()
        compose.onNodeWithTag("nav_search").assertIsSelected()
        compose.onNodeWithTag("search_input").assertTextContains(query)
    }

    @Test
    fun collapseAllAlsoCollapsesSearchMatchesAndReturnsToTop() {
        showApp()
        openDirectory()
        compose.onNodeWithTag("directory_query").performTextReplacement("社保")
        scrollToDocument(work[12])
        assertTrue(scrollPosition() > 0f)
        compose.onNodeWithTag("directory_collapse_all").assertIsDisplayed().performClick()
        assertEquals(0f, scrollPosition(), .001f)
        compose.onNodeWithTag("directory_query").assertTextContains("社保")
        chapter(work.first()).assert(stateDescription("已折叠"))
        chapter(article).assert(stateDescription("已折叠"))
        compose.onAllNodes(tagStartingWith("document_")).assertCountEquals(0)
        chapter(work.first()).performClick()
        compose.onNodeWithTag("document_${work.first().id}").assertIsDisplayed()
    }

    @Test
    fun locateFromReaderClearsDirectoryFiltersAndRevealsTheArticle() {
        val selected = work[12]
        showApp(initialQuery = "社保", hits = listOf(SearchHit(selected, selected.intro, 0, 1f)))
        openDirectory()
        compose.onNodeWithTag("directory_kind_article").performClick()
        compose.onNodeWithTag("directory_query").performTextReplacement("不存在的目录")
        compose.onNodeWithTag("directory_empty").assertIsDisplayed()
        compose.onNodeWithTag("nav_search").performClick()
        compose.onNodeWithTag("result_card_${selected.id}").performClick()
        compose.onNodeWithTag("back_button").assertContentDescriptionEquals("返回搜索结果")
        compose.onNodeWithTag("reader_locate").assertContentDescriptionEquals("在目录中定位").performClick()

        compose.onNodeWithTag("nav_directory").assertIsSelected()
        assertEquals("", compose.onNodeWithTag("directory_query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("directory_kind_all").assertIsSelected()
        compose.onNodeWithTag("document_${selected.id}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("detail_title").assertTextEquals(selected.title)
        compose.onNodeWithTag("back_button").assertContentDescriptionEquals("返回目录")
    }

    @Test
    fun oldFavoriteReturnsToFavoritesWithoutOfferingAnInvalidDirectoryTarget() {
        showApp()
        compose.onNodeWithTag("nav_favorites").performClick()
        compose.onNodeWithTag("document_${removed.id}").performClick()
        compose.onNodeWithTag("detail_title").assertTextEquals(removed.title)
        compose.onNodeWithTag("reader_locate").assertDoesNotExist()
        compose.onNodeWithTag("back_button").assertContentDescriptionEquals("返回收藏").performClick()
        compose.onNodeWithTag("nav_favorites").assertIsSelected()
        compose.onNodeWithTag("document_${removed.id}").assertIsDisplayed()
    }

    private fun showApp(restoration: StateRestorationTester? = null, initialQuery: String = "", hits: List<SearchHit> = emptyList()) {
        val state = mutableStateOf(AppUiState(
            documents = documents,
            query = initialQuery,
            hits = hits,
            searchPhase = if (hits.isEmpty()) UiSearchPhase.EMPTY else UiSearchPhase.REFINED,
            favorites = listOf(removed),
            loading = false,
        ))
        val actions = AppActions(
            onQueryChange = { query, _ -> state.value = state.value.copy(query = query) },
            onSubmitSearch = {}, onOpenResult = {}, onToggleFavorite = {}, onDeleteHistory = {},
            onClearHistory = {}, onHistoryEnabled = {}, onUpdate = {},
        )
        if (restoration == null) compose.setContent { LifeEvidenceTheme { LifeEvidenceApp(state.value, actions) } }
        else restoration.setContent { LifeEvidenceTheme { LifeEvidenceApp(state.value, actions) } }
    }

    private fun openDirectory() = compose.onNodeWithTag("nav_directory").performClick()

    private fun chapter(document: ContentDocument) = compose.onNode(
        tagStartingWith("directory_chapter_") and hasText(document.chapter)
    )

    private fun scrollToDocument(document: ContentDocument) {
        compose.onNodeWithTag("directory_list").performScrollToNode(hasTestTag("document_${document.id}"))
        compose.onNodeWithTag("document_${document.id}").assertIsDisplayed()
    }

    private fun scrollPosition(): Float = compose.onNodeWithTag("directory_list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun stateDescription(description: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

    private fun tagStartingWith(prefix: String) = SemanticsMatcher("testTag starts with $prefix") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }

    private fun document(id: String, path: String, kind: String, chapter: String, title: String) = ContentDocument(
        id, path, kind, chapter, title, "测试摘要", "# $title\n\n测试正文，仅用于界面回归。",
        "https://example.com/$path", "hash-$id",
    )
}
