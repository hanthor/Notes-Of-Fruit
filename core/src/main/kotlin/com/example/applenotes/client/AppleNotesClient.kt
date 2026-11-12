package com.example.applenotes.client

import com.example.applenotes.log.CoreLog
import com.example.applenotes.auth.ICloudSession
import com.example.applenotes.auth.LENIENT_JSON
import com.example.applenotes.auth.USER_AGENT
import com.example.applenotes.proto.NoteBodyEditor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "AppleNotesClient"

/** Lightweight summary returned by recents(). title is decoded base64 → UTF-8 best effort. */
data class NoteSummary(
    val recordName: String,
    val title: String?,
    val snippet: String?,
    val modificationTimestampMs: Long?,
    val deleted: Boolean,
    /**
     * The recordName of the Folder this note lives in. Apple's special folders:
     *  - "DefaultFolder-CloudKit"  → the standard "Notes" folder
     *  - "TrashFolder-CloudKit"    → "Recently Deleted"
     *  - any other UUID            → user-created folder
     */
    val folderRecordName: String?,
)

/** A folder the user has created (or one of Apple's special folders). */
data class FolderSummary(
    val recordName: String,
    val displayName: String?,
    val isTrash: Boolean,
    val isDefault: Boolean,
)

/** Full record for the note detail view. */
data class NoteRecord(
    val recordName: String,
    val recordType: String?,
    val recordChangeTag: String?,
    val rawFields: Map<String, JsonElement>,
) {
    fun stringField(name: String): String? = rawFields[name]?.let { extractStringValue(it) }
    fun decodedTitle(): String? = rawFields["TitleEncrypted"]?.let { decodeEncryptedString(it) }
}

/**
 * Minimal CloudKit JSON client for `com.apple.notes` (private DB). Mirrors the
 * request shape pyicloud uses post-cookie-bootstrap.
 *
 * `*Encrypted` fields come back as base64 of plaintext when authenticated via
 * the web cookies (the X-APPLE-WEBAUTH-PCS-Notes cookie carries the unwrapped
 * Notes PCS key, so the server pre-decrypts).
 */
