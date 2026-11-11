package com.example.applenotes.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.applenotes.auth.AppleNotesSession
import com.example.applenotes.auth.CookieReader
import com.example.applenotes.auth.DeviceIdentity
import com.example.applenotes.auth.ICloudSession
import com.example.applenotes.client.AppleNotesClient
import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.data.DEFAULT_FOLDER_RECORD_NAME
import com.example.applenotes.data.TRASH_RECORD_NAME
import com.example.applenotes.proto.NoteAppender
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.ui.common.AlphaWarningDialog
import com.example.applenotes.ui.common.CenteredSpinner
import com.example.applenotes.ui.common.ErrorScreen
import com.example.applenotes.ui.detail.NoteDetailScreen
import com.example.applenotes.ui.list.NotesListScreen
import com.example.applenotes.ui.login.LoginScreen
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.launch

private const val TAG = "AppleNotesUi"

private sealed interface ScreenState {
    data object Splash : ScreenState
    data object Login : ScreenState
    data class Loading(val msg: String) : ScreenState
    data class Error(val msg: String) : ScreenState
    data class NotesList(
        val session: ICloudSession,
        val notes: List<NoteSummary>,
        val folders: List<FolderSummary>,
        /**
         * Currently-selected folder recordName. Null = "All notes" pseudo-folder
         * (every note across user-visible folders). Use [TRASH_RECORD_NAME] to
         * show Recently Deleted explicitly.
         */
        val selectedFolder: String? = "DefaultFolder-CloudKit",
    ) : ScreenState
    /**
     * Detail keeps the cached [notesCache] from the list view so back-nav
     * restores the list instantly without a `fetchRecents` round trip.
     * `notesCache` is updated in place when we save (replacing the just-saved
     * note's summary) so the user sees the new snippet on return.
     */
    data class Detail(
        val session: ICloudSession,
        val record: NoteRecord,
        val notesCache: List<NoteSummary>,
        val folders: List<FolderSummary>,
        val selectedFolder: String?,
        /**
         * Set when this Detail represents a brand-new note that has NOT yet
         * been written to iCloud. The first save calls `createNote` with the
         * user's typed content (title + body in one shot); subsequent saves
         * use `modifyNoteBody`. We match iCloud.com's pattern: it never does
         * empty-create-then-modify — it only creates the record once there's
         * content to write. Empty-create + modify races Mac's notesync
         * housekeeping and produces records Mac later trashes.
         *
         * When non-null, [record] is a stub (recordChangeTag = null,
         * recordName = "", empty fields).
         */
        val pendingFolderRef: kotlinx.serialization.json.JsonElement? = null,
    ) : ScreenState
}

private const val TRASH_RECORD_NAME = "TrashFolder-CloudKit"
private const val DEFAULT_FOLDER_RECORD_NAME = "DefaultFolder-CloudKit"

