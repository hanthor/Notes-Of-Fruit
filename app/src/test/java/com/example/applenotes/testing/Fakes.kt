package com.example.applenotes.testing

import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.data.AlphaGate
import com.example.applenotes.data.AuthGateway
import com.example.applenotes.data.NotesApi
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteCreator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.rules.TestWatcher
import org.junit.runner.Description

val REPLICA_UUID: ByteArray = ByteArray(16) { (it + 1).toByte() }.also {
    it[6] = ((it[6].toInt() and 0x0F) or 0x40).toByte()
    it[8] = ((it[8].toInt() and 0x3F) or 0x80).toByte()
}

/** The replica of "another device" in conflict tests. */
val OTHER_DEVICE_UUID: ByteArray = ByteArray(16) { (0x50 + it).toByte() }.also {
    it[6] = ((it[6].toInt() and 0x0F) or 0x40).toByte()
    it[8] = ((it[8].toInt() and 0x3F) or 0x80).toByte()
}

fun stringField(value: String): JsonElement = buildJsonObject {
    put("type", "ENCRYPTED_BYTES")
    put("value", value)
}

fun folderRef(recordName: String): JsonElement = buildJsonObject {
    put("type", "REFERENCE")
    put("value", buildJsonObject { put("recordName", recordName); put("action", "NONE") })
}

/** A NOTE_STORE_PROTO body with [text] as its visible content. */
fun noteBodyB64(text: String): String = NoteCreator.buildEmptyNoteB64(REPLICA_UUID, text)

fun noteRecord(
    recordName: String,
    text: String?,
    tag: String? = "tag-1",
    folder: String = "DefaultFolder-CloudKit",
    textB64: String? = text?.let { noteBodyB64(it) },
): NoteRecord = NoteRecord(
    recordName = recordName,
    recordType = "Note",
    recordChangeTag = tag,
    rawFields = buildMap {
        textB64?.let { put("TextDataEncrypted", stringField(it)) }
        put("Folder", folderRef(folder))
        put("ReplicaIDToNotesVersionDataEncrypted", stringField("cmVwbGljYS12ZXJzaW9u"))
    },
)

fun NoteRecord.visibleText(): String? =
    stringField("TextDataEncrypted")?.let { NoteBodyEditor.readVisibleTextFromBase64(it) }

/** In-memory [NotesApi]. Every call is recorded; any call can be made to fail or to suspend. */
class FakeNotesApi : NotesApi {
    val records = linkedMapOf<String, NoteRecord>()
    val calls = mutableListOf<String>()

    /** Operation name ("modifyNoteBody", "lookupNote", ...) → error to throw on its next call. */
    val failNext = mutableMapOf<String, Throwable>()

    /** Operation name → errors to throw on the next calls, in order (after [failNext]). */
    val failQueue = mutableMapOf<String, ArrayDeque<Throwable>>()

    /** Operation name → gate the next call waits on (to test races). */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    data class Modify(
        val recordName: String,
        val tag: String,
        val b64: String,
        val newTitle: String?,
        val newSnippet: String?,
        val replicaVersion: String?,
    )

    data class Create(val title: String, val body: String, val folderRef: JsonElement, val uuid: ByteArray)

    val modifies = mutableListOf<Modify>()

    /** Reject a modify whose tag isn't the record's current one, like CloudKit's oplock. */
    var checkTags = false
    var conflicts = 0

    /** Runs at the start of the n-th modifyNoteBody call (1-based), e.g. to land a remote edit. */
    var beforeModify: ((Int) -> Unit)? = null
    private var modifyCalls = 0

    /**
     * Simulate another device editing [recordName] to [newText]: spliced by
     * [editor]'s replica, new tag, new ReplicaIDToNotesVersionDataEncrypted.
     */
    fun remoteEdit(recordName: String, newText: String, editor: ByteArray = OTHER_DEVICE_UUID): NoteRecord {
        val old = records.getValue(recordName)
        val b64 = com.example.applenotes.proto.NoteAppender.setBodyBase64(
            old.stringField("TextDataEncrypted")!!, editor, newText, 500,
        )
        val rec = old.copy(
            recordChangeTag = "remote-${++tagCounter}",
            rawFields = old.rawFields +
                ("TextDataEncrypted" to stringField(b64)) +
                ("ReplicaIDToNotesVersionDataEncrypted" to stringField("cmVtb3RlLXZlcnNpb24=")),
        )
        put(rec)
        return rec
    }
    val creates = mutableListOf<Create>()
    val deletes = mutableListOf<Pair<String, String>>()
    private var tagCounter = 100
    private var createCounter = 0

    fun put(record: NoteRecord) {
        records[record.recordName] = record
    }