class AppleNotesClient(
    private val httpClient: HttpClient,
    private val session: ICloudSession,
) {
    /** The account this session belongs to (see NotesApi.accountId). */
    val accountId: String get() = session.dsid

    suspend fun fetchRecents(limit: Int = 50): List<NoteSummary> {
        // CloudKit's "recents" SearchIndex paginates aggressively (returns ~13
        // records per page even when we ask for more). It hands back a
        // `continuationMarker` whenever there's another page. We loop until
        // we've collected `limit` notes or run out of continuation markers.
        val all = mutableListOf<NoteSummary>()
        var continuation: String? = null
        var page = 0
        val tStart = System.currentTimeMillis()
        while (all.size < limit) {
            val remaining = limit - all.size
            val payload: JsonObject = buildJsonObject {
                put("zoneID", buildJsonObject { put("zoneName", "Notes") })
                put("resultsLimit", remaining)
                if (continuation != null) put("continuationMarker", continuation!!)
                put("query", buildJsonObject {
                    put("recordType", "SearchIndexes")
                    putJsonArray("filterBy") {
                        add(buildJsonObject {
                            put("comparator", "EQUALS")
                            put("fieldName", "indexName")
                            put("fieldValue", buildJsonObject {
                                put("type", "STRING")
                                put("value", "recents")
                            })
                        })
                    }
                    putJsonArray("sortBy") {
                        add(buildJsonObject {
                            put("fieldName", "modTime")
                            put("ascending", false)
                        })
                    }
                })
                putJsonArray("desiredKeys") {
                    listOf("TitleEncrypted", "SnippetEncrypted", "ModificationDate", "Deleted", "Folder")
                        .forEach { add(JsonPrimitive(it)) }
                }
            }
            CoreLog.i(TAG) { "fetchRecents page=$page: requesting up to $remaining (have ${all.size}/$limit)" }
            val t0 = System.currentTimeMillis()
            val (status, raw) = postJson("/records/query", payload)
            val elapsed = System.currentTimeMillis() - t0
            CoreLog.i(TAG) { "fetchRecents page=$page response: HTTP $status, ${raw.length}B in ${elapsed}ms" }
            val parsed = parseResponse(raw, "records/query")
            val pageResults = (parsed.records ?: emptyList()).map { rec ->
                NoteSummary(
                    recordName = rec.recordName ?: "",
                    title = rec.fields?.get("TitleEncrypted")?.let(::decodeEncryptedString),
                    snippet = rec.fields?.get("SnippetEncrypted")?.let(::decodeEncryptedString),
                    modificationTimestampMs = rec.fields?.get("ModificationDate")
                        ?.let(::extractStringValue)?.toLongOrNull(),
                    deleted = rec.fields?.get("Deleted")?.let(::extractStringValue) == "1",
                    folderRecordName = rec.fields?.get("Folder")?.let(::extractReferenceRecordName),
                )
            }
            all.addAll(pageResults)
            continuation = parsed.continuationMarker
            page++
            // Stop if the server signalled "no more pages" or returned an empty
            // page (defensive — should not happen if continuationMarker says
            // there is more).
            if (continuation == null || pageResults.isEmpty()) break
        }
        val totalElapsed = System.currentTimeMillis() - tStart
        CoreLog.i(TAG) { "fetchRecents: returning ${all.size} records across $page pages in ${totalElapsed}ms" }
        for ((i, n) in all.withIndex()) {
            CoreLog.v(TAG) {
                "  [$i] ${n.recordName} title='${n.title?.take(40)}' " +
                    "snippet='${n.snippet?.take(40)}' " +
                    "mod=${n.modificationTimestampMs} deleted=${n.deleted}"
            }
        }
        return all
    }

    /**
     * One page of the "recents" SearchIndex (issue #12): the same query as
     * [fetchRecents], but a single request, so the caller can store each page
     * as it arrives and keep paging in the background with no overall cap.
     */
    suspend fun fetchRecentsPage(continuationMarker: String?, resultsLimit: Int): RecentsPage {
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            put("resultsLimit", resultsLimit)
            if (continuationMarker != null) put("continuationMarker", continuationMarker)
            put("query", buildJsonObject {
                put("recordType", "SearchIndexes")
                putJsonArray("filterBy") {
                    add(buildJsonObject {
                        put("comparator", "EQUALS")
                        put("fieldName", "indexName")
                        put("fieldValue", buildJsonObject {
                            put("type", "STRING")
                            put("value", "recents")
                        })
                    })
                }
                putJsonArray("sortBy") {
                    add(buildJsonObject {
                        put("fieldName", "modTime")
                        put("ascending", false)
                    })
                }
            })
            putJsonArray("desiredKeys") { SUMMARY_KEYS.forEach { add(JsonPrimitive(it)) } }
        }
        val t0 = System.currentTimeMillis()
        val (status, raw) = postJson("/records/query", payload)
        val parsed = parseResponse(raw, "records/query")
        val notes = (parsed.records ?: emptyList()).mapNotNull { rec ->
            val rn = rec.recordName ?: return@mapNotNull null
            noteSummaryOf(rn, rec.fields)
        }
        CoreLog.i(TAG) {
            "fetchRecentsPage: HTTP $status, ${notes.size} records in ${System.currentTimeMillis() - t0}ms " +
                "more=${parsed.continuationMarker != null}"
        }
        return RecentsPage(notes, parsed.continuationMarker)
    }

    /**
     * What changed in the Notes zone since [syncToken] (everything, when
     * null): CloudKit Web Services `POST [path]/changes/zone` with `zoneID`,
     * `syncToken`, `resultsLimit` and `desiredKeys`, answering `records`,
     * `syncToken` and `moreComing` (issue #12).
     *
     * Read-only. NOT yet confirmed against a real account: the caller treats
     * any failure (typed [NotesError]) as "fall back to a full refetch". See
     * [ZoneChangesParser] for how records are classified.
     */
    suspend fun fetchZoneChanges(syncToken: String?, resultsLimit: Int): ZoneChanges {
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            if (syncToken != null) put("syncToken", syncToken)
            put("resultsLimit", resultsLimit)
            putJsonArray("desiredKeys") { SUMMARY_KEYS.forEach { add(JsonPrimitive(it)) } }
        }
        val t0 = System.currentTimeMillis()
        val (status, raw) = postJson("/changes/zone", payload)
        val changes = ZoneChangesParser.parse(raw)
        CoreLog.i(TAG) {
            "fetchZoneChanges(token=${syncToken != null}): HTTP $status in ${System.currentTimeMillis() - t0}ms " +
                "notes=${changes.notes.size} folders=${changes.folders.size} removed=${changes.removedRecordNames.size} " +
                "ignored=${changes.ignored} more=${changes.moreComing}"
        }
        return changes
    }

    /**
     * Query all Folder records in the user's Notes zone. We iterate the
     * "folders" SearchIndex (same paginated pattern as fetchRecents) and
     * decode each folder's TitleEncrypted as the display name. Apple's two
     * special folders ("Notes" / DefaultFolder-CloudKit and
     * "Recently Deleted" / TrashFolder-CloudKit) come back too.
     */
    /**
     * Look up folders by their CloudKit recordNames. CloudKit refuses to
     * "query" Folder records ("Type is not marked indexable") and there's no
     * "folders" SearchIndex either ("No index of this name exists"), so we
     * use /records/lookup with the specific recordNames the caller provides
     * (typically derived from the `Folder` reference field of every fetched
     * note). Returns one FolderSummary per requested recordName, with a
     * decoded display name when present.
     */
    suspend fun lookupFolders(recordNames: Collection<String>): List<FolderSummary> {
        if (recordNames.isEmpty()) return emptyList()
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            putJsonArray("records") {
                for (rn in recordNames.distinct()) {
                    add(buildJsonObject { put("recordName", rn) })
                }
            }
        }
        CoreLog.i(TAG) { "lookupFolders: ${recordNames.size} ids" }
        val (_, raw) = postJson("/records/lookup", payload)
        val parsed = parseResponse(raw, "folder lookup")
        val out = (parsed.records ?: emptyList()).mapNotNull { rec ->
            val rn = rec.recordName ?: return@mapNotNull null
            // A record can come back with serverErrorCode=NOT_FOUND if the
            // ref points at a folder we can't see (rare). Skip those.
            if (rec.serverErrorCode != null) {
                CoreLog.w(TAG, "lookupFolders: skipping $rn (${rec.serverErrorCode})")
                return@mapNotNull null
            }
            FolderSummary(
                recordName = rn,
                displayName = rec.fields?.get("TitleEncrypted")?.let(::decodeEncryptedString),
                isTrash = rn == "TrashFolder-CloudKit",
                isDefault = rn == "DefaultFolder-CloudKit",
            )
        }
        CoreLog.i(TAG) { "lookupFolders: ${out.size} resolved" }
        for ((i, f) in out.withIndex()) {
            CoreLog.v(TAG) { "  [$i] ${f.recordName} display='${f.displayName}' trash=${f.isTrash} default=${f.isDefault}" }
        }
        return out
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun lookupNote(recordName: String): NoteRecord {
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            putJsonArray("records") {
                add(buildJsonObject { put("recordName", recordName) })
            }
        }
        CoreLog.i(TAG) { "lookupNote: $recordName" }
        val t0 = System.currentTimeMillis()
        val (status, raw) = postJson("/records/lookup", payload)
        val elapsed = System.currentTimeMillis() - t0
        CoreLog.i(TAG) { "lookupNote response: HTTP $status, ${raw.length}B in ${elapsed}ms" }
        val rec = firstRecord(parseResponse(raw, "records/lookup"), "records/lookup")
        throwIfRecordError("lookupNote", rec)
        val record = NoteRecord(
            recordName = rec.recordName ?: recordName,
            recordType = rec.recordType,
            recordChangeTag = rec.recordChangeTag,
            rawFields = rec.fields ?: emptyMap(),
        )
        CoreLog.i(TAG) { "lookupNote: tag=${record.recordChangeTag} fields=${record.rawFields.keys}" }
        record.stringField("TitleEncrypted")?.let {
            val decoded = runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
            CoreLog.v(TAG) { "lookupNote TitleEncrypted decoded='${decoded?.take(60)}'" }
        }
        record.stringField("SnippetEncrypted")?.let {
            val decoded = runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
            CoreLog.v(TAG) { "lookupNote SnippetEncrypted decoded='${decoded?.take(60)}'" }
        }
        record.stringField("ModificationDate")?.let {
            CoreLog.i(TAG) { "lookupNote ModificationDate=$it" }
        }
        logRecordSummary("lookupNote", record)
        return record
    }

    /**
     * Modify arbitrary fields on a Note record in one call. Each entry is
     * (fieldName, value, type) where type matches CloudKit's wire type
     * (e.g. "ENCRYPTED_BYTES" for body proto, "STRING" for arbitrary text).
     */
    suspend fun modifyFields(
        recordName: String,
        recordChangeTag: String,
        fields: Map<String, Pair<String, String>>, // fieldName -> (value, type)
    ): NoteRecord {
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            putJsonArray("operations") {
                add(buildJsonObject {
                    put("operationType", "update")
                    put("record", buildJsonObject {
                        put("recordName", recordName)
                        put("recordType", "Note")
                        put("recordChangeTag", recordChangeTag)
                        put("fields", buildJsonObject {
                            for ((name, vt) in fields) {
                                put(name, buildJsonObject {
                                    put("type", vt.second)
                                    put("value", vt.first)
                                })
                            }
                        })
                    })
                })
            }
        }
        CoreLog.i(TAG) { "modifyFields: $recordName tag=$recordChangeTag fields=${fields.keys}" }
        val (status, raw) = postJson("/records/modify", payload)
        CoreLog.v(TAG) { "modifyFields response: HTTP $status, body[0..400]=${raw.take(400)}" }
        val rec = firstRecord(parseResponse(raw, "records/modify"), "records/modify")
        throwIfRecordError("modifyFields", rec)
        val record = NoteRecord(
            recordName = rec.recordName ?: recordName,
            recordType = rec.recordType,
            recordChangeTag = rec.recordChangeTag,
            rawFields = rec.fields ?: emptyMap(),
        )
        CoreLog.i(TAG) { "modifyFields returned tag=${record.recordChangeTag} fieldKeys=${record.rawFields.keys}" }
        return record
    }

    /**
     * Delete a note via a hard CloudKit delete operation. Mac/iOS Notes
     * surface this as moving the note to "Recently Deleted" (server-managed).
     */
    /**
     * Download a CKAsset's binary body via its `downloadURL`. iCloud signs the
     * URL with the session's auth so we just GET it with our cookie jar.
     */
    suspend fun fetchAssetBytes(downloadUrl: String): ByteArray? = runCatching {
        // The URL comes from record data. Never send the session cookies
        // anywhere but Apple's own asset hosts over https (issue #6).
        if (!isTrustedAssetUrl(downloadUrl)) {
            CoreLog.w(TAG, "fetchAssetBytes: refusing a download URL outside Apple's asset hosts")
            return@runCatching null
        }
        val response: HttpResponse = httpClient.get(downloadUrl) {
            headers {
                append(HttpHeaders.UserAgent, USER_AGENT)
                append(HttpHeaders.Cookie, session.cookieHeader)
            }
        }
        if (response.status.value !in 200..299) {
            CoreLog.w(TAG, "fetchAssetBytes HTTP ${response.status.value}")
            return@runCatching null
        }
        response.body<ByteArray>()
    }.getOrNull()

    suspend fun deleteNote(recordName: String, recordChangeTag: String): String {
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            putJsonArray("operations") {
                add(buildJsonObject {
                    put("operationType", "forceDelete")
                    put("record", buildJsonObject {
                        put("recordName", recordName)
                        put("recordType", "Note")
                        put("recordChangeTag", recordChangeTag)
                    })
                })
            }
        }
        CoreLog.i(TAG) { "deleteNote: $recordName tag=$recordChangeTag" }
        val (status, raw) = postJson("/records/modify", payload)
        CoreLog.v(TAG) { "deleteNote response: HTTP $status, ${raw.length}B body[0..400]=${raw.take(400)}" }
        // A per-record error (e.g. the tag is stale) must not look like success.
        parseResponse(raw, "deleteNote").records?.firstOrNull()?.let { throwIfRecordError("deleteNote", it) }
        return raw
    }

    /**
     * Create a brand-new Note record with the given title + body. We need a
     * Folder reference (CKReference) to attach it under — easiest is to copy
     * one from any existing note. The TextDataEncrypted body is a bare
     * topotext.String registering us as the sole replica with one substring
     * covering the entire content.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun createNote(
        title: String,
        body: String,
        folderRef: JsonElement,
        ourReplicaUuid: ByteArray,
    ): NoteRecord {
        val recordName = java.util.UUID.randomUUID().toString().uppercase()
        // Combine title and body into a single note_text where line 1 = title.
        val noteText = if (title.isNotEmpty() && body.isNotEmpty()) "$title\n$body"
            else if (title.isNotEmpty()) title
            else body
        val textDataB64 = com.example.applenotes.proto.NoteCreator.buildEmptyNoteB64(
            ourReplicaUuid, noteText,
        )
        val nowMs = System.currentTimeMillis()
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            putJsonArray("operations") {
                add(buildJsonObject {
                    put("operationType", "create")
                    put("record", buildJsonObject {
                        put("recordName", recordName)
                        put("recordType", "Note")
                        put("fields", buildJsonObject {
                            put("TextDataEncrypted", buildJsonObject {
                                put("type", "ENCRYPTED_BYTES")
                                put("value", textDataB64)
                            })
                            put("TitleEncrypted", buildJsonObject {
                                put("type", "ENCRYPTED_STRING")
                                put("value", kotlin.io.encoding.Base64.encode(title.encodeToByteArray()))
                            })
                            put("SnippetEncrypted", buildJsonObject {
                                put("type", "ENCRYPTED_STRING")
                                put("value", kotlin.io.encoding.Base64.encode(body.take(120).encodeToByteArray()))
                            })
                            // Reuse Folder reference structure verbatim from the sample note.
                            put("Folder", folderRef)
                            // CRITICAL: without ModificationDate + CreationDate
                            // CloudKit defaults them to .NET DateTime.MinValue
                            // (year 1, encoded as -62135769600000 ms). Mac's
                            // notesync sees that sentinel and skips syncing
                            // the record to local — the user creates a note
                            // from Android, iCloud has it, Mac never shows it.
                            put("ModificationDate", buildJsonObject {
                                put("type", "TIMESTAMP")
                                put("value", nowMs)
                            })
                            put("CreationDate", buildJsonObject {
                                put("type", "TIMESTAMP")
                                put("value", nowMs)
                            })
                            // PaperStyleType / AttachmentViewType / Deleted /
                            // Folders / FoldersModificationDate are
                            // server-derived or default-zero — iCloud.com
                            // doesn't send them on create and Mac fills them
                            // in via notesync. Sending zeros made the record
                            // look "edited" relative to defaults, which can
                            // race the post-merge recordChangeTag bump and
                            // cause CONFLICT on the very next edit.
                        })
                    })
                })
            }
        }
        CoreLog.v(TAG) { "createNote: $recordName title='${title.take(40)}' body.len=${body.length}" }
        val (status, raw) = postJson("/records/modify", payload)
        CoreLog.v(TAG) { "createNote response: HTTP $status, ${raw.length}B body[0..400]=${raw.take(400)}" }
        val rec = firstRecord(parseResponse(raw, "createNote"), "createNote")
        throwIfRecordError("createNote", rec)
        val record = NoteRecord(
            recordName = rec.recordName ?: recordName,
            recordType = rec.recordType,
            recordChangeTag = rec.recordChangeTag,
            rawFields = rec.fields ?: emptyMap(),
        )
        CoreLog.i(TAG) { "createNote returned tag=${record.recordChangeTag} fields=${record.rawFields.keys}" }
        return record
    }

    /**
     * Replace TextDataEncrypted on a Note record, AND optionally update the
     * sibling TitleEncrypted / SnippetEncrypted fields. Apple's clients seem
     * to use Title and Snippet as the "did this note visibly change?" signal —
     * iCloud.com sets them whenever it edits. Skipping them is what kept Mac
     * from refreshing earlier (and the Android list view from updating, since
     * fetchRecents reads only Title/Snippet).
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun modifyNoteBody(
        recordName: String,
        recordChangeTag: String,
        newTextDataEncryptedB64: String,
        newTitle: String? = null,
        newSnippet: String? = null,
        // The encrypted-bytes value of `ReplicaIDToNotesVersionDataEncrypted`
        // from the lookupNote that produced [recordChangeTag]. Pass it through
        // unchanged in the modify request — Mac's notesync seems to use a
        // change to this field as the "refresh local cache from iCloud" signal.
        // Without it, Mac's local cache can diverge from iCloud (we observed
        // duplication after Android append on multi-substring typed notes).
        replicaVersionPassThroughB64: String? = null,
    ): NoteRecord {
        // One attempt. On CONFLICT (oplock: the record changed since
        // [recordChangeTag], whether a real edit on another device or Mac's
        // notesync bumping the tag after merging our previous write) this
        // throws NotesError.Conflict. Re-sending these bytes with a fresh
        // tag would overwrite whatever changed with a body computed against
        // the stale base (last writer wins), so the caller re-applies its
        // edit to the fresh record instead: NotesRepository.saveNote does a
        // three-way merge and splices onto the fresh proto (issue #4).
        val payload: JsonObject = buildJsonObject {
            put("zoneID", buildJsonObject { put("zoneName", "Notes") })
            putJsonArray("operations") {
                add(buildJsonObject {
                    put("operationType", "update")
                    put("record", buildJsonObject {
                        put("recordName", recordName)
                        put("recordType", "Note")
                        put("recordChangeTag", recordChangeTag)
                        put("fields", buildJsonObject {
                            put("TextDataEncrypted", buildJsonObject {
                                put("type", "ENCRYPTED_BYTES")
                                put("value", newTextDataEncryptedB64)
                            })
                            if (newTitle != null) {
                                put("TitleEncrypted", buildJsonObject {
                                    put("type", "ENCRYPTED_STRING")
                                    put("value", kotlin.io.encoding.Base64.encode(newTitle.encodeToByteArray()))
                                })
                            }
                            if (newSnippet != null) {
                                put("SnippetEncrypted", buildJsonObject {
                                    put("type", "ENCRYPTED_STRING")
                                    put("value", kotlin.io.encoding.Base64.encode(newSnippet.encodeToByteArray()))
                                })
                            }
                            if (replicaVersionPassThroughB64 != null) {
                                put("ReplicaIDToNotesVersionDataEncrypted", buildJsonObject {
                                    put("type", "ENCRYPTED_BYTES")
                                    put("value", replicaVersionPassThroughB64)
                                })
                            }
                        })
                    })
                })
            }
        }
        CoreLog.v(TAG) {
            "modifyNoteBody: rec=$recordName tag=$recordChangeTag b64.len=${newTextDataEncryptedB64.length} " +
                "title=${newTitle?.let { "'${it.take(40)}'" } ?: "<unchanged>"} " +
                "snippet=${newSnippet?.let { "'${it.take(40)}'" } ?: "<unchanged>"}"
        }
        CoreLog.v(TAG) { "modifyNoteBody SENT proto: ${NoteBodyEditor.summarizeBase64(newTextDataEncryptedB64)}" }
        val t0 = System.currentTimeMillis()
        val (status, raw) = postJson("/records/modify", payload)
        val elapsed = System.currentTimeMillis() - t0
        CoreLog.i(TAG) { "modifyNoteBody response: HTTP $status, ${raw.length}B in ${elapsed}ms" }
        CoreLog.v(TAG) { "modifyNoteBody response body[0..600]=${raw.take(600)}" }
        val rec = firstRecord(parseResponse(raw, "records/modify"), "records/modify")
        throwIfRecordError("modifyNoteBody", rec)
        return finishModifyNoteBody(recordName, rec, newTextDataEncryptedB64)
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private suspend fun finishModifyNoteBody(
        recordName: String,
        rec: RecordSummary,
        newTextDataEncryptedB64: String,
    ): NoteRecord {
        val record = NoteRecord(
            recordName = rec.recordName ?: recordName,
            recordType = rec.recordType,
            recordChangeTag = rec.recordChangeTag,
            rawFields = rec.fields ?: emptyMap(),
        )
        CoreLog.i(TAG) { "modifyNoteBody returned tag=${record.recordChangeTag} fieldKeys=${record.rawFields.keys}" }
        // Decoded values for the human-readable fields if echoed.
        record.stringField("TitleEncrypted")?.let {
            val decoded = runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
            CoreLog.v(TAG) { "modifyNoteBody returned TitleEncrypted.b64='${it.take(60)}' decoded='${decoded?.take(40)}'" }
        }
        record.stringField("SnippetEncrypted")?.let {
            val decoded = runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
            CoreLog.v(TAG) { "modifyNoteBody returned SnippetEncrypted.b64='${it.take(60)}' decoded='${decoded?.take(40)}'" }
        }
        logRecordSummary("modifyNoteBody", record, sentB64 = newTextDataEncryptedB64)

        // Verify-after-save: re-fetch and compare.
        val verified = runCatching { lookupNote(recordName) }.getOrElse {
            CoreLog.w(TAG, "verify-after-save lookup failed", it)
            return record
        }
        val verifiedB64 = verified.stringField("TextDataEncrypted")
        if (verifiedB64 != null) {
            val matchesSent = verifiedB64 == newTextDataEncryptedB64
            CoreLog.i(TAG) { "verify-after-save: tag=${verified.recordChangeTag} text.b64.len=${verifiedB64.length} matchesSent=$matchesSent" }
        }
        return verified
    }

    private fun logRecordSummary(label: String, record: NoteRecord, sentB64: String? = null) {
        // Everything below is a wire-shape dump (and decoding it isn't free).
        if (!CoreLog.verboseEnabled) return
        val textB64 = record.stringField("TextDataEncrypted")
        if (textB64 != null) {
            val tag = if (sentB64 != null) " matchesSent=${textB64 == sentB64}" else ""
            CoreLog.v(TAG) { "$label TextDataEncrypted.b64.len=${textB64.length}$tag" }
            // Log first 32 raw bytes so we can identify the encoding (gzip
            // magic 1f 8b, raw proto, encrypted, etc.) when summarize fails.
            runCatching {
                @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                val raw = kotlin.io.encoding.Base64.decode(textB64)
                val prefix = raw.take(32).joinToString("") {
                    ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1)
                }
                CoreLog.v(TAG) { "$label TextDataEncrypted raw[0..32] hex=$prefix (totalRaw=${raw.size})" }
            }
            CoreLog.v(TAG) { "$label TextDataEncrypted proto: ${NoteBodyEditor.summarizeBase64(textB64)}" }
            if (label == "lookupNote") {
                CoreLog.v(TAG) { "$label replicas dump:\n${NoteBodyEditor.dumpReplicasBase64(textB64)}" }
                CoreLog.v(TAG) { "$label attribute_runs dump:\n${NoteBodyEditor.dumpAttributeRunsBase64(textB64)}" }
                // Compare bag (insertion-order String.string) with visible-text walk.
                // Mismatch => tombstoned content exists, or our walk is wrong.
                val bag = NoteBodyEditor.readTextFromBase64(textB64)
                val visible = NoteBodyEditor.readVisibleTextFromBase64(textB64)
                CoreLog.v(TAG) {
                    "$label bag.len=${bag?.length ?: -1} visible.len=${visible?.length ?: -1} " +
                        "match=${bag != null && bag == visible}"
                }
                // Escape \n so each value fits on one logcat line — makes shell
                // parsing in test harnesses ergonomic.
                val esc = { s: String? ->
                    s?.take(200)?.replace("\\", "\\\\")?.replace("\n", "\\n") ?: "<null>"
                }
                CoreLog.v(TAG) { "$label bag='${esc(bag)}'" }
                CoreLog.v(TAG) { "$label visible='${esc(visible)}'" }
                CoreLog.v(TAG) { "$label substring tree:\n${NoteBodyEditor.dumpSubstringTreeBase64(textB64)}" }
            }
        } else {
            CoreLog.v(TAG) { "$label has no TextDataEncrypted in returned fields (keys=${record.rawFields.keys})" }
        }
        val replicaRegistryB64 = record.stringField("ReplicaIDToNotesVersionDataEncrypted")
        if (replicaRegistryB64 != null) {
            CoreLog.v(TAG) { "$label ReplicaIDToNotesVersionDataEncrypted.b64.len=${replicaRegistryB64.length}" }
            CoreLog.v(TAG) { "$label ReplicaIDToNotesVersion structure:\n${NoteBodyEditor.dumpRawBase64(replicaRegistryB64)}" }
        }
    }

    /**
     * POST to the private Notes database. Returns (status, body) for a 2xx
     * answer; anything else is thrown as a [NotesError] (non-2xx status via
     * [CloudKitErrors.fromHttp], I/O failures as [NotesError.Offline]).
     */
    private suspend fun postJson(relPath: String, payload: JsonObject): Pair<Int, String> {
        val url = buildString {
            append(session.ckdatabasewsUrl)
            append("/database/1/com.apple.notes/production/private")
            append(relPath)
            append("?remapEnums=true")
            append("&getCurrentSyncToken=true")
            append("&clientBuildNumber=2612Build21")
            append("&clientMasteringNumber=2612Build21")
            append("&clientId=").append(session.clientId)
            append("&dsid=").append(session.dsid)
        }
        val (status, raw, retryAfter) = try {
            val response = httpClient.post(url) {
                headers {
                    append(HttpHeaders.Cookie, session.cookieHeader)
                    append(HttpHeaders.Origin, "https://www.icloud.com")
                    append(HttpHeaders.Referrer, "https://www.icloud.com/")
                    append(HttpHeaders.UserAgent, USER_AGENT)
                }
                contentType(ContentType.Application.Json)
                setBody(LENIENT_JSON.encodeToString(JsonObject.serializer(), payload))
            }
            Triple(response.status.value, response.body<String>(), response.headers[HttpHeaders.RetryAfter])
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val mapped = CloudKitErrors.fromThrowable(e)
            CoreLog.w(TAG, "$relPath failed before an HTTP answer", mapped)
            throw mapped
        }
        CloudKitErrors.fromHttp(status, raw, retryAfter)?.let { err ->
            CoreLog.e(TAG, "$relPath FAILED: HTTP $status -> ${err.javaClass.simpleName}")
            CoreLog.v(TAG) { "$relPath FAILED body=${raw.take(800)}" }
            throw err
        }
        return status to raw
    }

    private fun parseResponse(raw: String, what: String): QueryResponse = try {
        LENIENT_JSON.decodeFromString(QueryResponse.serializer(), raw)
    } catch (e: kotlinx.serialization.SerializationException) {
        throw NotesError.Decode("$what: malformed JSON", details = raw.take(4000), cause = e)
    } catch (e: IllegalArgumentException) {
        throw NotesError.Decode("$what: malformed JSON", details = raw.take(4000), cause = e)
    }

    private fun firstRecord(parsed: QueryResponse, what: String): RecordSummary =
        parsed.records?.firstOrNull() ?: throw NotesError.Decode("$what returned no record")

    /** Throw the typed error for a per-record `serverErrorCode`, if there is one. */
    private fun throwIfRecordError(label: String, rec: RecordSummary) {
        val code = rec.serverErrorCode ?: return
        val err = CloudKitErrors.fromRecord(code, rec.reason, rec.retryAfter)
        CoreLog.e(TAG, "$label per-record error: $code -> ${err.javaClass.simpleName}")
        throw err
    }
}

