@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.shenghuoyouju.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shenghuoyouju.data.ContentDocument
import app.shenghuoyouju.data.SearchHit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class UiSearchPhase { LEXICAL, REFINED, EMPTY }

data class AppUiState(
    val documents: List<ContentDocument> = emptyList(),
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val searchPhase: UiSearchPhase = UiSearchPhase.EMPTY,
    val semanticRunning: Boolean = false,
    val favorites: List<ContentDocument> = emptyList(),
    val history: List<String> = emptyList(),
    val historyEnabled: Boolean = true,
    val loading: Boolean = true,
    val version: String = "",
    val updateStatus: String = "内容已保存在本机，随时可离线阅读",
    val updating: Boolean = false,
    val error: String? = null,
)

data class AppActions(
    val onQueryChange: (String, Boolean) -> Unit,
    val onSubmitSearch: (String) -> Unit,
    val onOpenResult: (SearchHit) -> Unit,
    val onToggleFavorite: (ContentDocument) -> Unit,
    val onDeleteHistory: (String) -> Unit,
    val onClearHistory: () -> Unit,
    val onHistoryEnabled: (Boolean) -> Unit,
    val onUpdate: () -> Unit,
)

private enum class Destination(val label: String, val icon: ImageVector, val tag: String) {
    SEARCH("搜索", Icons.Outlined.Search, "nav_search"),
    DIRECTORY("目录", Icons.Outlined.LibraryBooks, "nav_directory"),
    FAVORITES("收藏", Icons.Outlined.BookmarkBorder, "nav_favorites"),
}

@Serializable
private data class SavedSearchHit(val document: ContentDocument, val snippet: String, val offset: Int, val score: Float, val relatedOnly: Boolean) {
    fun hit() = SearchHit(document, snippet, offset, score, relatedOnly)
}

