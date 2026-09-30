package app.shenghuoyouju

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shenghuoyouju.data.*
import app.shenghuoyouju.search.*
import app.shenghuoyouju.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val model = EmbeddingModel(application)
    val repository = ContentRepository(application, model)
    val searchEngine = SearchEngine(repository, model)
    private val dao = UserDatabase.get(application).userDao()
    private val preferences = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(AppUiState(historyEnabled = preferences.getBoolean("historyEnabled", true)))
    val uiState = mutableState.asStateFlow()
    private var queryJob: Job? = null
    private var foregroundUpdateJob: Job? = null
    private var lastQueryRequest: Pair<String, Boolean>? = null
    private var queryGeneration = 0L
    private var historyGeneration = 0L
    private val historyLock = Mutex()
    private val favoriteLock = Mutex()

    val actions = AppActions(
        onQueryChange = ::changeQuery,
        onSubmitSearch = { saveHistory(it) },
        onOpenResult = { saveHistory(uiState.value.query) },
        onToggleFavorite = ::toggleFavorite,
        onDeleteHistory = { query -> viewModelScope.launch { historyLock.withLock { dao.deleteHistory(query) } } },
        onClearHistory = {
            historyGeneration++
            viewModelScope.launch { historyLock.withLock { dao.clearHistory() } }
        },
        onHistoryEnabled = { enabled ->
            historyGeneration++
            preferences.edit().putBoolean("historyEnabled", enabled).apply()
            mutableState.update { it.copy(historyEnabled = enabled) }
        },
        onUpdate = { viewModelScope.launch { repository.update(manual = true) } },
    )

    init {
        viewModelScope.launch {
            try {
                val corpus = repository.load()
                mutableState.update { it.copy(documents = corpus.corpus.documents, version = corpus.corpus.sourceCommit.take(7), loading = false) }
            } catch (failure: Exception) {
                mutableState.update { it.copy(loading = false, error = "离线内容未能加载：${failure.message}") }
            }
        }
        viewModelScope.launch { repository.current.filterNotNull().collect { snapshot -> mutableState.update { it.copy(documents = snapshot.corpus.documents, version = snapshot.corpus.sourceCommit.take(7)) } } }
        viewModelScope.launch { repository.updateStatus.collect { message -> mutableState.update { it.copy(updateStatus = message) } } }
        viewModelScope.launch { repository.updating.collect { busy -> mutableState.update { it.copy(updating = busy) } } }
        viewModelScope.launch { dao.history().collect { rows -> mutableState.update { it.copy(history = rows.map(HistoryRow::query)) } } }
        viewModelScope.launch { dao.favorites().collect { rows ->
            val documents = rows.mapNotNull { row -> runCatching { repository.json.decodeFromString<ContentDocument>(row.documentJson) }.getOrNull() }
            mutableState.update { it.copy(favorites = documents) }
        } }
    }

    fun changeQuery(query: String, composing: Boolean) {
        val text = query.take(500)
        val request = text to composing
        if (request == lastQueryRequest) return
        lastQueryRequest = request
        repository.prioritizeSearch()
        val generation = ++queryGeneration
        queryJob?.cancel()
        mutableState.update { it.copy(query = text, hits = if (it.query == text) it.hits else emptyList(), semanticRunning = text.isNotBlank() && !composing, searchPhase = UiSearchPhase.LEXICAL, error = null) }
        if (text.isBlank()) {
            mutableState.update { it.copy(hits = emptyList(), searchPhase = UiSearchPhase.EMPTY, semanticRunning = false) }
            return
        }
        queryJob = viewModelScope.launch {
            try {
                delay(150)
                if (repository.current.value == null) return@launch
                searchEngine.search(text, semantic = !composing).collect { result ->
                    if (generation == queryGeneration) mutableState.update {
                        it.copy(hits = result.hits, searchPhase = UiSearchPhase.valueOf(result.phase.name), semanticRunning = result.phase == SearchPhase.LEXICAL && !composing)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == queryGeneration) mutableState.update { it.copy(error = "语义搜索暂不可用，已保留关键词结果：${failure.message?.take(90)}") }
            } finally {
                if (generation == queryGeneration) mutableState.update { it.copy(semanticRunning = false) }
            }
        }
    }

    /** Every foreground entry can check connectivity; the repository owns the daily attempt gate. */
    fun onForeground() {
        if (foregroundUpdateJob?.isActive == true) return
        foregroundUpdateJob = viewModelScope.launch {
            uiState.first { !it.loading }
            if (repository.current.value != null) repository.update()
        }
    }

    override fun onCleared() {
        queryJob?.cancel()
        foregroundUpdateJob?.cancel()
        super.onCleared()
        // ORT may still be finishing a native call. Its mutex-protected close must not block UI.
        Thread({ runCatching { model.close() } }, "life-evidence-model-close").apply {
            isDaemon = true
            start()
        }
    }

    private fun saveHistory(query: String) {
        val normalized = query.trim().replace(Regex("\\s+"), " ").take(500)
        if (normalized.isEmpty() || !uiState.value.historyEnabled) return
        val generation = historyGeneration
        viewModelScope.launch {
            historyLock.withLock {
                if (generation != historyGeneration || !uiState.value.historyEnabled) return@withLock
                dao.saveHistory(HistoryRow(normalized, System.currentTimeMillis()))
                dao.trimHistory()
            }
        }
    }

    private fun toggleFavorite(document: ContentDocument) {
        viewModelScope.launch {
            favoriteLock.withLock {
                val existing = dao.favorites().first().any { it.id == document.id }
                if (existing) dao.deleteFavorite(document.id)
                else dao.saveFavorite(FavoriteRow(document.id, repository.json.encodeToString(document), document.sourceUrl.substringAfter("/blob/").substringBefore('/'), System.currentTimeMillis()))
            }
        }
    }
}
