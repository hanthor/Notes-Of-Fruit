package com.example.applenotes.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.applenotes.log.AppLog
import com.example.applenotes.auth.AppleNotesSession
import com.example.applenotes.auth.CookieReader
import com.example.applenotes.auth.DeviceIdentity
import com.example.applenotes.auth.ICloudSession
import com.example.applenotes.client.AppleNotesClient
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.proto.NoteAppender
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.ui.computeTitleAndSnippet
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "AppleNotesDebug"

/**
 * ADB-driven debug receiver. Lets a closed-loop test (or just plain debugging
 * from a terminal) drive the app's iCloud Notes operations without going
 * through the UI.
 *
 * DEBUG BUILDS ONLY. This file lives in `src/debug` and is registered by
 * `src/debug/AndroidManifest.xml`, so it is never compiled into, or declared
 * by, a release APK. See that manifest for why the receiver is exported but
 * guarded by `android.permission.DUMP` (adb shell can send to it; other apps
 * on the device cannot).
 *
 * Triggered by (debug builds use the `.debug` application ID suffix):
 *   adb shell am broadcast -p io.github.hanthor.notesoffruit.debug -a com.example.applenotes.<ACTION> [extras...]
 *
 * Actions:
 *   LIST                                    — fetchRecents, log every note
 *   LOOKUP --es recordName <name>           — lookupNote on a specific record
 *   LOOKUP_BY_TITLE --es title <substring>  — list, find first match, look it up
 *   APPEND --es recordName <name> --es text <chars>           — append text to a specific record
 *   APPEND_BY_TITLE --es title <sub> --es text <chars>        — list+match+append
 *   SET_BODY_BY_TITLE --es title <sub> --es body <text>       — replace a note's body
 *   DELETE_BY_TITLE --es title <sub>                          — delete (moves to Recently Deleted)
 *   DUMP_BY_TITLE --es title <sub>          — log the body proto (base64 + decoded views)
 *   DUMP_RECORD --es recordName <name>      — log every string field of a record
 *   CREATE --es title <t> --es body <b>     — create a note in an existing note's folder
 *
 * Any broadcast also switches on `AppLog.verbose` (wire-shape dumps such as
 * proto summaries and response bodies, off by default) for the rest of the
 * process; pass `--ez verbose false` to switch it off again.
 *
 * Each operation runs on a coroutine; [goAsync] keeps the broadcast (and so
 * the process) alive until it finishes. Android still enforces the broadcast
 * timeout (about 60 s for a background broadcast), so a very slow network
 * operation can still be cut short.
 *
 * Every step logs under the "AppleNotesDebug" tag with a stable prefix so a
 * driver script can pull `adb logcat -s AppleNotesDebug:* AppleNotesClient:* AppleNotesAppender:*`
 * and parse outcomes.
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // A debug session driven from adb wants wire-shape dumps (proto
        // summaries, substring trees, response bodies): turn on AppLog's
        // verbose level for the rest of this process. `--ez verbose false`
        // turns it back off.
        AppLog.verbose = intent.getBooleanExtra("verbose", true)
        val extras = intent.extras?.keySet()?.associateWith { intent.extras?.get(it) } ?: emptyMap()
        AppLog.i(TAG) { "RECV $action extras=$extras" }
        // Optional `strategy` extra to switch the appender's write pattern.
        intent.getStringExtra("strategy")?.let { stratStr ->
            val strat = runCatching { NoteAppender.Strategy.valueOf(stratStr) }.getOrNull()
            if (strat != null) {
                NoteAppender.setStrategy(strat)
                AppLog.i(TAG) { "Strategy set to $strat" }
            }
        }
        when (action) {
            ACTION_LIST -> launchOp("LIST") { client, _ ->
                val notes = client.fetchRecents(200)
                AppLog.i(TAG) { "LIST_OK count=${notes.size}" }
                notes
            }
            ACTION_LOOKUP -> {
                val recordName = intent.getStringExtra("recordName")
                if (recordName == null) {
                    AppLog.e(TAG, "LOOKUP missing recordName")
                    return
                }
                launchOp("LOOKUP") { client, _ ->
                    client.lookupNote(recordName)
                    AppLog.i(TAG) { "LOOKUP_OK $recordName" }
                }
            }
            ACTION_LOOKUP_BY_TITLE -> {
                val title = intent.getStringExtra("title")
                if (title == null) {
                    AppLog.e(TAG, "LOOKUP_BY_TITLE missing title")
                    return
                }
                launchOp("LOOKUP_BY_TITLE") { client, _ ->
                    val match = findByTitle(client, title)
                    if (match == null) {
                        AppLog.w(TAG, "LOOKUP_BY_TITLE_NOT_FOUND title='$title'")
                    } else {
                        AppLog.i(TAG) { "LOOKUP_BY_TITLE_FOUND '$title' -> ${match.recordName}" }
                        client.lookupNote(match.recordName)
                        AppLog.i(TAG) { "LOOKUP_BY_TITLE_OK" }
                    }
                }
            }
            ACTION_APPEND -> {
                val recordName = intent.getStringExtra("recordName")
                val text = intent.getStringExtra("text")
                if (recordName == null || text == null) {
                    AppLog.e(TAG, "APPEND missing recordName or text (got rec=$recordName text=$text)")
                    return
                }
                launchOp("APPEND") { client, session ->
                    appendToNote(context, client, recordName, text)
                }
            }
            ACTION_APPEND_BY_TITLE -> {
                val title = intent.getStringExtra("title")
                val text = intent.getStringExtra("text")
                if (title == null || text == null) {
                    AppLog.e(TAG, "APPEND_BY_TITLE missing title or text")
                    return
                }
                launchOp("APPEND_BY_TITLE") { client, session ->
                    val match = findByTitle(client, title)
                    if (match == null) {
                        AppLog.w(TAG, "APPEND_BY_TITLE_NOT_FOUND title='$title'")
                    } else {
                        AppLog.i(TAG) { "APPEND_BY_TITLE_FOUND '$title' -> ${match.recordName}" }
                        appendToNote(context, client, match.recordName, text)
                    }
                }
            }
            ACTION_DELETE_BY_TITLE -> {
                val title = intent.getStringExtra("title")
                if (title == null) {
                    AppLog.e(TAG, "DELETE_BY_TITLE missing title")
                    return
                }
                launchOp("DELETE_BY_TITLE") { client, _ ->
                    val match = findByTitle(client, title)
                    if (match == null) {
                        AppLog.w(TAG, "DELETE_BY_TITLE_NOT_FOUND title='$title'")
                    } else {
                        AppLog.i(TAG) { "DELETE_BY_TITLE_FOUND '$title' -> ${match.recordName}" }
                        val record = client.lookupNote(match.recordName)
                        val tag = record.recordChangeTag ?: error("no tag")
                        client.deleteNote(match.recordName, tag)
                        AppLog.i(TAG) { "DELETE_BY_TITLE_OK ${match.recordName}" }
                    }
                }
            }
            ACTION_SET_BODY_BY_TITLE -> {
                val title = intent.getStringExtra("title")
                val body = intent.getStringExtra("body")
                if (title == null || body == null) {
                    AppLog.e(TAG, "SET_BODY_BY_TITLE missing title or body")
                    return
                }
                launchOp("SET_BODY_BY_TITLE") { client, _ ->
                    val match = findByTitle(client, title)
                    if (match == null) {
                        AppLog.w(TAG, "SET_BODY_BY_TITLE_NOT_FOUND title='$title'")
                    } else {
                        AppLog.i(TAG) { "SET_BODY_BY_TITLE_FOUND '$title' -> ${match.recordName}" }
                        setBodyOnNote(context, client, match.recordName, body)
                    }
                }
            }
            ACTION_DUMP_RECORD -> {
                val recordName = intent.getStringExtra("recordName")
                if (recordName == null) {
                    AppLog.e(TAG, "DUMP_RECORD missing recordName")
                    return
                }
                launchOp("DUMP_RECORD") { client, _ ->
                    val record = client.lookupNote(recordName)
                    AppLog.i(TAG) { "DUMP_RECORD type=${record.recordType} tag=${record.recordChangeTag}" }
                    for ((name, _) in record.rawFields) {
                        val s = record.stringField(name)
                        if (s == null) continue
                        AppLog.i(TAG) { "FIELD $name (b64.len=${s.length})" }
                        val chunkSize = 800
                        val chunks = (s.length + chunkSize - 1) / chunkSize
                        for (i in 0 until chunks) {
                            val start = i * chunkSize
                            val end = minOf(start + chunkSize, s.length)
                            AppLog.i(TAG) { "FIELD_B64 $name [$i/$chunks] ${s.substring(start, end)}" }
                        }
                    }
                }
            }
            ACTION_DUMP_BY_TITLE -> {
                val title = intent.getStringExtra("title")
                if (title == null) {
                    AppLog.e(TAG, "DUMP_BY_TITLE missing title")
                    return
                }
                launchOp("DUMP_BY_TITLE") { client, _ ->
                    val match = findByTitle(client, title)
                    if (match == null) {
                        AppLog.w(TAG, "DUMP_BY_TITLE_NOT_FOUND title='$title'")
                    } else {
                        AppLog.i(TAG) { "DUMP_BY_TITLE_FOUND '$title' -> ${match.recordName}" }
                        val record = client.lookupNote(match.recordName)
                        val b64 = record.stringField("TextDataEncrypted")
                        if (b64 == null) {
                            AppLog.w(TAG, "DUMP_BY_TITLE_NO_BODY")
                        } else {
                            AppLog.i(TAG) { "DUMP_KIND ${NoteBodyEditor.probeBase64(b64)}" }
                            // Log b64 in 1KB chunks so logcat doesn't truncate.
                            val chunkSize = 800
                            val chunks = (b64.length + chunkSize - 1) / chunkSize
                            AppLog.i(TAG) { "DUMP_B64_BEGIN total=${b64.length} chunks=$chunks" }
                            for (i in 0 until chunks) {
                                val start = i * chunkSize
                                val end = minOf(start + chunkSize, b64.length)
                                AppLog.i(TAG) { "DUMP_B64[$i/$chunks] ${b64.substring(start, end)}" }
                            }
                            AppLog.i(TAG) { "DUMP_B64_END" }
                            AppLog.i(TAG) { "DUMP_SUMMARY ${NoteBodyEditor.summarizeBase64(b64)}" }
                            // describeBase64 is multi-line; split for logcat.
                            val desc = NoteBodyEditor.describeBase64(b64)
                            AppLog.i(TAG) { "DUMP_DESCRIBE_BEGIN" }
                            for (line in desc.lines()) AppLog.i(TAG) { "DESC $line" }
                            AppLog.i(TAG) { "DUMP_DESCRIBE_END" }
                            val ar = NoteBodyEditor.dumpAttributeRunsBase64(b64)
                            AppLog.i(TAG) { "DUMP_ATTRRUNS_BEGIN" }
                            for (line in ar.lines()) AppLog.i(TAG) { "AR $line" }
                            AppLog.i(TAG) { "DUMP_ATTRRUNS_END" }
                            val tree = NoteBodyEditor.dumpSubstringTreeBase64(b64)
                            AppLog.i(TAG) { "DUMP_TREE_BEGIN" }
                            for (line in tree.lines()) AppLog.i(TAG) { "TREE $line" }
                            AppLog.i(TAG) { "DUMP_TREE_END" }
                        }
                    }
                }
            }
            ACTION_CREATE -> {
                val title = intent.getStringExtra("title") ?: ""
                val body = intent.getStringExtra("body") ?: ""
                launchOp("CREATE") { client, _ ->
                    val uuid = DeviceIdentity.getOrCreate(context)
                    // Find a folder reference from any existing note.
                    val anyNote = client.fetchRecents(50).firstOrNull()
                        ?: error("no existing notes to copy folder reference from")
                    val sample = client.lookupNote(anyNote.recordName)
                    val folderRef = sample.rawFields["Folder"]
                        ?: error("sample note has no Folder field")
                    val created = client.createNote(title, body, folderRef, uuid)
                    AppLog.i(TAG) { "CREATE_OK ${created.recordName}" }
                }
            }
            else -> AppLog.w(TAG, "RECV unknown action=$action")
        }
    }

    private fun launchOp(label: String, op: suspend (AppleNotesClient, ICloudSession) -> Unit) {
        // Without goAsync() the system treats the broadcast as handled as soon
        // as onReceive returns and may kill the (otherwise idle) process while
        // the coroutine is still mid-write. finish() releases it.
        val pending = goAsync()
        scope.launch {
            try {
                val session = bootstrap()
                val client = AppleNotesClient(httpClient, session)
                op(client, session)
                AppLog.i(TAG) { "$label DONE" }
            } catch (e: Throwable) {
                AppLog.e(TAG, "$label FAILED ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun bootstrap(): ICloudSession {
        val cookieReader = CookieReader()
        return AppleNotesSession(httpClient, cookieReader).bootstrap().getOrThrow()
    }

    private suspend fun findByTitle(client: AppleNotesClient, title: String): NoteSummary? {
        val notes = client.fetchRecents(200)
        return notes.firstOrNull { !it.deleted && (it.title?.contains(title, ignoreCase = true) == true) }
    }

    private suspend fun appendToNote(
        context: Context,
        client: AppleNotesClient,
        recordName: String,
        text: String,
    ) {
        val uuid = DeviceIdentity.getOrCreate(context)
        val record = client.lookupNote(recordName)
        val tag = record.recordChangeTag ?: error("no recordChangeTag on $recordName")
        val textB64 = record.stringField("TextDataEncrypted") ?: error("no body on $recordName")
        val originalBody = NoteBodyEditor.readVisibleTextFromBase64(textB64)
            ?: NoteBodyEditor.readTextFromBase64(textB64).orEmpty()
        val now = System.currentTimeMillis() / 1000L
        val newB64 = NoteAppender.appendBase64(textB64, uuid, text, now)
        val newBody = originalBody + text
        val (oldTitle, oldSnippet) = computeTitleAndSnippet(originalBody)
        val (newTitle, newSnippet) = computeTitleAndSnippet(newBody)
        AppLog.i(TAG) {
            "APPEND rec=$recordName text='${text.take(40)}' " +
                "title '${oldTitle.take(30)}'->'${newTitle.take(30)}' " +
                "snippet '${oldSnippet.take(30)}'->'${newSnippet.take(30)}'"
        }
        client.modifyNoteBody(
            recordName, tag, newB64,
            // Always send Title and Snippet, even when unchanged. Mac's notesync
            // appears to use field-update signals (not value-diff) to decide
            // whether to refresh local cache; without these, the local cache
            // could keep its stale CRDT view alongside our update => duplicated
            // body in the UI.
            newTitle = newTitle,
            newSnippet = newSnippet,
            replicaVersionPassThroughB64 = record.stringField("ReplicaIDToNotesVersionDataEncrypted"),
        )
        AppLog.i(TAG) { "APPEND_OK rec=$recordName" }
    }

    /**
     * Replace the note body with [newBody] entirely via the tombstone-rewrite
     * strategy. Used for non-pure-append edits (mid-string changes, deletions).
     */
    private suspend fun setBodyOnNote(
        context: Context,
        client: AppleNotesClient,
        recordName: String,
        newBody: String,
    ) {
        val uuid = DeviceIdentity.getOrCreate(context)
        val record = client.lookupNote(recordName)
        val tag = record.recordChangeTag ?: error("no recordChangeTag on $recordName")
        val textB64 = record.stringField("TextDataEncrypted") ?: error("no body on $recordName")
        val originalBody = NoteBodyEditor.readVisibleTextFromBase64(textB64)
            ?: NoteBodyEditor.readTextFromBase64(textB64).orEmpty()
        val now = System.currentTimeMillis() / 1000L
        val newB64 = NoteAppender.setBodyBase64(textB64, uuid, newBody, now)
        val (oldTitle, oldSnippet) = computeTitleAndSnippet(originalBody)
        val (newTitle, newSnippet) = computeTitleAndSnippet(newBody)
        AppLog.i(TAG) {
            "SET_BODY rec=$recordName bodyLen=${newBody.length} " +
                "title '${oldTitle.take(30)}'->'${newTitle.take(30)}' " +
                "snippet '${oldSnippet.take(30)}'->'${newSnippet.take(30)}'"
        }
        client.modifyNoteBody(
            recordName, tag, newB64,
            newTitle = newTitle.takeIf { it != oldTitle },
            newSnippet = newSnippet.takeIf { it != oldSnippet },
            replicaVersionPassThroughB64 = record.stringField("ReplicaIDToNotesVersionDataEncrypted"),
        )
        AppLog.i(TAG) { "SET_BODY_OK rec=$recordName" }
    }

    companion object {
        private const val ACTION_LIST = "com.example.applenotes.LIST"
        private const val ACTION_LOOKUP = "com.example.applenotes.LOOKUP"
        private const val ACTION_LOOKUP_BY_TITLE = "com.example.applenotes.LOOKUP_BY_TITLE"
        private const val ACTION_APPEND = "com.example.applenotes.APPEND"
        private const val ACTION_APPEND_BY_TITLE = "com.example.applenotes.APPEND_BY_TITLE"
        private const val ACTION_SET_BODY_BY_TITLE = "com.example.applenotes.SET_BODY_BY_TITLE"
        private const val ACTION_DELETE_BY_TITLE = "com.example.applenotes.DELETE_BY_TITLE"
        private const val ACTION_DUMP_BY_TITLE = "com.example.applenotes.DUMP_BY_TITLE"
        private const val ACTION_DUMP_RECORD = "com.example.applenotes.DUMP_RECORD"
        private const val ACTION_CREATE = "com.example.applenotes.CREATE"

        private val httpClient by lazy {
            HttpClient(OkHttp) { expectSuccess = false }
        }
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }
}
