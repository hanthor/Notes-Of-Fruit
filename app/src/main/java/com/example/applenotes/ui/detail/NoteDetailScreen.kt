package com.example.applenotes.ui.detail

import android.content.Intent
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.ui.common.humanRelative

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteDetailScreen(
    record: NoteRecord,
    onBack: () -> Unit,
    onSave: (newBody: String, isPureAppend: Boolean, alsoExit: Boolean, newSpans: List<NoteBodyEditor.AttrSpan>?) -> Unit,
    onDelete: () -> Unit,
    saving: Boolean,
    savedHint: String? = null,
    lastSavedAtMs: Long? = null,
    onHintShown: () -> Unit = {},
    onFetchAttachment: (suspend (String) -> NoteRecord?)? = null,
    onFetchAsset: (suspend (String) -> ByteArray?)? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val shareContext = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(savedHint) {
        if (savedHint != null) {
            snackbarHostState.showSnackbar(savedHint)
            onHintShown()
        }
    }
    val originalBody = remember(record.recordName, record.recordChangeTag) {
        record.stringField("TextDataEncrypted")?.let { b64 ->
            runCatching { NoteBodyEditor.readVisibleTextFromBase64(b64) }.getOrNull()
                ?: runCatching { NoteBodyEditor.readTextFromBase64(b64) }.getOrNull()
        }.orEmpty()
    }
    // Apple Notes stores the title as the first line of the body. We split the
    // raw originalBody into two editable pieces:
    //   - `titleDraft`  → rendered as a TextField inside the top app bar
    //   - `bodyDraft`   → rendered as the main BasicTextField below
    // On save we re-assemble `titleDraft + "\n" + bodyDraft` (omitting the
    // separator if either piece is empty) so the underlying CloudKit body
    // shape stays identical to what Mac expects (first line == title).
    val initialTitle = remember(originalBody) {
        val firstNl = originalBody.indexOf('\n')
        if (firstNl < 0) originalBody else originalBody.substring(0, firstNl)
    }
    val initialBody = remember(originalBody) {
        val firstNl = originalBody.indexOf('\n')
        if (firstNl < 0) "" else originalBody.substring(firstNl + 1)
    }
    var titleDraft by remember(record.recordName, record.recordChangeTag) {
        mutableStateOf(initialTitle)
    }
    var bodyDraft by remember(record.recordName, record.recordChangeTag) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initialBody))
    }
    val fullBodyForSave = when {
        titleDraft.isEmpty() && bodyDraft.text.isEmpty() -> ""
        titleDraft.isEmpty() -> bodyDraft.text
        bodyDraft.text.isEmpty() -> titleDraft
        else -> "$titleDraft\n${bodyDraft.text}"
    }
    // isModified is computed after spansDraft is declared below — see further down.
    val isPureAppend = remember(fullBodyForSave, originalBody) {
        fullBodyForSave.startsWith(originalBody) && fullBodyForSave.length > originalBody.length
    }
    var isModified = fullBodyForSave != originalBody

    // Parse the note's attribute_runs once per record load — kept around so
    // the splice path in NoteAppender can preserve paragraph styles through
    // edits (Phase D1). Display-time formatting via VisualTransformation is
    // a future enhancement; for now the editor shows plain text.
    val attrSpans = remember(record.recordName, record.recordChangeTag) {
        record.stringField("TextDataEncrypted")?.let { b64 ->
            runCatching { NoteBodyEditor.parseAttributeRunsFromBase64(b64) }.getOrNull()
        }.orEmpty()
    }
    // attrSpans cover the FULL visible text including the title line (which
    // we render separately in the app bar). Trim the title's worth of spans
    // off the front so styleAt indexes align with `initialBody`. Computed
    // from spansDraft so toolbar/tap mutations are reflected live.
    // (spansDraft is declared below; we wrap in remember after that.)
    // Default to the formatted view when the note has any non-body paragraph
    // style (heading, bullets, checkboxes, etc.). Plain notes go straight to
    // the editor — no extra tap needed. Tapping the formatted view drops
    // into the editor; we don't auto-toggle back, to avoid losing edits.
    val bodyAttrSpansInit = remember(originalBody, attrSpans) {
        val skip = if (originalBody.contains('\n')) initialTitle.length + 1
                   else initialTitle.length
        attrSpansAfter(attrSpans, skip)
    }
    val hasRichFormatting = remember(bodyAttrSpansInit) {
        bodyAttrSpansInit.any {
            it.style != NoteBodyEditor.ParagraphStyle.BODY &&
                it.style != NoteBodyEditor.ParagraphStyle.TITLE
        }
    }
    var viewMode by remember(record.recordName, record.recordChangeTag) {
        mutableStateOf(hasRichFormatting && initialBody.isNotEmpty())
    }

    // Mutable AttrSpan list driving the editor's formatting state. Covers the
    // FULL visible body (title + "\n" + body) — same coordinate space as the
    // proto. Toolbar mutations operate on body-relative ranges and we add the
    // title prefix length when applying.
    var spansDraft by remember(record.recordName, record.recordChangeTag) {
        mutableStateOf(attrSpans)
    }
    // Track the previous full-body text so we can keep [spansDraft] in sync
    // with character insertions/deletions.
    var prevFullBody by remember(record.recordName, record.recordChangeTag) {
        mutableStateOf(originalBody)
    }
    LaunchedEffect(fullBodyForSave) {
        if (fullBodyForSave == prevFullBody) return@LaunchedEffect
        spansDraft = syncSpansToText(prevFullBody, spansDraft, fullBodyForSave)
        prevFullBody = fullBodyForSave
    }
    // Format-only changes need to mark the note as modified so save fires.
    if (spansDraft != attrSpans) isModified = true

    // Live body-only spans for formatted view (mirrors any draft mutation).
    val bodyAttrSpans = remember(titleDraft, fullBodyForSave, spansDraft) {
        val skip = if (fullBodyForSave.contains('\n')) titleDraft.length + 1
                   else titleDraft.length
        attrSpansAfter(spansDraft, skip)
    }

    // Resolve attachment cards. For each AttrSpan with isAttachment + recordName
    // we kick off an async lookup, decode the result by attachment type
    // ("com.apple.notes.table" → table grid; "public.png/jpeg/heic" → image
    // bytes), and feed the cache into FormattedNoteBody.
    val attachmentCache = remember(record.recordName, record.recordChangeTag) {
        mutableStateOf<Map<String, AttachmentContent>>(emptyMap())
    }
    LaunchedEffect(record.recordName, record.recordChangeTag, onFetchAttachment) {
        val fetcher = onFetchAttachment ?: return@LaunchedEffect
        val assetFetcher = onFetchAsset ?: { _ -> null }
        val recordNames = bodyAttrSpans.mapNotNull { it.attachmentRecordName }.toSet()
        for (rn in recordNames) {
            if (attachmentCache.value.containsKey(rn)) continue
            val rec = runCatching { fetcher(rn) }.getOrNull() ?: continue
            val content = decodeAttachment(rec, assetFetcher, fetcher)
            if (content != null) {
                attachmentCache.value = attachmentCache.value + (rn to content)
            }
        }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // Auto-save on lifecycle ON_PAUSE (app backgrounded, screen turns off,
    // another activity covers us, etc.). Best-effort fire-and-forget — the
    // parent's onSave coroutine handles the actual network call. We pass
    // `alsoExit=false` because we're not navigating, just persisting.
    // Use `rememberUpdatedState` semantics by referencing the latest values
    // through the captured composable scope.
    DisposableEffect(lifecycleOwner, isModified, fullBodyForSave, isPureAppend) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && isModified && !saving) {
                onSave(fullBodyForSave, isPureAppend, false, spansDraft)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // "Saved Xs ago" pill — recompute every 10s.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastSavedAtMs) {
        while (lastSavedAtMs != null) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(10_000)
        }
    }
    val savedAgoText: String? = lastSavedAtMs?.let { savedAt ->
        val deltaSec = (nowMs - savedAt) / 1000
        when {
            deltaSec < 5 -> "Saved"
            deltaSec < 60 -> "Saved ${deltaSec}s ago"
            deltaSec < 3600 -> "Saved ${deltaSec / 60}m ago"
            else -> "Saved ${deltaSec / 3600}h ago"
        }
    }

    val onBackOrSave: () -> Unit = {
        if (isModified && !saving) {
            // Save and navigate atomically. Parent handles the navigation in
            // the save's success callback (sees alsoExit=true).
            onSave(fullBodyForSave, isPureAppend, true, spansDraft)
        } else {
            onBack()
        }
    }

    // Intercept the system back button / gesture so it navigates to the list
    // instead of closing the activity. Mirrors the back-arrow's behaviour
    // (save-if-modified, then exit). Disabled while saving so a tap on
    // "Save" → back doesn't fire twice.
    androidx.activity.compose.BackHandler(enabled = !saving) {
        onBackOrSave()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBackOrSave) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Column {
                            // Editable title in the app bar — tap to type.
                            BasicTextField(
                                value = titleDraft,
                                onValueChange = { titleDraft = it.replace("\n", "") },
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
                                            "Untitled",
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                        )
                                    }
                                    inner()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val pill = when {
                                saving -> "Saving…"
                                isModified -> "Unsaved"
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
                                onClick = { onSave(fullBodyForSave, isPureAppend, false, spansDraft) },
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Save")
                            }
                        }
                        IconButton(
                            onClick = {
                                // Share the visible body (title + content) as
                                // plaintext via Android's standard chooser.
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, fullBodyForSave)
                                    titleDraft.takeIf { it.isNotBlank() }?.let {
                                        putExtra(Intent.EXTRA_SUBJECT, it)
                                    }
                                }
                                val chooser = Intent.createChooser(sendIntent, null).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                shareContext.startActivity(chooser)
                            },
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete note") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; showDeleteDialog = true },
                            )
                        }
                    },
                )
                if (saving) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                    attachmentContent = attachmentCache.value,
                    onTap = { viewMode = false },
                    onToggleCheckbox = { bodyParaStart ->
                        val skip = if (fullBodyForSave.contains('\n')) titleDraft.length + 1
                            else titleDraft.length
                        val fullPos = bodyParaStart + skip
                        spansDraft = NoteBodyEditor.toggleCheckbox(
                            fullBodyForSave, spansDraft, fullPos,
                        )
                        // Auto-save the toggle so check state persists.
                        onSave(fullBodyForSave, false, false, spansDraft)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                BasicTextField(
                    value = bodyDraft,
                    onValueChange = { bodyDraft = it },
                    visualTransformation = remember(bodyAttrSpans) {
                        FormattingVisualTransformation(bodyAttrSpans)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner ->
                        if (bodyDraft.text.isEmpty()) {
                            Text(
                                "Start typing…",
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
                        spansDraft = newSpans
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
                    text = modDate?.let { "Modified ${humanRelative(it)}" } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${bodyDraft.text.length} chars",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this note?") },
            text = { Text("This moves the note to Recently Deleted in iCloud Notes.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}