@Composable
fun AppleNotesApp() {
    val context = LocalContext.current
    val cookieReader = remember { CookieReader() }
    val httpClient = remember {
        HttpClient(OkHttp) {
            expectSuccess = false
        }
    }

    var state by remember { mutableStateOf<ScreenState>(ScreenState.Splash) }
    var saving by remember { mutableStateOf(false) }
    var ourReplicaUuid by remember { mutableStateOf<ByteArray?>(null) }
    var lastSavedHint by remember { mutableStateOf<String?>(null) }
    var lastSavedAtMs by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    // First-launch alpha disclaimer. Block all app behaviour behind this — we
    // don't even start the auth bootstrap until the user has acknowledged.
    var alphaAck by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        alphaAck = com.example.applenotes.AlphaWarning.isAcknowledged(context)
    }
    if (alphaAck == false) {
        AlphaWarningDialog(onAcknowledge = {
            scope.launch {
                com.example.applenotes.AlphaWarning.acknowledge(context)
                alphaAck = true
            }
        })
        return
    }
    if (alphaAck == null) {
        // Still loading the ack flag — render nothing; the splash theme is
        // still showing underneath.
        return
    }

    LaunchedEffect(Unit) {
        // Load (or generate on first run) our stable per-device replica UUID.
        // Same UUID for every note we ever edit on this install; getting a
        // fresh one every session would burn through Apple's per-note replica cap.
        val uuid = DeviceIdentity.getOrCreate(context)
        ourReplicaUuid = uuid
        Log.i(TAG, "device replica uuid=${DeviceIdentity.hexEncode(uuid)}")
    }

    suspend fun bootstrap(): ScreenState = try {
        val session = AppleNotesSession(httpClient, cookieReader).bootstrap().getOrThrow()
        val client = AppleNotesClient(httpClient, session)
        val notes = client.fetchRecents(limit = 1000)
            .filter { !it.deleted }
            .sortedByDescending { it.modificationTimestampMs ?: 0L }
        val folderIds = notes.mapNotNull { it.folderRecordName }.toSet()
        val folders = runCatching { client.lookupFolders(folderIds) }.getOrElse {
            Log.w(TAG, "lookupFolders failed; falling back to ID-only list", it)
            folderIds.map { rn ->
                FolderSummary(
                    recordName = rn,
                    displayName = null,
                    isTrash = rn == TRASH_RECORD_NAME,
                    isDefault = rn == DEFAULT_FOLDER_RECORD_NAME,
                )
            }
        }
        ScreenState.NotesList(session, notes, folders)
    } catch (e: Throwable) {
        Log.e(TAG, "bootstrap failed", e)
        ScreenState.Error(e.message ?: e.toString())
    }

    suspend fun refreshList(session: ICloudSession): List<NoteSummary> =
        AppleNotesClient(httpClient, session).fetchRecents(limit = 1000)
            .filter { !it.deleted }
            .sortedByDescending { it.modificationTimestampMs ?: 0L }

    LaunchedEffect(Unit) {
        // On launch, check for existing session cookies and try to restore.
        val cookies = cookieReader.cookieMap()
        state = if ("X-APPLE-WEBAUTH-TOKEN" in cookies) {
            ScreenState.Loading("Restoring session…").also {
                scope.launch { state = bootstrap() }
            }
        } else {
            ScreenState.Login
        }
    }

    when (val s = state) {
        ScreenState.Splash -> CenteredSpinner("Starting up…")
        ScreenState.Login -> LoginScreen(
            onCookiesArrived = {
                scope.launch {
                    state = ScreenState.Loading("Signing in…")
                    state = bootstrap()
                }
            },
        )
        is ScreenState.Loading -> CenteredSpinner(s.msg)
        is ScreenState.Error -> ErrorScreen(
            message = s.msg,
            onRetry = {
                scope.launch {
                    state = ScreenState.Loading("Retrying…")
                    state = bootstrap()
                }
            },
            onSignOut = {
                cookieReader.clearAuthCookies()
                state = ScreenState.Login
            },
        )
        is ScreenState.NotesList -> NotesListScreen(
            allNotes = s.notes,
            folders = s.folders,
            selectedFolder = s.selectedFolder,
            onSelectFolder = { folderRn ->
                state = s.copy(selectedFolder = folderRn)
            },
            onSelect = { note ->
                scope.launch {
                    state = ScreenState.Loading("Loading note…")
                    state = try {
                        val rec = AppleNotesClient(httpClient, s.session).lookupNote(note.recordName)
                        ScreenState.Detail(s.session, rec, s.notes, s.folders, s.selectedFolder)
                    } catch (e: Throwable) {
                        ScreenState.Error(e.message ?: e.toString())
                    }
                }
            },
            onRefresh = {
                scope.launch {
                    state = ScreenState.Loading("Refreshing…")
                    state = try {
                        val client = AppleNotesClient(httpClient, s.session)
                        val notes = client.fetchRecents(limit = 1000)
                            .filter { !it.deleted }
                            .sortedByDescending { it.modificationTimestampMs ?: 0L }
                        val folderIds = notes.mapNotNull { it.folderRecordName }.toSet()
                        val folders = runCatching { client.lookupFolders(folderIds) }.getOrElse { s.folders }
                        ScreenState.NotesList(s.session, notes, folders, s.selectedFolder)
                    } catch (e: Throwable) {
                        ScreenState.Error(e.message ?: e.toString())
                    }
                }
            },
            onCreateNote = {
                scope.launch {
                    val uuid = ourReplicaUuid
                    if (uuid == null) {
                        state = ScreenState.Error("Device replica UUID not loaded yet — try again in a moment.")
                        return@launch
                    }
                    state = ScreenState.Loading("Creating note…")
                    state = try {
                        val client = AppleNotesClient(httpClient, s.session)
                        // Grab a Folder CKReference from any existing note in
                        // the user-visible set. We need this BEFORE the user
                        // types anything because createNote requires a folder
                        // ref. Prefer the currently-selected folder so the
                        // new note lands where the user is browsing.
                        val sample = s.notes
                            .firstOrNull { it.folderRecordName == s.selectedFolder }
                            ?: s.notes.firstOrNull { it.folderRecordName != TRASH_RECORD_NAME }
                            ?: error("No existing note to copy folder reference from")
                        val sampleRecord = client.lookupNote(sample.recordName)
                        val folderRef = sampleRecord.rawFields["Folder"]
                            ?: error("Sample note has no Folder field")
                        // DEFERRED CREATE: do NOT call createNote yet. iCloud.com
                        // doesn't either — clicking "new note" just opens an
                        // empty editor; the server-side record only gets
                        // created on the first save with the user's actual
                        // content. Empty-create-then-modify races Mac's
                        // notesync housekeeping and produces records that
                        // Mac later trashes (we observed this: notes appearing
                        // briefly then vanishing on Mac restart).
                        //
                        // The draft gets a unique local recordName ("draft-<uuid>")
                        // so the post-save state-update can disambiguate
                        // multiple drafts in flight (back, FAB-again, type,
                        // save races back into the original Detail).
                        val draftId = "draft-" + java.util.UUID.randomUUID().toString()
                        val draftRecord = NoteRecord(
                            recordName = draftId,
                            recordType = "Note",
                            recordChangeTag = null,
                            rawFields = emptyMap(),
                        )
                        ScreenState.Detail(
                            session = s.session,
                            record = draftRecord,
                            notesCache = s.notes,
                            folders = s.folders,
                            selectedFolder = s.selectedFolder,
                            pendingFolderRef = folderRef,
                        )
                    } catch (e: Throwable) {
                        Log.e(TAG, "createNote (folder lookup) failed", e)
                        ScreenState.Error(e.message ?: e.toString())
                    }
                }
            },
            onSignOut = {
                cookieReader.clearAuthCookies()
                state = ScreenState.Login
            },
        )
        is ScreenState.Detail -> NoteDetailScreen(
            record = s.record,
            saving = saving,
            savedHint = lastSavedHint,
            lastSavedAtMs = lastSavedAtMs,
            onHintShown = { lastSavedHint = null },
            onFetchAttachment = { rn ->
                runCatching {
                    AppleNotesClient(httpClient, s.session).lookupNote(rn)
                }.getOrNull()
            },
            onFetchAsset = { url ->
                AppleNotesClient(httpClient, s.session).fetchAssetBytes(url)
            },
            onBack = {
                // Restore the cached list instantly. No network round trip —
                // `notesCache` was patched in place when we saved, so the snippet
                // for the just-edited note already reflects our changes.
                state = ScreenState.NotesList(s.session, s.notesCache, s.folders, s.selectedFolder)
            },
            onSave = { newBody, isPureAppend, alsoExit, newSpans ->
                scope.launch {
                    saving = true
                    try {
                        val uuid = ourReplicaUuid
                            ?: error("Device replica UUID not loaded yet — try again in a moment.")
                        val client = AppleNotesClient(httpClient, s.session)
                        val (newTitle, newSnippet) = computeTitleAndSnippet(newBody)

                        val updated: NoteRecord
                        val patchedCache: List<NoteSummary>

                        if (s.record.recordChangeTag == null) {
                            // DRAFT: this is the first save for a FAB-created
                            // note. Call createNote with the typed content in
                            // one shot — matching iCloud.com's pattern. The
                            // proto produced by NoteCreator already has the
                            // full content as a single substring + matching
                            // attribute_run, so Mac's notesync sees a
                            // self-consistent record on first sight.
                            val folderRef = s.pendingFolderRef
                                ?: error("Draft has no folderRef — refresh first.")
                            // Split fullBodyForSave back into title + body the
                            // same way it was assembled upstream (line 945-950
                            // in NoteDetailScreen): the first '\n' is the
                            // title/body separator. computeTitleAndSnippet's
                            // `newTitle` is derived heuristically (first
                            // non-blank line, possibly trimmed) and would
                            // misbehave under leading whitespace / leading
                            // blank lines, duplicating content into the proto.
                            val firstNlIdx = newBody.indexOf('\n')
                            val createTitle = if (firstNlIdx < 0) newBody else newBody.substring(0, firstNlIdx)
                            val createBody = if (firstNlIdx < 0) "" else newBody.substring(firstNlIdx + 1)
                            Log.i(
                                TAG,
                                "save (draft→createNote): title='${createTitle.take(30)}' " +
                                    "body.len=${createBody.length} snippet='${newSnippet.take(30)}'",
                            )
                            updated = client.createNote(
                                title = createTitle,
                                body = createBody,
                                folderRef = folderRef,
                                ourReplicaUuid = uuid,
                            )
                            // Pre-pend a fresh summary into the cache so back-nav
                            // shows the new note immediately without a refetch.
                            val folderRecordName = com.example.applenotes.client.extractReferenceRecordName(folderRef)
                            val newSummary = NoteSummary(
                                recordName = updated.recordName,
                                title = newTitle,
                                snippet = newSnippet,
                                modificationTimestampMs = System.currentTimeMillis(),
                                deleted = false,
                                folderRecordName = folderRecordName,
                            )
                            patchedCache = listOf(newSummary) + s.notesCache
                        } else {
                            // EXISTING NOTE: incremental modify path.
                            val tag = s.record.recordChangeTag!!
                            val textB64 = s.record.stringField("TextDataEncrypted")
                                ?: error("No body field on this note.")
                            val originalBody = NoteBodyEditor.readVisibleTextFromBase64(textB64)
                                ?: NoteBodyEditor.readTextFromBase64(textB64).orEmpty()
                            val now = System.currentTimeMillis() / 1000L
                            // Pure-append edits go through the cheap APPEND path
                            // (one new substring at the sentinel slot). Anything
                            // else (replace/insert/delete in the middle) goes
                            // through the splice path which mirrors iCloud.com's
                            // pattern: slot promotion + split-and-tombstone +
                            // fresh ts on tombstones + new chunks for inserts.
                            val newB64 = if (isPureAppend && newSpans == null) {
                                val appended = newBody.removePrefix(originalBody)
                                require(appended.isNotEmpty()) { "Pure-append marked but no new chars" }
                                NoteAppender.appendBase64(textB64, uuid, appended, now)
                            } else {
                                NoteAppender.setBodyBase64(textB64, uuid, newBody, now, newSpans)
                            }
                            val (oldTitle, oldSnippet) = computeTitleAndSnippet(originalBody)
                            Log.i(
                                TAG,
                                "save: title '${oldTitle.take(30)}'->'${newTitle.take(30)}' " +
                                    "snippet '${oldSnippet.take(30)}'->'${newSnippet.take(30)}'",
                            )
                            updated = client.modifyNoteBody(
                                s.record.recordName,
                                tag,
                                newB64,
                                newTitle = newTitle.takeIf { it != oldTitle },
                                newSnippet = newSnippet.takeIf { it != oldSnippet },
                                replicaVersionPassThroughB64 = s.record.stringField("ReplicaIDToNotesVersionDataEncrypted"),
                            )
                            // Patch the cached list summary for this note so when
                            // the user navigates back they see the updated
                            // snippet/title without a refetch.
                            patchedCache = s.notesCache.map { note ->
                                if (note.recordName == s.record.recordName) {
                                    note.copy(
                                        title = newTitle,
                                        snippet = newSnippet,
                                        modificationTimestampMs = System.currentTimeMillis(),
                                    )
                                } else note
                            }
                        }
                        // Defensive state update: the save coroutine may complete
                        // AFTER the user has navigated away (e.g. they tapped back,
                        // we triggered a save+exit, save took 500ms, user is now
                        // looking at the list). If the user is still on this same
                        // Detail, refresh with the updated record. If they've
                        // moved to NotesList, just patch its cache silently. Else
                        // (Error, Splash, Login, different Detail), leave alone.
                        //
                        // Match by recordName for both real notes (CloudKit ID)
                        // and drafts (the local "draft-<uuid>" we minted on FAB
                        // tap). This disambiguates if the user has multiple
                        // drafts in flight: each has its own draft ID, the
                        // save callback closure captured the originating
                        // draft's ID via `s`, and only the matching Detail
                        // gets replaced.
                        val current = state
                        val sameDetail = current is ScreenState.Detail &&
                            current.record.recordName == s.record.recordName
                        state = when {
                            sameDetail && current is ScreenState.Detail ->
                                if (alsoExit) ScreenState.NotesList(current.session, patchedCache, current.folders, current.selectedFolder)
                                else ScreenState.Detail(current.session, updated, patchedCache, current.folders, current.selectedFolder, pendingFolderRef = null)
                            current is ScreenState.NotesList ->
                                current.copy(notes = patchedCache)
                            else -> current
                        }
                        lastSavedAtMs = System.currentTimeMillis()
                    } catch (e: Throwable) {
                        Log.e(TAG, "save failed", e)
                        state = ScreenState.Error(e.message ?: e.toString())
                    } finally {
                        saving = false
                    }
                }
            },
            onDelete = {
                scope.launch {
                    // Draft (never saved to iCloud): just discard locally,
                    // no network call needed.
                    if (s.record.recordChangeTag == null) {
                        state = ScreenState.NotesList(s.session, s.notesCache, s.folders, s.selectedFolder)
                        return@launch
                    }
                    state = ScreenState.Loading("Deleting…")
                    state = try {
                        val tag = s.record.recordChangeTag!!
                        AppleNotesClient(httpClient, s.session).deleteNote(s.record.recordName, tag)
                        // Drop the deleted note from the cache and return to list.
                        ScreenState.NotesList(
                            s.session,
                            s.notesCache.filter { it.recordName != s.record.recordName },
                            s.folders,
                            s.selectedFolder,
                        )
                    } catch (e: Throwable) {
                        Log.e(TAG, "delete failed", e)
                        ScreenState.Error(e.message ?: e.toString())
                    }
                }
            },
        )
    }
}
