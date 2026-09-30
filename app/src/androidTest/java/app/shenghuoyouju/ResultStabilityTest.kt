package app.shenghuoyouju

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.shenghuoyouju.data.ContentDocument
import app.shenghuoyouju.data.SearchHit
import app.shenghuoyouju.ui.*
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic presentation checks for delayed refinement; inference is tested separately. */
@RunWith(AndroidJUnit4::class)
class ResultStabilityTest {
    @get:Rule val compose = createComposeRule()

    private val documents = (0 until 20).map { index ->
        ContentDocument("sample$index", "example.md", "entry", "测试资料", "社保相关条目 $index", "测试摘录 $index", "测试全文 $index", "https://example.com", "hash$index")
    }
    private val hits = documents.map { SearchHit(it, it.intro, 0, 1f) }
    private val actions = AppActions({ _, _ -> }, {}, {}, {}, {}, {}, {}, {})

    @Test
    fun scrolledResultsWaitForExplicitRefinement() {
        val state = mutableStateOf(AppUiState(documents = documents, query = "社保", hits = hits, searchPhase = UiSearchPhase.LEXICAL, loading = false))
        compose.setContent { LifeEvidenceTheme { LifeEvidenceApp(state.value, actions) } }
        compose.onNodeWithTag("result_list").performScrollToIndex(5)
        compose.onNodeWithTag("result_card_sample5").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(hits = hits.reversed(), searchPhase = UiSearchPhase.REFINED) }
        compose.onNodeWithTag("apply_refined_results").assertIsDisplayed()
        compose.onNodeWithTag("result_card_sample5").assertIsDisplayed()
        compose.onNodeWithTag("apply_refined_results").performClick()
        compose.onNodeWithTag("apply_refined_results").assertDoesNotExist()
    }

    @Test
    fun emptyRefinementDoesNotRemoveScrolledClickTargets() {
        val state = mutableStateOf(AppUiState(documents = documents, query = "社保", hits = hits, searchPhase = UiSearchPhase.LEXICAL, loading = false))
        compose.setContent { LifeEvidenceTheme { LifeEvidenceApp(state.value, actions) } }
        compose.onNodeWithTag("result_list").performScrollToIndex(5)
        compose.runOnIdle { state.value = state.value.copy(hits = emptyList(), searchPhase = UiSearchPhase.EMPTY) }
        compose.onNodeWithTag("result_card_sample5").assertIsDisplayed()
        compose.onNodeWithTag("search_empty").assertDoesNotExist()
        compose.onNodeWithTag("apply_refined_results").performClick()
        compose.onNodeWithTag("search_empty").assertExists()
        compose.onNodeWithTag("result_card_sample5").assertDoesNotExist()
    }

    @Test
    fun automaticRefinementAtTopShowsTheNewFirstResult() {
        val state = mutableStateOf(AppUiState(documents = documents, query = "社保", hits = hits, searchPhase = UiSearchPhase.LEXICAL, loading = false))
        compose.setContent { LifeEvidenceTheme { LifeEvidenceApp(state.value, actions) } }
        compose.onNodeWithTag("result_card_sample0").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(hits = hits.reversed(), searchPhase = UiSearchPhase.REFINED) }
        compose.onNodeWithTag("result_card_sample19").assertIsDisplayed()
        compose.onNodeWithTag("apply_refined_results").assertDoesNotExist()
        assertEquals(0f, compose.onNodeWithTag("result_list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
    }

    @Test
    fun newQueryResetsOldAnchorWhenItsFirstResultsArrive() {
        val state = mutableStateOf(AppUiState(documents = documents, query = "社保", hits = hits, searchPhase = UiSearchPhase.REFINED, loading = false))
        compose.setContent { LifeEvidenceTheme { LifeEvidenceApp(state.value, actions) } }
        compose.onNodeWithTag("result_list").performScrollToIndex(5)
        compose.runOnIdle { state.value = state.value.copy(query = "感冒", hits = emptyList(), searchPhase = UiSearchPhase.LEXICAL, semanticRunning = true) }
        compose.waitForIdle()
        compose.runOnIdle { state.value = state.value.copy(hits = hits.reversed(), semanticRunning = false) }
        compose.onNodeWithTag("result_card_sample19").assertIsDisplayed()
        assertEquals(0f, compose.onNodeWithTag("result_list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
        compose.runOnIdle { state.value = state.value.copy(hits = hits, searchPhase = UiSearchPhase.REFINED) }
        compose.onNodeWithTag("result_card_sample0").assertIsDisplayed()
        compose.onNodeWithTag("apply_refined_results").assertDoesNotExist()
    }
}