private fun SearchHit.saved() = SavedSearchHit(document, snippet, offset, score, relatedOnly)
private val ReadingSaver = Saver<SearchHit?, String>(
    save = { hit -> hit?.let { Json.encodeToString(it.saved()) } ?: "" },
    restore = { value -> runCatching { Json.decodeFromString<SavedSearchHit>(value).hit() }.getOrNull() },
)
private val ResultsSaver = Saver<List<SearchHit>, String>(
    save = { hits -> Json.encodeToString(hits.map { it.saved() }) },
    restore = { value -> runCatching { Json.decodeFromString<List<SavedSearchHit>>(value).map { it.hit() } }.getOrDefault(emptyList()) },
)
private val PendingResultsSaver = Saver<List<SearchHit>?, String>(
    save = { hits -> hits?.let { Json.encodeToString(it.map { hit -> hit.saved() }) } ?: "" },
    restore = { value -> runCatching { Json.decodeFromString<List<SavedSearchHit>>(value).map { it.hit() } }.getOrNull() },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeEvidenceApp(state: AppUiState, actions: AppActions) {
    var destination by rememberSaveable { mutableStateOf(Destination.SEARCH) }
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(state.query)) }
    var reader by rememberSaveable(stateSaver = ReadingSaver) { mutableStateOf<SearchHit?>(null) }
    var directoryKind by rememberSaveable { mutableStateOf("") }
    var directoryQuery by rememberSaveable { mutableStateOf("") }
    val chapterListSaver = listSaver<List<String>, String>(save = { it }, restore = { it })
    var expandedChapters by rememberSaveable(stateSaver = chapterListSaver) { mutableStateOf(emptyList<String>()) }
    var collapsedMatches by rememberSaveable(stateSaver = chapterListSaver) { mutableStateOf(emptyList<String>()) }
    var directoryRevealId by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    // A new query owns a new scroll anchor, even if its first results have not arrived yet.
    val searchScroll = rememberSaveable(state.query, saver = LazyListState.Saver) { LazyListState() }
    val directoryScroll = rememberLazyListState()
    val favoriteScroll = rememberLazyListState()
    val focus = LocalFocusManager.current
    var displayedHits by rememberSaveable(stateSaver = ResultsSaver) { mutableStateOf(state.hits) }
    var displayedQuery by rememberSaveable { mutableStateOf(state.query) }
    var pendingHits by rememberSaveable(stateSaver = PendingResultsSaver) { mutableStateOf<List<SearchHit>?>(null) }
    var rankingPresentation by rememberSaveable { mutableIntStateOf(0) }
    var touchingResults by remember { mutableStateOf(false) }
    val interactionBusy = searchScroll.isScrollInProgress || searchScroll.firstVisibleItemIndex > 0 ||
        searchScroll.firstVisibleItemScrollOffset > 0 || touchingResults || reader != null

    LaunchedEffect(state.query) {
        if (field.text != state.query) field = TextFieldValue(state.query, TextRange(state.query.length))
    }
    LaunchedEffect(state.query, state.hits, state.searchPhase) {
        if (state.query != displayedQuery) {
            displayedQuery = state.query
            rankingPresentation++
            displayedHits = state.hits
            pendingHits = null
            if (state.hits.isNotEmpty()) searchScroll.requestScrollToItem(0)
            return@LaunchedEffect
        }
        if (state.searchPhase != UiSearchPhase.LEXICAL && displayedHits.isNotEmpty() && state.hits != displayedHits && interactionBusy) {
            pendingHits = state.hits
        } else {
            // The scroll request can be consumed while the old data is still measured. Give the
            // accepted ranking new keys too, so a subsequent measure cannot restore its old anchor.
            // Deferred rankings keep the current keys; returning/recreating restores this version.
            if (state.hits != displayedHits && state.hits.isNotEmpty() && !interactionBusy) {
                rankingPresentation++
                searchScroll.requestScrollToItem(0)
            }
            displayedHits = state.hits
            pendingHits = null
        }
    }

    fun useQuery(query: String) {
        field = TextFieldValue(query, TextRange(query.length))
        destination = Destination.SEARCH
        actions.onQueryChange(query, false)
    }
    fun open(hit: SearchHit) {
        focus.clearFocus()
        if (destination == Destination.SEARCH) actions.onOpenResult(hit)
        reader = hit
    }
    fun locateInDirectory(document: ContentDocument) {
        val chapter = buildDirectoryTree(state.documents).firstOrNull { group -> group.documents.any { it.id == document.id } } ?: return
        directoryKind = ""
        directoryQuery = ""
        collapsedMatches = emptyList()
        if (chapter.key !in expandedChapters) expandedChapters = expandedChapters + chapter.key
        directoryRevealId = document.id
        destination = Destination.DIRECTORY
        reader = null
    }

    BackHandler(reader != null) { reader = null }
    BackHandler(reader == null && destination != Destination.SEARCH) { destination = Destination.SEARCH }
    BackHandler(reader == null && destination == Destination.SEARCH && field.text.isNotEmpty()) { useQuery("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (reader != null) {
                TopAppBar(
                    title = { Text("原文阅读", style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = { IconButton(onClick = { reader = null }, modifier = Modifier.testTag("back_button")) {
                        Icon(Icons.Outlined.ArrowBack, when (destination) {
                            Destination.DIRECTORY -> "返回目录"
                            Destination.FAVORITES -> "返回收藏"
                            Destination.SEARCH -> "返回搜索结果"
                        })
                    } },
                    actions = {
                        if (state.documents.any { it.id == reader!!.document.id }) {
                            IconButton(onClick = { locateInDirectory(reader!!.document) }, modifier = Modifier.testTag("reader_locate")) {
                                Icon(Icons.Outlined.FormatListBulleted, "在目录中定位")
                            }
                        }
                        val saved = state.favorites.any { it.id == reader!!.document.id }
                        IconButton(onClick = { actions.onToggleFavorite(reader!!.document) }, modifier = Modifier.testTag("bookmark_toggle")) {
                            Icon(if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, if (saved) "取消收藏" else "收藏原文", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(21.dp)) }
                            }
                            Text("生活有据", style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    actions = { IconButton(onClick = { settings = true; focus.clearFocus() }, modifier = Modifier.testTag("settings_button")) { Icon(Icons.Outlined.Settings, "设置与内容更新") } },
                )
            }
        },
        bottomBar = {
            if (reader == null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    Destination.entries.forEach { target ->
                        NavigationBarItem(
                            selected = target == destination,
                            onClick = { destination = target; focus.clearFocus() },
                            icon = { Icon(target.icon, null) },
                            label = { Text(target.label) },
                            modifier = Modifier.testTag(target.tag),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when {
                reader != null -> ReaderScreen(reader!!, state.documents, state.version)
                state.loading -> LoadingScreen()
                destination == Destination.SEARCH -> SearchScreen(
                    state = state,
                    field = field,
                    hits = if (displayedQuery == state.query) displayedHits else emptyList(),
                    rankingPresentation = rankingPresentation,
                    scroll = searchScroll,
                    pending = displayedQuery == state.query && pendingHits != null,
                    onApplyPending = {
                        rankingPresentation++
                        searchScroll.requestScrollToItem(0)
                        displayedHits = pendingHits.orEmpty()
                        pendingHits = null
                    },
                    onTouch = { touchingResults = it },
                    onFieldChange = { value ->
                        field = value
                        actions.onQueryChange(value.text, value.composition != null)
                    },
                    onSubmit = { actions.onSubmitSearch(field.text); focus.clearFocus() },
                    onUseQuery = ::useQuery,
                    onOpen = ::open,
                    onDeleteHistory = actions.onDeleteHistory,
                    onClearHistory = actions.onClearHistory,
                )
                destination == Destination.DIRECTORY -> DirectoryScreen(
                    documents = state.documents,
                    scroll = directoryScroll,
                    kind = directoryKind,
                    query = directoryQuery,
                    expandedChapters = expandedChapters,
                    collapsedMatches = collapsedMatches,
                    favoriteIds = state.favorites.map { it.id }.toSet(),
                    revealId = directoryRevealId,
                    onKindChange = { directoryKind = it; directoryScroll.requestScrollToItem(0) },
                    onQueryChange = { directoryQuery = it; collapsedMatches = emptyList(); directoryScroll.requestScrollToItem(0) },
                    onToggleChapter = { key ->
                        if (directoryQuery.isBlank()) {
                            expandedChapters = if (key in expandedChapters) expandedChapters - key else expandedChapters + key
                        } else {
                            collapsedMatches = if (key in collapsedMatches) collapsedMatches - key else collapsedMatches + key
                        }
                    },
                    onCollapseAll = {
                        if (directoryQuery.isBlank()) expandedChapters = emptyList()
                        else collapsedMatches = buildDirectoryTree(state.documents, directoryKind, directoryQuery).map { it.key }
                        directoryScroll.requestScrollToItem(0)
                    },
                    onRevealed = { directoryRevealId = null },
                    onOpen = ::open,
                    onFullSearch = ::useQuery,
                )
                else -> FavoritesScreen(state.favorites, state.documents, favoriteScroll, ::open)
            }
        }
    }
    if (settings) SettingsDialog(state, actions, onDismiss = { settings = false })
}

@Composable
private fun LoadingScreen() {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(22.dp))
        Text("正在打开你的生活资料库", style = MaterialTheme.typography.titleMedium)
        Text("首次准备完成后，即可离线使用", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SearchScreen(
    state: AppUiState,
    field: TextFieldValue,
    hits: List<SearchHit>,
    rankingPresentation: Int,
    scroll: LazyListState,
    pending: Boolean,
    onApplyPending: () -> Unit,
    onTouch: (Boolean) -> Unit,
    onFieldChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onUseQuery: (String) -> Unit,
    onOpen: (SearchHit) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 22.dp).padding(top = 10.dp, bottom = 10.dp)) {
            if (field.text.isBlank()) {
                Text("你遇到了\n什么问题？", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(bottom = 9.dp))
                Text("用平时说话的方式，找一份有出处的答案。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
            }
            OutlinedTextField(
                value = field,
                onValueChange = onFieldChange,
                modifier = Modifier.fillMaxWidth().testTag("search_input"),
                placeholder = { Text("例如：离职了，社保怎么办", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.primary) },
                trailingIcon = {
                    if (field.text.isNotEmpty()) IconButton(onClick = { onFieldChange(TextFieldValue("")) }) { Icon(Icons.Outlined.Close, "清空搜索") }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        }
        if (field.text.isBlank()) {
            LazyColumn(contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 5.dp)) {
                        Icon(Icons.Outlined.OfflinePin, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("随时可用 · 离线检索", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    SectionLabel("从日常出发")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TopicTile("工作与保障", "社保、劳动与权益", Icons.Outlined.WorkOutline, Modifier.weight(1f)) { onUseQuery("社保") }
                        TopicTile("身体与用药", "健康、药物与就医", Icons.Outlined.HealthAndSafety, Modifier.weight(1f)) { onUseQuery("感冒") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TopicTile("居家日常", "安全、饮食与生活", Icons.Outlined.Home, Modifier.weight(1f)) { onUseQuery("食品安全") }
                        TopicTile("数字生活", "隐私、账号与网络", Icons.Outlined.Devices, Modifier.weight(1f)) { onUseQuery("密码") }
                    }
                }
                if (state.historyEnabled && state.history.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            SectionLabel("最近查过")
                            TextButton(onClick = onClearHistory, modifier = Modifier.testTag("clear_history")) { Text("清空") }
                        }
                    }
                    items(state.history, key = { "history:$it" }) { query ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onUseQuery(query) }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Text(query, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { onDeleteHistory(query) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "删除历史：$query", modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.FormatQuote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Column {
                                Text("让生活经验，有迹可循。", style = MaterialTheme.typography.bodyMedium)
                                Text("资料来自 HowToLiveBetter，保留原文与出处。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
                if (state.error != null) item { Text(state.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 7.dp).testTag("semantic_status"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (field.composition != null) {
                    Text("输入完成后，继续查找相关原文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (state.semanticRunning) {
                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                    Text(if (hits.isEmpty()) "正在理解这个问题…" else "先看看这些，正在整理更多相关原文…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(Icons.Outlined.Article, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (hits.isEmpty()) "检索完成" else "找到 ${hits.size} 份相关原文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.error != null) Text(state.error, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            if (pending) {
                TextButton(onClick = onApplyPending, modifier = Modifier.fillMaxWidth().testTag("apply_refined_results")) {
                    Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("相关性已更新，点此查看")
                }
            }
            if (hits.isEmpty() && !state.semanticRunning && field.composition == null) {
                EmptyState(Icons.Outlined.SearchOff, "暂时没有匹配的内容", "这份资料库还没有覆盖这个问题。\n试试更具体的说法，或到目录里看看。", Modifier.testTag("search_empty"))
            } else {
                LazyColumn(
                    state = scroll,
                    modifier = Modifier.fillMaxSize().testTag("result_list").pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                onTouch(event.changes.any { it.pressed })
                            }
                        }
                    },
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 7.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(hits.filterNot { it.relatedOnly }, key = { "$rankingPresentation:${it.document.id}" }) { hit -> ResultCard(hit, field.text, onClick = { onOpen(hit) }) }
                    if (hits.any { it.relatedOnly }) {
                        item(key = "$rankingPresentation:related_background_label") {
                            Text("相关背景，供参考", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp))
                        }
                        items(hits.filter { it.relatedOnly }, key = { "$rankingPresentation:${it.document.id}" }) { hit -> ResultCard(hit, field.text, onClick = { onOpen(hit) }) }
                    }
                    if (hits.isNotEmpty()) item(key = "$rankingPresentation:result_footer") {
                        Text("摘录来自原文，请结合上下文阅读。", Modifier.fillMaxWidth().padding(vertical = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicTile(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column(Modifier.padding(15.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
            Spacer(Modifier.height(13.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun ResultCard(hit: SearchHit, query: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("result_card_${hit.document.id}"), shape = RoundedCornerShape(20.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TypeLabel(kindLabel(hit.document.kind))
                Text(hit.document.chapter, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(11.dp))
            Text(highlightPlain(hit.document.title, query, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(highlightPlain(stripMarkdown(hit.snippet), query, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (hit.relatedOnly) {
                Spacer(Modifier.height(10.dp))
                Text("仅主题相关 · 可能无法直接解答", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun TypeLabel(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f)) {
        Text(text, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium)
    }
}

internal fun kindLabel(kind: String): String = when (kind.lowercase()) {
    "article", "essay", "long", "longform" -> "长文"
    "glossary", "term", "terminology" -> "术语"
    "entry", "main", "tip" -> "条目"
    else -> kind.ifBlank { "原文" }
}

@Composable
private fun FavoritesScreen(favorites: List<ContentDocument>, current: List<ContentDocument>, scroll: LazyListState, onOpen: (SearchHit) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
            Text("留给下次的答案", style = MaterialTheme.typography.headlineMedium)
            Text("${favorites.size} 份收藏 · 原文快照保存在本机", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 7.dp))
        }
        if (favorites.isEmpty()) {
            EmptyState(Icons.Outlined.BookmarkBorder, "值得留下的，放在这里", "阅读时点一下右上角的书签，\n下次需要，就不用重新寻找。")
        } else {
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("favorites_list"), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(favorites, key = { it.id }) { doc ->
                    val live = current.find { it.id == doc.id }
                    val note = when {
                        live == null -> "旧版收藏 · 保留原文快照"
                        live.contentHash != doc.contentHash -> "原文已更新 · 当前为收藏时的版本"
                        else -> "已保存原文快照"
                    }
                    DocumentRow(doc, note, onClick = { onOpen(SearchHit(doc, doc.intro, 0, 0f)) })
                }
            }
        }
    }
}

@Composable
private fun DocumentRow(doc: ContentDocument, note: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("document_${doc.id}"), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
            }
            Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(68.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(29.dp)) }
        }
        Spacer(Modifier.height(19.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 9.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private data class ReadingBlock(val text: String, val offset: Int, val heading: Int = 0, val quoted: Boolean = false)

private fun readingBlocks(body: String): List<ReadingBlock> {
    val blocks = mutableListOf<ReadingBlock>()
    var offset = 0
    var paragraph = StringBuilder()
    var start = 0
    fun flush() {
        if (paragraph.isNotBlank()) blocks += ReadingBlock(paragraph.toString().trim(), start)
        paragraph = StringBuilder()
    }
    body.lines().forEach { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() || line.matches(Regex("[-*_]{3,}")) -> flush()
            line.startsWith("#") -> {
                flush()
                blocks += ReadingBlock(line.trimStart('#').trim(), offset, line.takeWhile { it == '#' }.length)
            }
            line.startsWith(">") -> {
                flush()
                blocks += ReadingBlock(line.removePrefix(">").trim(), offset, quoted = true)
            }
            line.matches(Regex("^([-*+] |\\d+[.)] ).*")) -> {
                flush()
                blocks += ReadingBlock(line.replace(Regex("^[-*+] "), "• "), offset)
            }
            else -> {
                if (paragraph.isEmpty()) start = offset else paragraph.append('\n')
                paragraph.append(line)
            }
        }
        offset += raw.length + 1
    }
    flush()
    return blocks
}

@Composable
private fun ReaderScreen(hit: SearchHit, current: List<ContentDocument>, version: String) {
    val doc = hit.document
    val blocks = remember(doc.contentHash) { readingBlocks(doc.body) }
    val scroll = rememberLazyListState()
    val uri = LocalUriHandler.current
    var sourceError by remember { mutableStateOf(false) }
    val documentVersion = remember(doc.sourceUrl, version) {
        doc.sourceUrl.substringAfter("/blob/", "").substringBefore('/').takeIf { it.matches(Regex("[a-f0-9]{40}")) } ?: version
    }
    val targetBlock = remember(doc.id, hit.offset) { blocks.indexOfLast { it.offset <= hit.offset }.coerceAtLeast(0) }
    var initialPositionApplied by rememberSaveable(doc.id, doc.contentHash) { mutableStateOf(false) }
    LaunchedEffect(doc.id, doc.contentHash) {
        if (!initialPositionApplied) {
            if (hit.offset > 0 && blocks.isNotEmpty()) scroll.scrollToItem(targetBlock + 1)
            initialPositionApplied = true
        }
    }
    val old = current.find { it.id == doc.id }?.contentHash != doc.contentHash
    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("reader_body"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypeLabel(kindLabel(doc.kind))
                Text(doc.chapter, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            Text(doc.title, modifier = Modifier.padding(top = 15.dp, bottom = 12.dp).testTag("detail_title").semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            if (old) Text("当前阅读的是先前版本的原文快照。", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp))
            if (hit.relatedOnly) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("这份内容仅与主题相关，可能无法直接解答你的问题。", Modifier.padding(13.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(Modifier.height(14.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        items(blocks.size) { index ->
            val block = blocks[index]
            val matched = hit.offset > 0 && index == targetBlock
            Column(Modifier.fillMaxWidth().then(if (matched) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f), RoundedCornerShape(12.dp)).padding(12.dp) else Modifier)) {
                if (matched) Text("从这里找到相关内容", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                RichParagraph(block, doc.sourceUrl)
            }
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 15.dp, bottom = 20.dp))
            Text("原文与出处", style = MaterialTheme.typography.titleMedium)
            Text("HowToLiveBetter · ${doc.path}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            if (documentVersion.isNotBlank()) Text("内容版本 ${documentVersion.take(12)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { try { uri.openUri(doc.sourceUrl) } catch (_: Exception) { sourceError = true } }, contentPadding = PaddingValues(vertical = 10.dp), modifier = Modifier.testTag("source_link")) {
                Text("在浏览器中查看来源")
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(15.dp))
            }
            if (sourceError) Text("没有可打开此链接的浏览器，原文已完整保存在本机。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RichParagraph(block: ReadingBlock, sourceUrl: String) {
    val uri = LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val text = remember(block.text, color) { markdownText(block.text, color, sourceUrl) }
    val style = when {
        block.heading in 1..2 -> MaterialTheme.typography.titleLarge
        block.heading > 0 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.bodyLarge
    }.copy(color = if (block.quoted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    SelectionContainer {
        @Suppress("DEPRECATION")
        ClickableText(text = text, style = style, modifier = Modifier.fillMaxWidth().then(if (block.heading > 0) Modifier.semantics { heading() } else Modifier), onClick = { position ->
            text.getStringAnnotations("url", position, position).firstOrNull()?.let { link ->
                try { uri.openUri(link.item) } catch (_: Exception) { linkError = true }
            }
        })
    }
    if (linkError) Text("暂时无法打开该链接。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun markdownText(input: String, linkColor: Color, sourceUrl: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("!?\\[([^]\\n]*)]\\(([^)]+)\\)|\\*\\*(.+?)\\*\\*|__([^_]+)__|`([^`]+)`|<https?://[^>]+>|https?://[^\\s<>]+|<[^>]+>")
    var last = 0
    pattern.findAll(input).forEach { match ->
        append(input.substring(last, match.range.first))
        when {
            match.groups[1] != null -> {
                val label = match.groupValues[1].ifBlank { "图片" }
                val originalUrl = match.groupValues[2].substringBefore(" \"")
                val url = when {
                    originalUrl.startsWith("https://") || originalUrl.startsWith("http://") -> originalUrl
                    originalUrl.startsWith("#") -> sourceUrl + originalUrl
                    else -> try {
                        java.net.URI(sourceUrl).resolve(originalUrl).takeIf { it.scheme in setOf("http", "https") }?.toString() ?: sourceUrl
                    } catch (_: Exception) { sourceUrl }
                }
                pushStringAnnotation("url", url)
                pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                append(if (match.value.startsWith("!")) "[图片：$label]" else label)
                pop(); pop()
            }
            match.groups[3] != null || match.groups[4] != null -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                append(match.groupValues[3].ifBlank { match.groupValues[4] })
                pop()
            }
            match.groups[5] != null -> append(match.groupValues[5])
            match.value.startsWith("http") || match.value.startsWith("<http") -> {
                val url = match.value.trim('<', '>')
                pushStringAnnotation("url", url)
                pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                append(url)
                pop(); pop()
            }
            match.value.startsWith("<br") -> append('\n')
        }
        last = match.range.last + 1
    }
    append(input.substring(last))
}

private fun stripMarkdown(text: String): String = text
    .replace(Regex("!?\\[([^]]+)]\\([^)]+\\)"), "$1")
    .replace(Regex("<[^>]+>"), "")
    .replace(Regex("[*_`#]"), "")
    .trim()

private fun highlightPlain(text: String, query: String, color: Color): AnnotatedString {
    val terms = Regex("[\\p{IsHan}]{2,}|[a-zA-Z0-9]{2,}").findAll(query).flatMap { match ->
        val token = match.value
        if (token.first().code > 127 && token.length > 2) token.windowed(2).asSequence() else sequenceOf(token)
    }.filter { it !in setOf("怎么", "什么", "哪些", "到底", "情况", "怎么办", "有什", "么办", "是什") }.distinct().toList()
    return buildAnnotatedString {
        append(text)
        terms.forEach { term ->
            var index = text.indexOf(term, ignoreCase = true)
            while (index >= 0) {
                addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), index, index + term.length)
                index = text.indexOf(term, startIndex = index + term.length, ignoreCase = true)
            }
        }
    }
}

@Composable
private fun SettingsDialog(state: AppUiState, actions: AppActions, onDismiss: () -> Unit) {
    var clearConfirmation by remember { mutableStateOf(false) }
    var selectedLicense by remember { mutableStateOf<Pair<String, String>?>(null) }
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置与资料") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("保留搜索历史", style = MaterialTheme.typography.titleMedium)
                            Text("只记录主动搜索与打开的结果", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.historyEnabled, onCheckedChange = actions.onHistoryEnabled, modifier = Modifier.testTag("history_switch"))
                    }
                    TextButton(onClick = { clearConfirmation = true }, enabled = state.history.isNotEmpty(), modifier = Modifier.testTag("clear_history")) { Text("清空全部历史") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item {
                    Text("离线资料库", style = MaterialTheme.typography.titleMedium)
                    Text("${state.documents.size} 份原文 · ${state.version.take(12).ifBlank { "内置版本" }}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    Text(state.updateStatus, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
                    Text("打开应用时，在 Wi-Fi 下每天检查一次。更新失败时保留已有内容。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    FilledTonalButton(onClick = actions.onUpdate, enabled = !state.updating, modifier = Modifier.padding(top = 12.dp).testTag("update_button")) {
                        if (state.updating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.updating) "正在更新" else "检查内容更新")
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 16.dp))
                }
                item {
                    Text("关于生活有据", style = MaterialTheme.typography.titleMedium)
                    Text("搜索原话、历史和收藏保存在本机。内容来自 HowToLiveBetter，作者 eternity4719 及仓库贡献者。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = { runCatching { uri.openUri("https://github.com/eternity4719/HowToLiveBetter") } }, contentPadding = PaddingValues(0.dp)) { Text("访问原始项目与许可说明") }
                    listOf(
                        "出处与处理说明" to "ATTRIBUTION.txt",
                        "内容许可 · CC BY 4.0" to "HowToLiveBetter-CC-BY-4.0.txt",
                        "语义模型许可 · MIT" to "BGE-MIT.txt",
                    ).forEach { (title, file) ->
                        TextButton(onClick = {
                            selectedLicense = title to runCatching { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }.getOrElse { "许可文件暂时无法读取，请查看原始项目许可说明。" }
                        }, contentPadding = PaddingValues(0.dp)) { Text(title) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
    if (clearConfirmation) AlertDialog(
        onDismissRequest = { clearConfirmation = false },
        title = { Text("清空搜索历史？") },
        text = { Text("收藏的原文会继续保留。") },
        confirmButton = { TextButton(onClick = { actions.onClearHistory(); clearConfirmation = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("取消") } },
    )
    selectedLicense?.let { (title, content) ->
        AlertDialog(
            onDismissRequest = { selectedLicense = null },
            title = { Text(title) },
            text = { SelectionContainer { Text(content, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { selectedLicense = null }) { Text("返回") } },
        )
    }
}
