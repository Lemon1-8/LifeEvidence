@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.shenghuoyouju.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.shenghuoyouju.data.ContentDocument
import app.shenghuoyouju.data.SearchHit
import kotlinx.coroutines.launch

@Composable
internal fun DirectoryScreen(
    documents: List<ContentDocument>,
    scroll: LazyListState,
    kind: String,
    query: String,
    expandedChapters: List<String>,
    collapsedMatches: List<String>,
    favoriteIds: Set<String>,
    revealId: String?,
    onKindChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleChapter: (String) -> Unit,
    onCollapseAll: () -> Unit,
    onRevealed: () -> Unit,
    onOpen: (SearchHit) -> Unit,
    onFullSearch: (String) -> Unit,
) {
    val chapters = remember(documents, kind, query) { buildDirectoryTree(documents, kind, query) }
    val kinds = remember(documents) { documents.map { it.kind }.distinct() }
    val filtering = query.isNotBlank()
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    fun isExpanded(chapter: DirectoryChapter) = if (filtering) chapter.key !in collapsedMatches else chapter.key in expandedChapters

    LaunchedEffect(revealId, chapters) {
        if (revealId != null) {
            var index = 0
            for (chapter in chapters) {
                val documentIndex = chapter.documents.indexOfFirst { it.id == revealId }
                if (documentIndex >= 0) {
                    scroll.requestScrollToItem(index + 1 + documentIndex)
                    break
                }
                index += 1 + if (isExpanded(chapter)) chapter.documents.size else 0
            }
            onRevealed()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 22.dp).padding(top = 10.dp)) {
            Text("按章节找内容", style = MaterialTheme.typography.headlineMedium)
            Text("先选章节，再点开想读的原文", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().testTag("directory_query"),
                placeholder = { Text("筛选章节或文章标题") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }, modifier = Modifier.testTag("directory_clear_query")) {
                        Icon(Icons.Outlined.Close, "清空目录筛选")
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = kind.isEmpty(), onClick = { onKindChange(""); focus.clearFocus() },
                    label = { Text("全部") }, modifier = Modifier.testTag("directory_kind_all"))
                kinds.forEach { value ->
                    FilterChip(selected = kind == value, onClick = { onKindChange(value); focus.clearFocus() },
                        label = { Text(kindLabel(value)) }, modifier = Modifier.testTag("directory_kind_$value"))
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${chapters.size} 个章节 · ${chapters.sumOf { it.documents.size }} 份${if (filtering) "匹配原文" else "原文"}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag("directory_count"))
                TextButton(onClick = { onCollapseAll(); focus.clearFocus() }, enabled = chapters.any { isExpanded(it) },
                    modifier = Modifier.testTag("directory_collapse_all")) {
                    Icon(Icons.Outlined.UnfoldLess, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("全部收起")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
        if (chapters.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp).testTag("directory_empty"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.SearchOff, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("没有匹配的目录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text("这里按章节名和标题筛选", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                if (filtering) {
                    FilledTonalButton(onClick = { focus.clearFocus(); onFullSearch(query.trim()) }, modifier = Modifier.testTag("directory_full_search")) {
                        Text("去全文搜索")
                    }
                }
                TextButton(onClick = { onQueryChange(""); onKindChange(""); focus.clearFocus() }) { Text("重置筛选") }
            }
        } else {
            Box(Modifier.weight(1f)) {
                LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("directory_list"),
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 76.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    chapters.forEach { chapter ->
                        val expanded = isExpanded(chapter)
                        item(key = "chapter:${chapter.key}", contentType = "chapter") {
                            DirectoryChapterRow(chapter, expanded, onClick = { focus.clearFocus(); onToggleChapter(chapter.key) })
                        }
                        if (expanded) {
                            items(chapter.documents, key = { "directory:${it.id}" }, contentType = { "document" }) { doc ->
                                DirectoryDocumentRow(doc, doc.id in favoriteIds, onClick = { onOpen(SearchHit(doc, doc.intro, 0, 0f)) })
                            }
                        }
                    }
                }
                val showTop by remember { derivedStateOf { scroll.firstVisibleItemIndex > 3 } }
                if (showTop) {
                    SmallFloatingActionButton(onClick = { scope.launch { scroll.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("directory_top"),
                        containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Outlined.VerticalAlignTop, "回到目录顶部")
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryChapterRow(chapter: DirectoryChapter, expanded: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("directory_chapter_${chapter.key}").semantics {
            heading()
            stateDescription = if (expanded) "已展开" else "已折叠"
        },
        shape = RoundedCornerShape(16.dp),
        color = if (expanded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = .2f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
    ) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder, null,
                modifier = Modifier.size(23.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(chapter.title, style = MaterialTheme.typography.titleMedium)
                Text(if (chapter.documents.size == chapter.totalCount) "${chapter.totalCount} 份原文"
                    else "${chapter.documents.size} / ${chapter.totalCount} 份原文匹配",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DirectoryDocumentRow(document: ContentDocument, saved: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.SubdirectoryArrowRight, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(8.dp))
        Surface(onClick = onClick, modifier = Modifier.weight(1f).testTag("document_${document.id}"),
            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(document.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (saved) Icon(Icons.Outlined.Bookmark, "已收藏", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
