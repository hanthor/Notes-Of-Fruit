package com.example.applenotes.ui.detail

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.data.AppGraph
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.data.runCatchingCancellable
import com.example.applenotes.data.userMessage
import com.example.applenotes.proto.NoteBodyEditor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private const val TAG = "AppleNotesUi"

/**
 * State and save/delete logic for the note detail/editor screen.
 *
 * One instance (activity-scoped) serves whichever note is open; [open] loads
 * a note and is idempotent per `openId`, so rotation keeps the editor as is.
 *
 * The editor draft (title, body [TextFieldValue] incl. selection and IME
 * composition, format spans) is Compose snapshot state rather than a
 * StateFlow: text fields must see their own updates synchronously or the
 * cursor jumps / IME composition breaks. Everything else is in [status].
 * The draft is mirrored into [savedState] so it also survives process death.
 */
class NoteDetailViewModel(
    private val repository: NotesRepository,
    private val savedState: SavedStateHandle,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    data class Status(
        /** Which open of a note this state belongs to; null = nothing open. */
        val openId: String? = null,
        /** Restoring after process death (looking the note up again). */
        val loading: Boolean = false,
        val saving: Boolean = false,
        val deleting: Boolean = false,
        val lastSavedAtMs: Long? = null,
        /** Set when the screen should navigate back to the list. */
        val exitRequested: Boolean = false,
        /** Set for failures that should replace the screen with an error. */
        val fatalError: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _attachments = MutableStateFlow<Map<String, AttachmentContent>>(emptyMap())

    /**
     * Resolved attachment cards. For each AttrSpan with isAttachment +
     * recordName we kick off an async lookup, decode the result by attachment
     * type ("com.apple.notes.table" → table grid; "public.png/jpeg/heic" →
     * image bytes), and feed the cache into FormattedNoteBody.
     */
    val attachments: StateFlow<Map<String, AttachmentContent>> = _attachments.asStateFlow()
    private var attachmentsJob: Job? = null

    // ----- Loaded note -----

    var record: NoteRecord? by mutableStateOf(null)
        private set
    private var pendingFolderRef: JsonElement? = null

    /** The note's visible text as loaded (title line + body). */
    var originalBody: String by mutableStateOf("")
        private set
    private var attrSpans: List<NoteBodyEditor.AttrSpan> by mutableStateOf(emptyList())

    // ----- Editor draft -----
    //
    // Apple Notes stores the title as the first line of the body. We split
    // the raw originalBody into two editable pieces:
    //   - `titleDraft`  → rendered as a TextField inside the top app bar
    //   - `bodyDraft`   → rendered as the main BasicTextField below
    // On save we re-assemble `titleDraft + "\n" + bodyDraft` (omitting the
    // separator if either piece is empty) so the underlying CloudKit body
    // shape stays identical to what Mac expects (first line == title).

    var titleDraft: String by mutableStateOf("")
        private set
    var bodyDraft: TextFieldValue by mutableStateOf(TextFieldValue(""))
        private set

    /**
     * Mutable AttrSpan list driving the editor's formatting state. Covers the
     * FULL visible body (title + "\n" + body) — same coordinate space as the
     * proto. Toolbar mutations operate on body-relative ranges and we add the
     * title prefix length when applying.
     */
    var spansDraft: List<NoteBodyEditor.AttrSpan> by mutableStateOf(emptyList())
        private set

    /** Previous full-body text, so [spansDraft] can follow insertions/deletions. */
    private var prevFullBody: String = ""

    /**
     * Formatted (read) view vs plain editor. Defaults to the formatted view
     * when the note has any non-body paragraph style (heading, bullets,
     * checkboxes, etc.). Plain notes go straight to the editor — no extra tap
     * needed. Tapping the formatted view drops into the editor; we don't
     * auto-toggle back, to avoid losing edits.
     */
    var viewMode: Boolean by mutableStateOf(false)
        private set

    val fullBodyForSave: String
        get() = assembleFullBody(titleDraft, bodyDraft.text)

    val isPureAppend: Boolean
        get() {
            val full = fullBodyForSave
            return full.startsWith(originalBody) && full.length > originalBody.length
        }

    /** Text changed, or format-only changes (which also need a save). */
    val isModified: Boolean
        get() = fullBodyForSave != originalBody || spansDraft != attrSpans

    /** Length of the title line plus its separator in [fullBodyForSave]. */
    val titlePrefixSkip: Int
        get() = if (fullBodyForSave.contains('\n')) titleDraft.length + 1 else titleDraft.length

    // ----- Opening -----

    /**
     * Show [record] for the navigation entry [openId]. No-op when [openId] is
     * already open (re-composition after rotation). [record] == null means
     * "restore [openId] from saved state" (after process death).
     */
    fun open(openId: String, record: NoteRecord?, pendingFolderRef: JsonElement?) {
        if (_status.value.openId == openId) return
        attachmentsJob?.cancel()
        _attachments.value = emptyMap()
        if (record == null) {
            restore(openId)
            return
        }
        _status.value = Status(openId = openId)
        load(record, pendingFolderRef, draft = null)
    }

    private fun restore(openId: String) {
        val recordName = savedState.get<String>(KEY_RECORD_NAME)
        if (savedState.get<String>(KEY_OPEN_ID) != openId || recordName == null) {
            _status.value = Status(openId = openId, exitRequested = true)
            return
        }
        val tag = savedState.get<String>(KEY_RECORD_TAG)
        val draft = DraftSnapshot.decodeOrNull(savedState.get<String>(KEY_DRAFT))
        if (tag == null) {
            // A never-saved draft: nothing to look up.
            val folderRef = savedState.get<String>(KEY_FOLDER_REF)
                ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
            if (folderRef == null) {
                _status.value = Status(openId = openId, exitRequested = true)
                return
            }
            _status.value = Status(openId = openId)
            load(NoteRecord(recordName, "Note", null, emptyMap()), folderRef, draft)
            return
        }
        _status.value = Status(openId = openId, loading = true)
        viewModelScope.launch {
            runCatchingCancellable { repository.lookupNote(recordName) }
                .onSuccess { rec ->
                    if (_status.value.openId != openId) return@onSuccess
                    _status.update { it.copy(loading = false) }
                    // Only resurrect the draft against the exact version it
                    // was typed on; otherwise the splice would silently
                    // revert whatever changed in between.
                    load(rec, null, draft.takeIf { rec.recordChangeTag == tag })
                }
                .onFailure {
                    Log.w(TAG, "restore: lookupNote failed", it)
                    if (_status.value.openId == openId) {
                        _status.update { s -> s.copy(loading = false, exitRequested = true) }
                    }
                }
        }
    }

    private fun load(rec: NoteRecord, folderRef: JsonElement?, draft: DraftSnapshot?) {
        record = rec
        pendingFolderRef = folderRef
        val original = decodeOriginalBody(rec)
        originalBody = original
        val spans = rec.stringField("TextDataEncrypted")?.let { b64 ->
            runCatching { NoteBodyEditor.parseAttributeRunsFromBase64(b64) }.getOrNull()
        }.orEmpty()
        attrSpans = spans

        val firstNl = original.indexOf('\n')
        val initialTitle = if (firstNl < 0) original else original.substring(0, firstNl)
        val initialBody = if (firstNl < 0) "" else original.substring(firstNl + 1)

        if (draft != null) {
            titleDraft = draft.title
            bodyDraft = TextFieldValue(
                draft.body,
                TextRange(
                    draft.selectionStart.coerceIn(0, draft.body.length),
                    draft.selectionEnd.coerceIn(0, draft.body.length),
                ),
            )
            spansDraft = draft.spans.map { it.toAttrSpan() }
            viewMode = draft.viewMode
            prevFullBody = fullBodyForSave
        } else {
            titleDraft = initialTitle
            bodyDraft = TextFieldValue(initialBody)
            spansDraft = spans
            prevFullBody = original
            // attrSpans cover the FULL visible text including the title line
            // (which we render separately in the app bar). Trim the title's
            // worth of spans off the front so styleAt indexes align with
            // `initialBody`.
            val skip = if (original.contains('\n')) initialTitle.length + 1 else initialTitle.length
            val hasRichFormatting = attrSpansAfter(spans, skip).any {
                it.style != NoteBodyEditor.ParagraphStyle.BODY &&
                    it.style != NoteBodyEditor.ParagraphStyle.TITLE
            }
            viewMode = hasRichFormatting && initialBody.isNotEmpty()
        }
        persistIdentity()
        persistDraft()
        fetchAttachments()
    }

    private fun fetchAttachments() {
        val recordNames = bodyAttrSpans().mapNotNull { it.attachmentRecordName }.toSet()
        if (recordNames.all { it in _attachments.value }) return
        attachmentsJob?.cancel()
        attachmentsJob = viewModelScope.launch {
            val fetcher: suspend (String) -> NoteRecord? = { rn ->
                runCatchingCancellable { repository.lookupNote(rn) }.getOrNull()
            }
            val assetFetcher: suspend (String) -> ByteArray? = { url ->
                runCatchingCancellable { repository.fetchAssetBytes(url) }.getOrNull()
            }
            for (rn in recordNames) {
                if (_attachments.value.containsKey(rn)) continue
                val rec = fetcher(rn) ?: continue
                val content = runCatchingCancellable { decodeAttachment(rec, assetFetcher, fetcher) }
                    .onFailure { Log.w(TAG, "decodeAttachment failed for $rn", it) }
                    .getOrNull()
                if (content != null) {
                    _attachments.value = _attachments.value + (rn to content)
                }
            }
        }
    }

    // ----- Editing -----

    fun onTitleChange(value: String) {
        titleDraft = value.replace("\n", "")
        syncSpans()
    }

    fun onBodyChange(value: TextFieldValue) {
        bodyDraft = value
        syncSpans()
    }

    /** Toolbar / link-dialog span mutations (full-body coordinates). */
    fun onSpansChange(spans: List<NoteBodyEditor.AttrSpan>) {
        spansDraft = spans
        persistDraft()
    }

    fun enterEditMode() {
        viewMode = false
        persistDraft()
    }

    /** Keep [spansDraft] aligned with the text after a title/body edit. */
    private fun syncSpans() {
        val full = fullBodyForSave
        if (full != prevFullBody) {
            spansDraft = syncSpansToText(prevFullBody, spansDraft, full)
            prevFullBody = full
        }
        persistDraft()
    }

    /** Live body-only spans (mirrors any draft mutation). */
    fun bodyAttrSpans(): List<NoteBodyEditor.AttrSpan> = attrSpansAfter(spansDraft, titlePrefixSkip)

    /** Tap on a checkbox in the formatted view; auto-saves so check state persists. */
    fun toggleCheckbox(bodyParagraphStart: Int) {
        val fullPos = bodyParagraphStart + titlePrefixSkip
        spansDraft = NoteBodyEditor.toggleCheckbox(fullBodyForSave, spansDraft, fullPos)
        persistDraft()
        save(alsoExit = false, isPureAppendOverride = false)
    }

    // ----- Save / delete -----

    /**
     * Save the draft. [alsoExit] = save and navigate back atomically (the
     * navigation happens in the save's success path). Note: the UI always
     * passes the current spans, so the splice path (setBodyBase64) is used
     * even for pure appends — same as before the ViewModel refactor.
     */
    fun save(alsoExit: Boolean, isPureAppendOverride: Boolean? = null) {
        val rec = record ?: return
        val openId = _status.value.openId ?: return
        val newBody = fullBodyForSave
        val pureAppend = isPureAppendOverride ?: isPureAppend
        val spans = spansDraft
        val folderRef = pendingFolderRef
        _status.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val updated = repository.saveNote(rec, folderRef, newBody, pureAppend, spans)
                // Defensive state update: the save may complete AFTER the user
                // has navigated away (e.g. they tapped back while a save was in
                // flight). Only touch the editor if it still shows the same
                // note. Match by recordName for both real notes (CloudKit ID)
                // and drafts (the local "draft-<uuid>" we minted on FAB tap) —
                // this disambiguates multiple drafts in flight. The list cache
                // was already patched by the repository either way.
                val stillHere = _status.value.openId == openId &&
                    record?.recordName == rec.recordName
                if (stillHere) {
                    if (alsoExit) {
                        _status.update { it.copy(exitRequested = true) }
                    } else {
                        load(updated, null, draft = null)
                    }
                }
                _status.update { it.copy(lastSavedAtMs = clock()) }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "save failed", e)
                _status.update { it.copy(fatalError = userMessage(e)) }
            } finally {
                _status.update { it.copy(saving = false) }
            }
        }
    }

    /** Lifecycle ON_PAUSE auto-save (best effort, not navigating). */
    fun autoSave() {
        if (isModified && !_status.value.saving) save(alsoExit = false)
    }

    fun delete() {
        val rec = record ?: return
        val openId = _status.value.openId ?: return
        // Draft (never saved to iCloud): just discard locally, no network
        // call needed.
        if (rec.recordChangeTag == null) {
            _status.update { it.copy(exitRequested = true) }
            return
        }
        _status.update { it.copy(deleting = true) }
        viewModelScope.launch {
            runCatchingCancellable { repository.deleteNote(rec) }
                .onSuccess {
                    if (_status.value.openId == openId) {
                        _status.update { it.copy(deleting = false, exitRequested = true) }
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "delete failed", e)
                    _status.update { it.copy(deleting = false, fatalError = userMessage(e)) }
                }
        }
    }

    /** The screen navigated away; forget the open note. */
    fun close() {
        attachmentsJob?.cancel()
        _status.value = Status()
        record = null
        pendingFolderRef = null
        savedState.remove<String>(KEY_OPEN_ID)
        savedState.remove<String>(KEY_RECORD_NAME)
        savedState.remove<String>(KEY_RECORD_TAG)
        savedState.remove<String>(KEY_FOLDER_REF)
        savedState.remove<String>(KEY_DRAFT)
    }

    // ----- Saved state -----

    private fun persistIdentity() {
        val rec = record ?: return
        savedState[KEY_OPEN_ID] = _status.value.openId
        savedState[KEY_RECORD_NAME] = rec.recordName
        savedState[KEY_RECORD_TAG] = rec.recordChangeTag
        savedState[KEY_FOLDER_REF] = pendingFolderRef?.toString()
    }

    private fun persistDraft() {
        if (record == null) return
        val body = bodyDraft.text
        // Keep well clear of the Binder transaction limit: an enormous note
        // is not worth a TransactionTooLargeException on every onStop.
        if (body.length + titleDraft.length > MAX_SAVED_DRAFT_CHARS) {
            savedState.remove<String>(KEY_DRAFT)
            return
        }
        savedState[KEY_DRAFT] = DraftSnapshot(
            title = titleDraft,
            body = body,
            selectionStart = bodyDraft.selection.start,
            selectionEnd = bodyDraft.selection.end,
            spans = spansDraft.map { SavedSpan.of(it) },
            viewMode = viewMode,
        ).encode()
    }

    companion object {
        internal const val KEY_OPEN_ID = "detail.openId"
        internal const val KEY_RECORD_NAME = "detail.recordName"
        internal const val KEY_RECORD_TAG = "detail.recordChangeTag"
        internal const val KEY_FOLDER_REF = "detail.pendingFolderRef"
        internal const val KEY_DRAFT = "detail.draft"
        private const val MAX_SAVED_DRAFT_CHARS = 100_000

        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return viewModelFactory {
                initializer {
                    NoteDetailViewModel(AppGraph.notesRepository(app), createSavedStateHandle())
                }
            }
        }
    }
}

/** Title + body → the note's full visible text (first line == title). */
internal fun assembleFullBody(title: String, body: String): String = when {
    title.isEmpty() && body.isEmpty() -> ""
    title.isEmpty() -> body
    body.isEmpty() -> title
    else -> "$title\n$body"
}

/** Visible text of [record]'s body; "" when it can't be decoded. */
internal fun decodeOriginalBody(record: NoteRecord): String =
    record.stringField("TextDataEncrypted")?.let { b64 ->
        runCatching { NoteBodyEditor.readVisibleTextFromBase64(b64) }.getOrNull()
            ?: runCatching { NoteBodyEditor.readTextFromBase64(b64) }.getOrNull()
    }.orEmpty()
