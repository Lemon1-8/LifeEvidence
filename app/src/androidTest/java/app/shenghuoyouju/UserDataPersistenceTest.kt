package app.shenghuoyouju

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.shenghuoyouju.data.ContentDocument
import app.shenghuoyouju.data.UserDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run seed and verify in separate am instrument invocations, with adb force-stop in between. */
@RunWith(AndroidJUnit4::class)
class UserDataPersistenceTest {
    @Test fun seedPersistentUserData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = fixtureFile(context)
        if (file.exists()) assertNotEquals("尚有待验证的 seed；先执行 verify 或检查既有报告", "seeded", JSONObject(file.readText()).optString("status"))
        val store = ViewModelStore()
        val viewModel = withContext(Dispatchers.Main) {
            MainViewModel(context.applicationContext as Application).also { store.put("persistence", it) }
        }
        val dao = UserDatabase.get(context).userDao()
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val originalHistoryEnabled = preferences.getBoolean("historyEnabled", true)
        val createdQueries = mutableListOf<String>()
        val createdFavorites = mutableListOf<String>()
        var seeded = false
        try {
            val loaded = withTimeout(90_000) { viewModel.uiState.first { !it.loading && it.documents.isNotEmpty() } }
            val existingHistory = dao.history().first()
            val existingFavorites = dao.favorites().first()
            assertTrue("保留原有记录：历史已满50条时不插入测试记录", existingHistory.size < 50)
            val reusableQuery = existingHistory.firstOrNull { it.query == "离职了，社保怎么办" }?.query
                ?: existingHistory.firstOrNull()?.query
            // One owned probe also verifies deletion without touching any existing UI-test query.
            val probeQuery = "持久化验收 社保 ${System.currentTimeMillis()} ${Process.myPid()}"
            createdQueries += probeQuery
            withContext(Dispatchers.Main) {
                viewModel.actions.onHistoryEnabled(true)
                viewModel.actions.onSubmitSearch(probeQuery)
            }
            withTimeout(15_000) { dao.history().first { rows -> rows.any { it.query == probeQuery } } }
            val selectedQuery = reusableQuery ?: probeQuery
            val selectedHistory = dao.history().first().first { it.query == selectedQuery }
            val reusableFavorite = existingFavorites.firstOrNull { row ->
                runCatching { viewModel.repository.json.decodeFromString<ContentDocument>(row.documentJson) }.isSuccess
            }
            val favoriteId = if (reusableFavorite != null) reusableFavorite.id else {
                val existingIds = existingFavorites.map { it.id }.toSet()
                val document = loaded.documents.first { it.id !in existingIds }
                createdFavorites += document.id
                withContext(Dispatchers.Main) { viewModel.actions.onToggleFavorite(document) }
                withTimeout(15_000) { dao.favorites().first { rows -> rows.any { it.id == document.id } } }
                document.id
            }
            val favorite = dao.favorites().first().first { it.id == favoriteId }
            withTimeout(15_000) { viewModel.uiState.first { selectedQuery in it.history && it.favorites.any { doc -> doc.id == favoriteId } } }
            val fixture = JSONObject().put("status", "seeded").put("seedPid", Process.myPid())
                .put("seedProcessStartedMs", Process.getStartElapsedRealtime()).put("query", selectedQuery)
                .put("queryUsedAt", selectedHistory.usedAt).put("deletionProbeQuery", probeQuery)
                .put("favoriteId", favoriteId).put("favoriteSnapshotJson", favorite.documentJson)
                .put("favoriteSourceCommit", favorite.sourceCommit).put("favoriteSavedAt", favorite.savedAt)
                .put("reusedExistingHistory", reusableQuery != null).put("reusedExistingFavorite", reusableFavorite != null)
                .put("createdQueries", JSONArray(createdQueries)).put("createdFavoriteIds", JSONArray(createdFavorites))
                .put("originalQueries", JSONArray(existingHistory.map { it.query }))
                .put("originalFavoriteIds", JSONArray(existingFavorites.map { it.id }))
                .put("originalHistoryEnabled", originalHistoryEnabled)
            file.writeText(fixture.toString(2))
            Log.i("LifeEvidencePersistence", fixture.toString())
            seeded = true
        } finally {
            if (!seeded) {
                createdQueries.forEach { dao.deleteHistory(it) }
                createdFavorites.forEach { dao.deleteFavorite(it) }
            }
            withContext(Dispatchers.Main) { viewModel.actions.onHistoryEnabled(originalHistoryEnabled) }
            preferences.edit().putBoolean("historyEnabled", originalHistoryEnabled).commit()
            withContext(Dispatchers.Main) { store.clear() }
        }
        Unit
    }

    @Test fun verifyPersistentUserDataAndCleanup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = fixtureFile(context)
        assertTrue("先单独运行 seedPersistentUserData，然后 force-stop App", file.exists())
        val fixture = JSONObject(file.readText())
        assertEquals("seeded", fixture.getString("status"))
        val store = ViewModelStore()
        val dao = UserDatabase.get(context).userDao()
        val createdQueries = strings(fixture.getJSONArray("createdQueries"))
        val createdFavorites = strings(fixture.getJSONArray("createdFavoriteIds"))
        var verified = false
        try {
            assertNotEquals("必须在 force-stop 后的新进程验证", fixture.getInt("seedPid"), Process.myPid())
            assertTrue(Process.getStartElapsedRealtime() > fixture.getLong("seedProcessStartedMs"))
            val viewModel = withContext(Dispatchers.Main) {
                MainViewModel(context.applicationContext as Application).also { store.put("persistence", it) }
            }
            val query = fixture.getString("query")
            val favoriteId = fixture.getString("favoriteId")
            val state = withTimeout(90_000) {
                viewModel.uiState.first { !it.loading && query in it.history && it.favorites.any { doc -> doc.id == favoriteId } }
            }
            val expectedDocument = viewModel.repository.json.decodeFromString<ContentDocument>(fixture.getString("favoriteSnapshotJson"))
            assertEquals("新 ViewModel 必须恢复完整收藏快照", expectedDocument, state.favorites.first { it.id == favoriteId })
            assertEquals(fixture.getBoolean("originalHistoryEnabled"), state.historyEnabled)
            val storedFavorite = dao.favorites().first().first { it.id == favoriteId }
            assertEquals(fixture.getString("favoriteSnapshotJson"), storedFavorite.documentJson)
            assertEquals(fixture.getString("favoriteSourceCommit"), storedFavorite.sourceCommit)
            assertEquals(fixture.getLong("favoriteSavedAt"), storedFavorite.savedAt)
            assertEquals(fixture.getLong("queryUsedAt"), dao.history().first().first { it.query == query }.usedAt)

            val probe = fixture.getString("deletionProbeQuery")
            assertTrue(probe in state.history)
            withContext(Dispatchers.Main) { viewModel.actions.onDeleteHistory(probe) }
            withTimeout(15_000) { dao.history().first { rows -> rows.none { it.query == probe } } }
            withTimeout(15_000) { viewModel.uiState.first { probe !in it.history } }
            for (id in createdFavorites) {
                val document = viewModel.uiState.value.favorites.first { it.id == id }
                withContext(Dispatchers.Main) { viewModel.actions.onToggleFavorite(document) }
                withTimeout(15_000) { dao.favorites().first { rows -> rows.none { it.id == id } } }
            }
            val remainingQueries = dao.history().first().map { it.query }.toSet()
            val remainingFavorites = dao.favorites().first().map { it.id }.toSet()
            assertTrue("原有 UI 历史不能被测试清理", remainingQueries.containsAll(strings(fixture.getJSONArray("originalQueries"))))
            assertTrue("原有 UI 收藏不能被测试清理", remainingFavorites.containsAll(strings(fixture.getJSONArray("originalFavoriteIds"))))
            fixture.put("verifyPid", Process.myPid()).put("verifyProcessStartedMs", Process.getStartElapsedRealtime())
                .put("newProcessVerified", true).put("completeFavoriteSnapshotRestored", true)
                .put("singleHistoryDeleteActionVerified", true).put("originalUserDataPreserved", true)
            verified = true
        } catch (failure: Throwable) {
            fixture.put("failure", failure.toString())
            throw failure
        } finally {
            // Cleanup is restricted to IDs created by seed, even when an assertion fails.
            createdQueries.forEach { dao.deleteHistory(it) }
            createdFavorites.forEach { dao.deleteFavorite(it) }
            withContext(Dispatchers.Main) { store.clear() }
            fixture.put("status", if (verified) "verified" else "failed").put("ownedTestDataCleaned", true)
            file.writeText(fixture.toString(2))
            Log.i("LifeEvidencePersistence", fixture.toString())
        }
        Unit
    }

    private fun fixtureFile(context: Context): File =
        File(File(requireNotNull(context.getExternalFilesDir(null)), "test-results").apply { mkdirs() }, "user-data-persistence.json")
    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
}
