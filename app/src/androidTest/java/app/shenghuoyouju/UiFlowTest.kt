@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.shenghuoyouju

import android.app.UiModeManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.lifecycle.ViewModelProvider
import app.shenghuoyouju.ui.UiSearchPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real release Activity, local database and bundled search index. */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun prepareActivity() {
        awaitOfflineCorpus()
        screenshot("01-home")
    }

    @Test
    fun searchReadBookmarkAndReturn() {
        setHistoryEnabled(true)
        search("离职了，社保怎么办")
        recordSearchDiagnostics("social-before-screenshot")
        screenshot("02-social-insurance-results")
        recordSearchDiagnostics("social-after-screenshot")
        val firstHit = ViewModelProvider(compose.activity)[MainViewModel::class.java].uiState.value.hits.first()
        compose.onNodeWithTag("result_card_${firstHit.document.id}").assertIsDisplayed()
        assertEquals("新查询和IME收起后应展示结果顶部", 0f, scrollPosition("result_list"), .001f)
        compose.onNodeWithTag("result_card_${firstHit.document.id}").performClick()
        compose.onNodeWithTag("reader_body").assertExists().performScrollToIndex(0)
        compose.onNodeWithTag("detail_title").assertIsDisplayed()
        screenshot("03-original-reading")
        if (compose.onAllNodesWithContentDescription("收藏原文").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("bookmark_toggle").performClick()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("取消收藏").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("back_button").performClick()
        compose.onNodeWithTag("search_input").assertTextContains("离职了，社保怎么办")
        compose.onNodeWithTag("nav_favorites").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(tagStartingWith("document_")).fetchSemanticsNodes().isNotEmpty() }
        screenshot("04-favorites")
        compose.onAllNodes(tagStartingWith("document_")).onFirst().performClick()
        compose.onNodeWithTag("reader_body").assertExists()
        compose.onNodeWithTag("back_button").performClick()
        compose.onNodeWithTag("nav_search").performClick()
        compose.onNodeWithTag("search_input").assertTextContains("离职了，社保怎么办")
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("离职了，社保怎么办").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun rapidQueryChangesDiscardOldAnswersAndAllowEmpty() {
        compose.onNodeWithTag("search_input").performClick().performTextReplacement("社保")
        compose.onNodeWithTag("search_input").performTextReplacement("感冒")
        compose.onNodeWithTag("search_input").performTextReplacement("钓鱼的技巧有哪些")
        compose.onNodeWithTag("search_input").performImeAction()
        compose.waitUntil(90_000) { compose.onAllNodesWithTag("search_empty").fetchSemanticsNodes().isNotEmpty() && !understanding() }
        compose.onNodeWithTag("search_empty").assertExists()
        screenshot("05-out-of-corpus")
        assertTrue(compose.onAllNodes(tagStartingWith("result_card_")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.onNodeWithText("你遇到了\n什么问题？").assertIsDisplayed()
        assertEquals("", compose.onNodeWithTag("search_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun directoryNavigationAndHistoryPreferences() {
        compose.onNodeWithTag("nav_directory").performClick()
        compose.onNodeWithTag("directory_list").assertExists()
        compose.onAllNodes(tagStartingWith("directory_chapter_")).onFirst().performClick()
        compose.onAllNodes(tagStartingWith("document_")).onFirst().performClick()
        compose.onNodeWithTag("reader_body").assertExists()
        compose.onNodeWithTag("back_button").performClick()
        compose.onNodeWithTag("directory_list").assertExists()
        compose.onNodeWithTag("nav_search").performClick()

        setHistoryEnabled(true)
        search("社保")
        compose.onNodeWithTag("settings_button").performClick()
        compose.onNodeWithTag("clear_history").performClick()
        compose.onNodeWithText("清空", useUnmergedTree = true).performClick()
        compose.onNodeWithText("完成").performClick()
        setHistoryEnabled(false)
        search("感冒")
        compose.onNodeWithContentDescription("清空搜索").performClick()
        assertTrue(compose.onAllNodesWithText("最近查过").fetchSemanticsNodes().isEmpty())

        compose.activityRule.scenario.recreate()
        awaitOfflineCorpus()
        compose.onNodeWithTag("settings_button").performClick()
        compose.onNodeWithTag("history_switch").assertIsOff()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("clear_history") and !isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("clear_history").assertIsNotEnabled()
        compose.onNodeWithTag("history_switch").performClick()
        compose.onNodeWithText("完成").performClick()
    }

    @Test
    fun largeFontAndDarkThemeKeepPrimaryActionsReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previousScale = shell("settings get system font_scale").trim().takeIf { it.toFloatOrNull() != null } ?: "1.0"
        val manager = instrumentation.targetContext.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        val previousNight = manager.nightMode
        try {
            shell("settings put system font_scale 1.3")
            shell("cmd uimode night yes")
            compose.activityRule.scenario.recreate()
            awaitOfflineCorpus()
            compose.onNodeWithTag("search_input").assertIsDisplayed()
            screenshot("06-dark-large-font")
            compose.onNodeWithTag("nav_directory").assertIsDisplayed().performClick()
            compose.onNodeWithTag("directory_list").assertIsDisplayed()
            compose.onAllNodes(tagStartingWith("directory_chapter_")).onFirst().performClick()
            compose.onAllNodes(tagStartingWith("document_")).onFirst().assertIsDisplayed()
            compose.onNodeWithTag("nav_favorites").assertIsDisplayed().performClick()
            compose.onNodeWithTag("settings_button").assertIsDisplayed().performClick()
            compose.onNodeWithTag("update_button").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("完成").performClick()
            compose.onNodeWithTag("nav_search").performClick()
            compose.onNodeWithTag("search_input").assertIsDisplayed()
        } finally {
            shell("settings put system font_scale $previousScale")
            shell("cmd uimode night ${when (previousNight) { UiModeManager.MODE_NIGHT_YES -> "yes"; UiModeManager.MODE_NIGHT_NO -> "no"; else -> "auto" }}")
            compose.activityRule.scenario.recreate()
        }
    }

    @Test
    fun movingCursorDoesNotRestartCompletedSearch() {
        search("离职了，社保怎么办")
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val phases = CopyOnWriteArrayList<UiSearchPhase>()
        val observation = CoroutineScope(Dispatchers.Unconfined).launch {
            model.uiState.collect { phases += it.searchPhase }
        }
        try {
            compose.onNodeWithTag("search_input").performClick().performTextInputSelection(TextRange(2))
            compose.onNodeWithTag("search_input").performTextInputSelection(TextRange(0, 3))
            compose.waitForIdle()
            assertTrue("光标移动不应重新进入关键词或语义检索", UiSearchPhase.LEXICAL !in phases)
            assertEquals(UiSearchPhase.REFINED, model.uiState.value.searchPhase)
            assertTrue(!model.uiState.value.semanticRunning)
        } finally { observation.cancel() }
    }

    @Test
    fun composingSpanDefersSemanticsUntilImeCommits() {
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.onNodeWithTag("search_input").performClick().performTextClearance()
        // Compose 1.7.5 has no public performTextInputCommand; use the same InputConnection as IMEs.
        val connection = compose.runOnIdle {
            requireNotNull(compose.activity.currentFocus?.onCreateInputConnection(EditorInfo())) {
                "搜索框必须提供真实的输入法连接"
            }
        }
        val started = SystemClock.elapsedRealtime()
        compose.runOnIdle { assertTrue(connection.setComposingText("社保", 1)) }
        compose.waitUntil(10_000) {
            val state = model.uiState.value
            SystemClock.elapsedRealtime() - started >= 450 && state.query == "社保" &&
                state.hits.isNotEmpty() && state.searchPhase == UiSearchPhase.LEXICAL && !state.semanticRunning
        }
        compose.onNodeWithText("输入完成后，继续查找相关原文").assertIsDisplayed()
        compose.runOnIdle { assertTrue(connection.finishComposingText()) }
        compose.waitUntil(90_000) {
            val state = model.uiState.value
            state.searchPhase == UiSearchPhase.REFINED && !state.semanticRunning &&
                state.hits.any { it.document.title.contains("社保") }
        }
        compose.onNodeWithTag("search_input").assertTextContains("社保")
        compose.onNodeWithTag("search_input").performImeAction()
        compose.onAllNodes(tagStartingWith("result_card_")).onFirst().assertIsDisplayed()
    }

    @Test
    fun readingAndSearchPositionSurviveActivityRecreation() {
        search("社保")
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val index = minOf(2, model.uiState.value.hits.lastIndex)
        val selected = model.uiState.value.hits[index]
        compose.onNodeWithTag("result_list").performScrollToNode(hasTestTag("result_card_${selected.document.id}"))
        val searchPosition = scrollPosition("result_list")
        compose.onNodeWithTag("result_card_${selected.document.id}").performClick()
        compose.onNodeWithTag("reader_body").performScrollToIndex(2)
        val readerPosition = scrollPosition("reader_body")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(90_000) { compose.onAllNodesWithTag("reader_body").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("重建后保留原文滚动位置", readerPosition.toDouble(), scrollPosition("reader_body").toDouble(), .1)
        compose.onNodeWithTag("reader_body").performScrollToIndex(0)
        compose.onNodeWithTag("detail_title").assertTextEquals(selected.document.title)
        compose.onNodeWithTag("back_button").performClick()
        compose.onNodeWithTag("search_input").assertTextContains("社保")
        assertEquals("返回后保留结果滚动位置", searchPosition.toDouble(), scrollPosition("result_list").toDouble(), .1)
    }

    private fun search(query: String) {
        compose.onNodeWithTag("search_input").performClick().performTextReplacement(query)
        compose.onNodeWithTag("search_input").performImeAction()
        compose.waitUntil(90_000) {
            compose.onAllNodes(tagStartingWith("result_card_")).fetchSemanticsNodes().isNotEmpty() && !understanding()
        }
        compose.onNodeWithTag("search_input").assertTextContains(query)
        recordSearchDiagnostics("after-refinement")
    }

    private fun awaitOfflineCorpus() {
        compose.waitUntil(90_000) { compose.onAllNodesWithTag("search_input").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun setHistoryEnabled(enabled: Boolean) {
        compose.onNodeWithTag("settings_button").performClick()
        val node = compose.onNodeWithTag("history_switch")
        val checked = node.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On
        if (checked != enabled) node.performClick()
        compose.onNodeWithText("完成").performClick()
    }

    private fun understanding(): Boolean = compose.onAllNodes(
        hasText("正在理解这个问题…") or hasText("先看看这些，正在整理更多相关原文…")
    ).fetchSemanticsNodes().isNotEmpty()

    private fun tagStartingWith(prefix: String) = SemanticsMatcher("testTag starts with $prefix") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }

    private fun scrollPosition(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "test-results/screenshots")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建截图目录" }
        val device = UiDevice.getInstance(instrumentation)
        device.waitForIdle()
        assertTrue("保存真实 Activity 截图失败：$name", device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun recordSearchDiagnostics(stage: String) {
        compose.waitForIdle()
        val state = ViewModelProvider(compose.activity)[MainViewModel::class.java].uiState.value
        val list = compose.onAllNodesWithTag("result_list").fetchSemanticsNodes().firstOrNull()
        val cards = compose.onAllNodes(tagStartingWith("result_card_")).fetchSemanticsNodes()
        val imeBottom = compose.runOnIdle {
            compose.activity.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: -1
        }
        val record = JSONObject()
            .put("stage", stage)
            .put("elapsedMs", SystemClock.elapsedRealtime())
            .put("query", state.query)
            .put("phase", state.searchPhase.name)
            .put("semanticRunning", state.semanticRunning)
            .put("imeBottom", imeBottom)
            .put("scroll", runCatching { list!!.config[SemanticsProperties.VerticalScrollAxisRange].value() }.getOrDefault(-1f))
            .put("listBounds", list?.boundsInRoot?.toString())
            .put("pending", compose.onAllNodesWithTag("apply_refined_results").fetchSemanticsNodes().isNotEmpty())
            .put("rankedIds", JSONArray(state.hits.map { it.document.id }))
            .put("cards", JSONArray().apply {
                cards.forEach { node ->
                    put(JSONObject().put("tag", node.config.getOrElse(SemanticsProperties.TestTag) { "" })
                        .put("bounds", node.boundsInRoot.toString()))
                }
            })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "test-results/screenshots")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "search-position.jsonl").appendText(record.toString() + "\n")
        android.util.Log.i("LifeSearchPosition", record.toString())
    }
}
