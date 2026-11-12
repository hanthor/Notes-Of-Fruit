package com.example.applenotes.automation

import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.extractReferenceRecordName
import com.example.applenotes.data.DEFAULT_FOLDER_RECORD_NAME
import com.example.applenotes.data.DRAFT_RECORD_PREFIX
import com.example.applenotes.data.NoteBody
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.data.TRASH_RECORD_NAME
import com.example.applenotes.data.decodeNoteBody
import com.example.applenotes.data.runCatchingCancellable
import com.example.applenotes.data.userMessage
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.ui.common.UiText
import com.example.applenotes.ui.detail.assembleFullBody

/** What an automation call reports back through `result_pending_intent`. */
internal data class AutomationResult(val success: Boolean, val recordName: String? = null, val error: String? = null) {
    companion object {
        fun ok(recordName: String?) = AutomationResult(true, recordName)
        fun fail(error: String) = AutomationResult(false, error = error)
    }
}

/**
 * Runs automation requests against [NotesRepository]. Writes go through
 * [NotesRepository.saveNote], the same path the editor uses (draft create
 * for CREATE_NOTE, the incremental modify path for APPEND_TO_NOTE). There is
 * deliberately no delete or overwrite.
 */
internal class AutomationProcessor(
    private val gate: AutomationGate,
    private val repository: NotesRepository,
    /**
     * Turns a [userMessage] into the result's error text. The receiver
     * resolves string resources; tests see only [UiText.Plain] text.
     */
    private val describe: (UiText) -> String = { (it as? UiText.Plain)?.value ?: it.toString() },
) {
    /** Gate, validate and run one request. Never throws (except cancellation). */
    suspend fun handle(action: String?, extras: AutomationExtras): AutomationResult {
        if (!gate.isEnabled()) return AutomationResult.fail(DISABLED)
        val request = when (val p = parseAutomationRequest(action, extras)) {
            is Parsed.Invalid -> return AutomationResult.fail(p.error)
            is Parsed.Ok -> p.request
        }
        return runCatchingCancellable { execute(request) }
            // Typed iCloud errors get the same friendly text as the UI (raw
            // server bodies never go back to the calling app).
            .getOrElse { AutomationResult.fail(describe(userMessage(it))) }
    }

    private suspend fun execute(request: AutomationRequest): AutomationResult {
        ensureSignedIn()
        return when (request) {
            is AutomationRequest.Create -> create(request)
            is AutomationRequest.Append -> append(request)
            is AutomationRequest.Open -> AutomationResult.ok(resolve(request.recordName, request.title))
        }
    }

    private suspend fun ensureSignedIn() {
        if (repository.isSignedIn) return
        if (!repository.hasSavedLogin()) error("Not signed in. Open Notes of Fruit and sign in first.")
        repository.signIn()
    }

    private suspend fun create(r: AutomationRequest.Create): AutomationResult {
        val folderRecordName = r.folderName?.let { findFolder(it) }
        val folderRef = repository.folderRefForNewNote(folderRecordName ?: DEFAULT_FOLDER_RECORD_NAME)
        if (folderRecordName != null && extractReferenceRecordName(folderRef) != folderRecordName) {
            // folderRefForNewNote copies the reference from a note already in
            // the folder; an empty folder can't be targeted yet.
            error("Folder \"${r.folderName}\" has no notes yet, so a note can't be created in it")
        }
        val draft = NoteRecord(
            recordName = DRAFT_RECORD_PREFIX + java.util.UUID.randomUUID().toString(),
            recordType = "Note",
            recordChangeTag = null,
            rawFields = emptyMap(),
        )
        val saved = repository.saveNote(
            record = draft,
            pendingFolderRef = folderRef,
            newBody = assembleFullBody(r.title, r.text),
            isPureAppend = false,
            newSpans = null,
        )
        return AutomationResult.ok(saved.recordName)
    }

    private suspend fun append(r: AutomationRequest.Append): AutomationResult {
        val recordName = resolve(r.recordName, r.title)
        val record = repository.lookupNote(recordName)
        if (record.rawFields["Folder"]?.let { extractReferenceRecordName(it) } == TRASH_RECORD_NAME) {
            error("That note is in Recently Deleted")
        }
        val body = decodeNoteBody(record)
        if (body !is NoteBody.Editable) error("That note can't be edited by this app (unsupported format)")
        val (newBody, newSpans) = appendPlainText(body.text, body.spans, r.text)
        val saved = repository.saveNote(
            record = record,
            pendingFolderRef = null,
            newBody = newBody,
            isPureAppend = true,
            newSpans = newSpans,
        )
        return AutomationResult.ok(saved.recordName)
    }

    private fun findFolder(name: String): String {
        val folders = repository.folders.value.filter { !it.isTrash }
        val match = folders.filter { it.displayName?.equals(name, ignoreCase = true) == true }
        return when {
            match.size == 1 -> match[0].recordName
            match.size > 1 -> error("More than one folder is called \"$name\"")
            // The default folder may have no display name; it is "Notes" in Apple's UI.
            name.equals("Notes", ignoreCase = true) && folders.any { it.recordName == DEFAULT_FOLDER_RECORD_NAME } ->
                DEFAULT_FOLDER_RECORD_NAME
            else -> error("No folder called \"$name\"")
        }
    }

    /** recordName as given, or the single non-deleted note whose title is exactly [title]. */
    private suspend fun resolve(recordName: String?, title: String?): String {
        if (recordName != null) return recordName
        val wanted = title!!.trim()
        fun matches() = repository.notes.value.filter {
            it.folderRecordName != TRASH_RECORD_NAME && it.title?.trim() == wanted
        }
        var found = matches()
        if (found.isEmpty()) {
            repository.refresh()
            found = matches()
        }
        return when (found.size) {
            0 -> error("No note titled \"$wanted\"")
            1 -> found[0].recordName
            else -> error("${found.size} notes are titled \"$wanted\"; use record_name")
        }
    }

    companion object {
        const val DISABLED = "Automation is turned off. Enable it in Notes of Fruit → Settings."
    }
}

/**
 * [original] + [text] as new plain paragraph(s) at the end, with spans:
 * existing formatting is kept, the paragraph break carries the last
 * paragraph's style (as the editor does) and the appended text is plain Body.
 */
internal fun appendPlainText(
    original: String,
    spans: List<NoteBodyEditor.AttrSpan>,
    text: String,
): Pair<String, List<NoteBodyEditor.AttrSpan>> {
    val sep = if (original.isEmpty() || original.endsWith('\n')) "" else "\n"
    val newBody = original + sep + text
    val perChar = NoteBodyEditor.expandSpansToChars(original, spans).toMutableList()
    if (sep.isNotEmpty()) {
        val last = perChar.lastOrNull()
        perChar += NoteBodyEditor.AttrSpan(
            length = 1,
            style = last?.style ?: NoteBodyEditor.ParagraphStyle.BODY,
            checked = last?.checked ?: false,
        )
    }
    repeat(text.length) { perChar += NoteBodyEditor.AttrSpan(length = 1, style = NoteBodyEditor.ParagraphStyle.BODY) }
    return newBody to NoteBodyEditor.compactCharsToSpans(perChar.toTypedArray())
}