/**
 * Host suffixes CloudKit serves asset downloads from (`cvws.icloud-content.com`
 * and friends, plus the China-mainland mirrors). Matched on a label boundary,
 * so `noticloud.com` or `icloud.com.example.net` don't pass.
 */
private val TRUSTED_ASSET_HOST_SUFFIXES = listOf(
    "icloud.com",
    "icloud-content.com",
    "apple.com",
    "icloud.com.cn",
    "icloud-content.com.cn",
)

/**
 * True when [url] is an https URL on one of Apple's asset hosts: the only
 * places [AppleNotesClient.fetchAssetBytes] sends the session cookies to.
 * Rejects other schemes, URLs with userinfo, explicit non-443 ports and
 * anything without a plain host name.
 *
 * Parsed by hand rather than with java.net.URI: real CloudKit asset URLs
 * contain a literal `${f}` placeholder in the path, which URI rejects.
 */
internal fun isTrustedAssetUrl(url: String): Boolean {
    if (!url.startsWith("https://", ignoreCase = true)) return false
    val rest = url.substring("https://".length)
    val authority = rest.substring(0, rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it })
    // Userinfo ("user@host") and backslashes (which some parsers treat as
    // path separators) make the effective host ambiguous.
    if ('@' in authority || '\\' in authority) return false
    val colon = authority.lastIndexOf(':')
    val host = (if (colon >= 0) authority.substring(0, colon) else authority).lowercase().trimEnd('.')
    if (colon >= 0 && authority.substring(colon + 1) != "443") return false
    if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == '-' || it == '.' }) return false
    return TRUSTED_ASSET_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
}

