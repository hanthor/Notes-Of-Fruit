package com.example.applenotes.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.data.DEFAULT_FOLDER_RECORD_NAME
import com.example.applenotes.data.TRASH_RECORD_NAME
import com.example.applenotes.ui.ListAction
import com.example.applenotes.ui.common.MessageSnackbarEffect
import com.example.applenotes.ui.common.UiMessage
import com.example.applenotes.ui.common.AppSnackbarHost
import com.example.applenotes.ui.common.OfflineBanner
import androidx.compose.material3.SnackbarHostState
import com.example.applenotes.ui.common.humanRelative
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesListScreen(
    allNotes: List<NoteSummary>,
    folders: List<FolderSummary>,
    selectedFolder: String?,
    onSelectFolder: (String?) -> Unit,
    onSelect: (NoteSummary) -> Unit,
    onRefresh: () -> Unit,
    onCreateNote: () -> Unit,
    onSignOut: () -> Unit,
    message: UiMessage<ListAction>? = null,
    onMessageAction: (UiMessage<ListAction>) -> Unit = {},
    onMessageDismissed: (UiMessage<ListAction>) -> Unit = {},
    offline: Boolean = false,
    drawerActions: List<DrawerAction> = emptyList(),
    /** Background sync running ("Updating…"; issue #12). */
    syncing: Boolean = false,
    /** Pull-to-refresh in progress. */
    refreshing: Boolean = false,
    /** Unsent drafts in the outbox (issue #13); tapping the chip retries them. */
    unsentCount: Int = 0,
    onRetryUnsent: () -> Unit = {},
    /** Notes whose cached body matches a query (search beyond titles and snippets). */
    searchBodies: suspend (String) -> Set<String> = { emptySet() },
) {
    var query by rememberSaveable { mutableStateOf("") }
    var bodyMatches by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(query) {
        bodyMatches = emptySet()
        if (query.isBlank()) return@LaunchedEffect
        delay(250)
        bodyMatches = searchBodies(query)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    MessageSnackbarEffect(message, snackbarHostState, onMessageAction, onMessageDismissed)
    // Notes filtered to the currently-selected folder. null = "All notes"
    // (every folder except trash). TRASH_RECORD_NAME = trash only.
    val notesInFolder = remember(allNotes, selectedFolder) {
        when (selectedFolder) {
            null -> allNotes.filter { it.folderRecordName != TRASH_RECORD_NAME }
            else -> allNotes.filter { it.folderRecordName == selectedFolder }
        }
    }
    // Searches every cached note (the whole account once synced, not just a
    // first page), plus the bodies of notes opened before.
    val filtered = remember(notesInFolder, query, bodyMatches) {
        if (query.isBlank()) notesInFolder
        else notesInFolder.filter {
            (it.title?.contains(query, ignoreCase = true) == true) ||
                (it.snippet?.contains(query, ignoreCase = true) == true) ||
                it.recordName in bodyMatches
        }
    }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    // System back closes the drawer if it's open; otherwise let it fall
    // through (the activity-default behaviour exits the app from the list).
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        drawerScope.launch { drawerState.close() }
    }
    val selectedFolderName = remember(selectedFolder, folders) {
        when (selectedFolder) {
            null -> "All notes"
            TRASH_RECORD_NAME -> "Recently Deleted"
            DEFAULT_FOLDER_RECORD_NAME -> folders.firstOrNull { it.recordName == DEFAULT_FOLDER_RECORD_NAME }
                ?.displayName ?: "Notes"
            else -> folders.firstOrNull { it.recordName == selectedFolder }?.displayName ?: "Folder"
        }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            FoldersDrawer(
                folders = folders,
                allNotes = allNotes,
                selectedFolder = selectedFolder,
                onSelect = { rn ->
                    onSelectFolder(rn)
                    drawerScope.launch { drawerState.close() }
                },
                onSignOut = {
                    drawerScope.launch { drawerState.close() }
                    onSignOut()
                },
                actions = drawerActions.map { a ->
                    DrawerAction(a.label, a.icon) {
                        drawerScope.launch { drawerState.close() }
                        a.onClick()
                    }
                },
            )
        },
    ) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        drawerScope.launch { drawerState.open() }
                    }) {
                        Icon(Icons.Default.Menu, contentDescription = "Folders")
                    }
                },
                title = {
                    Column {
                        Text(selectedFolderName, style = MaterialTheme.typography.displayLarge)
                        Text(
                            "${notesInFolder.size} note${if (notesInFolder.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateNote,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "New note")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (offline) OfflineBanner()
            SyncStatusRow(syncing = syncing && !refreshing, unsentCount = unsentCount, onRetryUnsent = onRetryUnsent)
            // Search bar
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search notes") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = MaterialTheme.shapes.large,
            )
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
            if (filtered.isEmpty()) {
                // Scrollable so the pull gesture also works on an empty list.
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyNotesState(query.isNotBlank())
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.recordName }) { note ->
                        NoteCard(note, onClick = { onSelect(note) })
                    }
                    item { Spacer(Modifier.height(80.dp)) } // FAB clearance
                }
            }
            }  // PullToRefreshBox
        }
    }
    }  // ModalNavigationDrawer
}

@Composable
private fun EmptyNotesState(filtering: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Note,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (filtering) "No notes match your search." else "No notes yet.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!filtering) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap + to create a note, or sync one from Mac or iCloud.com.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteCard(note: NoteSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = note.title?.takeIf { it.isNotBlank() } ?: "Untitled",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                note.modificationTimestampMs?.let { ts ->
                    Text(
                        text = humanRelative(ts),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (!note.snippet.isNullOrBlank()) {
                    Text(
                        text = note.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