    private suspend fun enter(op: String) {
        calls += op
        gates.remove(op)?.await()
        failNext.remove(op)?.let { throw it }
        failQueue[op]?.removeFirstOrNull()?.let { throw it }
    }

    override suspend fun fetchRecents(limit: Int): List<NoteSummary> {
        enter("fetchRecents")
        return records.values.mapIndexed { i, r ->
            val text = r.visibleText().orEmpty()
            val (title, snippet) = com.example.applenotes.data.computeTitleAndSnippet(text)
            NoteSummary(
                recordName = r.recordName,
                title = title,
                snippet = snippet,
                modificationTimestampMs = 1_000_000L - i,
                deleted = false,
                folderRecordName = r.rawFields["Folder"]?.let {
                    ((it as JsonObject)["value"] as JsonObject)["recordName"].let { v -> (v as JsonPrimitive).content }
                },
            )
        }
    }

    override suspend fun lookupFolders(recordNames: Collection<String>): List<FolderSummary> {
        enter("lookupFolders")
        return recordNames.map {
            FolderSummary(it, displayName = "Folder $it", isTrash = it == "TrashFolder-CloudKit", isDefault = it == "DefaultFolder-CloudKit")
        }
    }

    override suspend fun lookupNote(recordName: String): NoteRecord {
        enter("lookupNote")
        return records[recordName] ?: error("no such record $recordName")
    }

    override suspend fun fetchAssetBytes(downloadUrl: String): ByteArray? {
        enter("fetchAssetBytes")
        return null
    }

    override suspend fun deleteNote(recordName: String, recordChangeTag: String): String {
        enter("deleteNote")
        deletes += recordName to recordChangeTag
        records.remove(recordName)
        return recordName
    }

    override suspend fun createNote(
        title: String,
        body: String,
        folderRef: JsonElement,
        ourReplicaUuid: ByteArray,
    ): NoteRecord {
        enter("createNote")
        creates += Create(title, body, folderRef, ourReplicaUuid)
        val text = if (title.isNotEmpty() && body.isNotEmpty()) "$title\n$body" else title.ifEmpty { body }
        val rec = NoteRecord(
            recordName = "CREATED-${++createCounter}",
            recordType = "Note",
            recordChangeTag = "tag-${++tagCounter}",
            rawFields = mapOf(
                "TextDataEncrypted" to stringField(NoteCreator.buildEmptyNoteB64(ourReplicaUuid, text)),
                "Folder" to folderRef,
            ),
        )
        put(rec)
        return rec
    }

    override suspend fun modifyNoteBody(
        recordName: String,
        recordChangeTag: String,
        newTextDataEncryptedB64: String,
        newTitle: String?,
        newSnippet: String?,
        replicaVersionPassThroughB64: String?,
    ): NoteRecord {
        enter("modifyNoteBody")
        beforeModify?.invoke(++modifyCalls)
        if (checkTags && records[recordName]?.recordChangeTag != recordChangeTag) {
            conflicts++
            throw com.example.applenotes.client.NotesError.Conflict(details = "serverErrorCode=CONFLICT")
        }
        modifies += Modify(recordName, recordChangeTag, newTextDataEncryptedB64, newTitle, newSnippet, replicaVersionPassThroughB64)
        val old = records[recordName] ?: error("no such record $recordName")
        val rec = old.copy(
            recordChangeTag = "tag-${++tagCounter}",
            rawFields = old.rawFields + ("TextDataEncrypted" to stringField(newTextDataEncryptedB64)),
        )
        put(rec)
        return rec
    }
}

class FakeAuthGateway(private val api: NotesApi, var savedLogin: Boolean = true) : AuthGateway {
    var openSessionCalls = 0
    var failOpen: Throwable? = null
    var cleared = false

    /** The auth-token cookie value [currentAuthToken] reports. */
    var authToken: String? = "token-1"

    override fun hasSavedLogin() = savedLogin

    override suspend fun openSession(): NotesApi {
        openSessionCalls++
        failOpen?.let { failOpen = null; throw it }
        return api
    }

    override fun currentAuthToken(): String? = authToken

    override fun clearLogin() {
        cleared = true
        savedLogin = false
    }
}

class FakeAlphaGate(var acknowledged: Boolean = true) : AlphaGate {
    override suspend fun isAcknowledged() = acknowledged
    override suspend fun acknowledge() {
        acknowledged = true
    }
}

/** Installs a test Main dispatcher (viewModelScope uses Dispatchers.Main). */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** Connectivity the test flips by hand. */
class FakeConnectivity(initial: Boolean = true) : com.example.applenotes.data.ConnectivityMonitor {
    override val online = kotlinx.coroutines.flow.MutableStateFlow(initial)
}
