package com.example.applenotes.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.applenotes.R
import com.example.applenotes.data.ReadOnlyReason
import com.example.applenotes.data.lockedNoteHint
import com.example.applenotes.export.NoteExportMenuItems
import com.example.applenotes.export.NoteSnapshot
import com.example.applenotes.export.rememberNoteExportActions
import com.example.applenotes.integrations.NoteShortcuts
import com.example.applenotes.ui.common.CenteredSpinner
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Lock
import com.example.applenotes.ui.common.AppSnackbarHost
import com.example.applenotes.ui.common.MessageSnackbarEffect
import com.example.applenotes.ui.common.OfflineBanner
import com.example.applenotes.ui.common.humanRelative

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteDetailScreen(
    vm: NoteDetailViewModel,
    /** Leave to the list without saving (nothing to save, or the user chose to discard). */
    onBack: () -> Unit,
    /** Go to sign-in after the session expired, keeping this note and its draft open. */
    onSignIn: () -> Unit = {},
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val record = vm.record
    if (status.loading || record == null) {
        CenteredSpinner(stringResource(R.string.loading_note))
        return
    }
    if (status.deleting) {
        CenteredSpinner(stringResource(R.string.loading_deleting))
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val shareContext = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val saving = status.saving
    val lastSavedAtMs = status.lastSavedAtMs
    val titleDraft = vm.titleDraft
    val bodyDraft = vm.bodyDraft
    val spansDraft = vm.spansDraft
    val fullBodyForSave = vm.fullBodyForSave
    val isModified = vm.isModified
    val viewMode = vm.viewMode
    val readOnly = vm.isReadOnly
    val attachments by vm.attachments.collectAsStateWithLifecycle()

    // Live body-only spans for formatted view (mirrors any draft mutation).
    val bodyAttrSpans = remember(titleDraft, fullBodyForSave, spansDraft) {
        vm.bodyAttrSpans()
    }

    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val exportActions = rememberNoteExportActions {
        NoteSnapshot.of(vm.titleDraft, vm.fullBodyForSave, vm.spansDraft, vm.attachments.value)
    }
    var menuOpen by remember { mutableStateOf(false) }
    val titleFieldLabel = stringResource(R.string.detail_title_field)
    val bodyFieldLabel = stringResource(R.string.detail_body_field)

    // Auto-save on lifecycle ON_PAUSE (app backgrounded, screen turns off,
    // another activity covers us, etc.). Best-effort fire-and-forget — the
    // ViewModel's save handles the actual network call. We pass
    // `alsoExit=false` because we're not navigating, just persisting.
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) vm.autoSave()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // "Saved Xs ago" pill — recompute every 10s.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastSavedAtMs) {
        while (lastSavedAtMs != null) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(10_000)
        }
    }
    val savedAgoText: String? = lastSavedAtMs?.let { savedAt ->
        val deltaSec = ((nowMs - savedAt) / 1000).toInt()
        when {
            deltaSec < 5 -> stringResource(R.string.detail_saved)
            deltaSec < 60 -> stringResource(R.string.detail_saved_seconds_ago, deltaSec)
            deltaSec < 3600 -> stringResource(R.string.detail_saved_minutes_ago, deltaSec / 60)
            else -> stringResource(R.string.detail_saved_hours_ago, deltaSec / 3600)
        }
    }

    // Save / delete failures: snackbar with Retry, the draft stays put.
    MessageSnackbarEffect(
        status.message,
        snackbarHostState,
        onAction = { m ->
            vm.onMessageAction(m)
            if (m.action == NoteDetailViewModel.DetailAction.SignIn) onSignIn()
        },
        onDismiss = vm::onMessageDismissed,
    )
    val online by vm.online.collectAsStateWithLifecycle()
    var showUnsavedDialog by rememberSaveable { mutableStateOf(false) }

    val onBackOrSave: () -> Unit = {
        when {
            // A save is in flight: leave once it lands. The ViewModel queues
            // the exit (saving anything typed meanwhile first); if the save
            // fails we stay here with the draft.
            saving -> vm.save(alsoExit = true)
            // The last save failed: ask instead of retrying silently.
            isModified && status.saveFailed -> showUnsavedDialog = true
            // Save and navigate atomically. The ViewModel requests the
            // navigation in the save's success path (alsoExit=true).
            isModified -> vm.save(alsoExit = true)
            else -> onBack()
        }
    }

    // Intercept the system back button / gesture so it navigates to the list
    // instead of closing the activity. Mirrors the back-arrow's behaviour
    // (save-if-modified, then exit). Stays enabled while saving: a second
    // back during a save is queued by the ViewModel, not fired twice.
    androidx.activity.compose.BackHandler {
        onBackOrSave()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBackOrSave) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    title = {
                        Column {
                            // Editable title in the app bar — tap to type.
                            BasicTextField(
                                value = titleDraft,
                                onValueChange = { vm.onTitleChange(it) },
                                readOnly = readOnly,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.titleLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                decorationBox = { inner ->
                                    if (titleDraft.isEmpty()) {
                                        Text(
                                            stringResource(R.string.note_untitled),
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                        )
                                    }
                                    inner()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = titleFieldLabel },
                            )
                            val pill = when {
                                readOnly -> stringResource(R.string.detail_status_read_only)
                                saving -> stringResource(R.string.detail_status_saving)
                                isModified -> stringResource(R.string.detail_status_unsaved)
                                savedAgoText != null -> savedAgoText
                                else -> null
                            }
                            if (pill != null) {
                                Text(
                                    pill,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    },
                    actions = {
                        if (isModified) {
                            IconButton(
                                enabled = !saving,
                                onClick = { vm.save(alsoExit = false) },
                            ) {
                                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_save))
                            }
                        }
                        IconButton(
                            // Plain text plus an HTML version carrying the
                            // note's formatting (export/NoteHtml.kt).
                            onClick = exportActions.share,
                        ) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            NoteExportMenuItems(
                                actions = exportActions,
                                hasImages = attachments.values.any { it is AttachmentContent.Image },
                                canPin = record.recordChangeTag != null,
                                onPin = {
                                    val ok = NoteShortcuts.requestPin(shareContext, record.recordName, titleDraft)
                                    if (!ok) {
                                        android.widget.Toast.makeText(
                                            shareContext,
                                            R.string.detail_pin_unsupported,
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                onDismiss = { menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_menu_delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; showDeleteDialog = true },
                            )
                        }
                    },
                )
                if (saving) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                when {
                    // Issue #12: opened from the cache because there's no network.
                    status.offlineCopy && !online -> OfflineCopyBanner()
                    !online -> OfflineBanner()
                }
                // "Updated on another device" polling + banner (issue #16). Not while
                // the "Restore unsaved changes?" question is open (issue #13).
                if (record.recordChangeTag != null && status.savedDraft == null) {
                    RemoteChangeIndicator(vm, record.recordName)
                }
                vm.readOnlyReason?.let {
                    ReadOnlyBanner(it, hint = if (it == ReadOnlyReason.LOCKED) lockedNoteHint(record) else null)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            // Body display: formatted view by default for notes that carry
            // paragraph styling (headings, lists, checkboxes), plain editor
            // otherwise. Tapping the formatted view drops into the editor.
            if (viewMode) {
                FormattedNoteBody(
                    fullBody = bodyDraft.text,
                    titlePrefixLength = 0,
                    attrSpans = bodyAttrSpans,
                    attachmentContent = attachments,
                    // Read-only notes never drop into the editor or toggle
                    // checkboxes (which would auto-save).
                    onTap = if (readOnly) null else ({ vm.enterEditMode() }),
                    onToggleCheckbox = if (readOnly) null else ({ bodyParaStart ->
                        // Auto-saves the toggle so check state persists.
                        vm.toggleCheckbox(bodyParaStart)
                    }),
                    emptyPlaceholder = stringResource(if (readOnly) R.string.detail_nothing_to_show else R.string.detail_start_typing),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                BasicTextField(
                    value = bodyDraft,
                    onValueChange = { vm.onBodyChange(it) },
                    visualTransformation = remember(bodyAttrSpans) {
                        FormattingVisualTransformation(bodyAttrSpans)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .semantics { contentDescription = bodyFieldLabel },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner ->
                        if (bodyDraft.text.isEmpty()) {
                            Text(
                                stringResource(R.string.detail_start_typing),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                        inner()
                    },
                )
                // Format toolbar — only shown while editing.
                FormattingToolbar(
                    titlePrefixLength = if (titleDraft.isEmpty()) 0 else titleDraft.length + 1,
                    bodyValue = bodyDraft,
                    fullBody = fullBodyForSave,
                    spans = spansDraft,
                    onSpansChange = { newSpans ->
                        vm.onSpansChange(newSpans)
                    },
                )
            }
            // Footer: modification date + char count + chord guidance.
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val modDate = record.stringField("ModificationDate")?.toLongOrNull()
                Text(
                    text = modDate?.let { stringResource(R.string.detail_modified, humanRelative(it)) } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = pluralStringResource(R.plurals.detail_char_count, bodyDraft.text.length, bodyDraft.text.length),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.unsaved_dialog_title)) },
            text = { Text(stringResource(R.string.unsaved_dialog_text)) },
            confirmButton = {
                TextButton(onClick = { showUnsavedDialog = false; vm.save(alsoExit = true) }) {
                    Text(stringResource(R.string.action_retry))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showUnsavedDialog = false; vm.discardUnsavedChanges(); onBack() }) {
                        Text(stringResource(R.string.action_discard))
                    }
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text(stringResource(R.string.unsaved_dialog_keep_editing))
                    }
                }
            },
        )
    }

    // Issue #4: the draft overlaps a change made on another device.
    // Nothing was written; the user has to pick (no dismiss), because
    // saving the draft as-is would revert the other change.
    status.conflict?.let {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.conflict_dialog_title)) },
            text = { Text(stringResource(R.string.conflict_dialog_text)) },
            confirmButton = {
                TextButton(onClick = { vm.keepBoth() }) { Text(stringResource(R.string.conflict_keep_both)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.discardLocalChanges() }) {
                    Text(stringResource(R.string.conflict_discard_mine))
                }
            },
        )
    }

    // Issue #13: an unsent draft from an earlier session.
    status.savedDraft?.let {
        RestoreDraftDialog(it, onRestore = vm::restoreSavedDraft, onDiscard = vm::discardSavedDraft)
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = { Text(stringResource(R.string.delete_dialog_text)) },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; vm.delete() }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Explains why the open note can't be edited (issue #3). */
@Composable
private fun ReadOnlyBanner(reason: ReadOnlyReason, hint: String? = null) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column {
                Text(stringResource(reason.explanation), style = MaterialTheme.typography.bodySmall)
                // Locked notes (issue #16): Apple's password hint, if the record has one.
                if (hint != null) {
                    Text(stringResource(R.string.read_only_password_hint, hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