internal fun extractStringValue(field: JsonElement): String? {
    val obj = field as? JsonObject ?: return null
    val value = obj["value"] ?: return null
    return runCatching { value.jsonPrimitive.content }.getOrNull()
}

/**
 * CKAsset fields look like
 *   { "type": "ASSETID", "value": { "downloadURL": "...", "size": N, "fileChecksum": "..." } }
 * Pull `value.downloadURL`. Some download URLs come back with `${f}` placeholders
 * that need substitution; we surface the raw URL and let the caller request it.
 */
internal fun extractAssetDownloadUrl(field: JsonElement): String? {
    val obj = field as? JsonObject ?: return null
    val value = obj["value"] as? JsonObject ?: return null
    return runCatching { value["downloadURL"]?.jsonPrimitive?.content }.getOrNull()
}

/**
 * CKReference fields look like
 *   { "type": "REFERENCE", "value": { "recordName": "...", "action": "NONE", "zoneID": {...} } }
 * Pull `value.recordName` out.
 */
fun extractReferenceRecordName(field: JsonElement): String? {
    val obj = field as? JsonObject ?: return null
    val value = obj["value"] as? JsonObject ?: return null
    return runCatching { value["recordName"]?.jsonPrimitive?.content }.getOrNull()
}

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeEncryptedString(field: JsonElement): String? {
    val raw = extractStringValue(field) ?: return null
    return runCatching { Base64.decode(raw).decodeToString() }.getOrNull() ?: raw
}

@Serializable
private data class QueryResponse(
    val records: List<RecordSummary>? = null,
    @SerialName("continuationMarker") val continuationMarker: String? = null,
    val syncToken: String? = null,
)

@Serializable
private data class RecordSummary(
    val recordName: String? = null,
    val recordType: String? = null,
    val recordChangeTag: String? = null,
    val fields: Map<String, JsonElement>? = null,
    val created: JsonElement? = null,
    val modified: JsonElement? = null,
    val serverErrorCode: String? = null,
    val reason: String? = null,
    /** Seconds; CloudKit sets it on THROTTLED / TRY_AGAIN_LATER errors. */
    val retryAfter: Long? = null,
)
