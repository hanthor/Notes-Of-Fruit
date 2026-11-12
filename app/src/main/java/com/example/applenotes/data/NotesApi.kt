package com.example.applenotes.data

import com.example.applenotes.auth.AUTH_TOKEN_COOKIE
import com.example.applenotes.auth.AppleNotesSession
import com.example.applenotes.auth.CookieReader
import com.example.applenotes.client.AppleNotesClient
import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.client.RecentsPage
import com.example.applenotes.client.ZoneChanges
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement

/**
 * The CloudKit operations the UI layer uses. Production code goes through
 * [CloudKitNotesApi] (a thin delegate over [AppleNotesClient]); unit tests
 * substitute a fake so the save / navigation logic can run without a
 * network or an iCloud account.
 *
 * Signatures mirror [AppleNotesClient] one-to-one — see that class for the
 * protocol details of each call.
 */
interface NotesApi {
    suspend fun fetchRecents(limit: Int): List<NoteSummary>
    suspend fun lookupFolders(recordNames: Collection<String>): List<FolderSummary>
    suspend fun lookupNote(recordName: String): NoteRecord
    suspend fun fetchAssetBytes(downloadUrl: String): ByteArray?
    suspend fun deleteNote(recordName: String, recordChangeTag: String): String
    /** [noteText] is the exact visible text to create; [title] / [body] only fill the list fields. */
    suspend fun createNote(
        title: String,
        body: String,
        folderRef: JsonElement,
        ourReplicaUuid: ByteArray,
        noteText: String,
    ): NoteRecord
    suspend fun modifyNoteBody(
        recordName: String,
        recordChangeTag: String,
        newTextDataEncryptedB64: String,
        newTitle: String?,
        newSnippet: String?,
        replicaVersionPassThroughB64: String?,
    ): NoteRecord

    /**
     * One page of the recents index (issue #12). The default serves the
     * whole [fetchRecents] answer as a single page, for fakes that don't page.
     */
    suspend fun fetchRecentsPage(continuationMarker: String?, resultsLimit: Int): RecentsPage =
        RecentsPage(if (continuationMarker == null) fetchRecents(resultsLimit) else emptyList(), null)

    /**
     * Zone changes since [syncToken] (issue #12). The default reports the
     * endpoint as unsupported, which the repository treats like any other
     * failure of the incremental path: it does a full refetch instead.
     */
    suspend fun fetchZoneChanges(syncToken: String?, resultsLimit: Int): ZoneChanges =
        throw UnsupportedOperationException("changes/zone not supported")

    /**
     * Who the session belongs to (the iCloud dsid), so a cache written for
     * one account is never shown to, or synced into, another. Null = unknown.
     */
    val accountId: String? get() = null
}

/** [NotesApi] backed by the real CloudKit client. One instance per session. */
class CloudKitNotesApi(private val client: AppleNotesClient) : NotesApi {
    override suspend fun fetchRecents(limit: Int) = client.fetchRecents(limit = limit)
    override suspend fun lookupFolders(recordNames: Collection<String>) = client.lookupFolders(recordNames)
    override suspend fun lookupNote(recordName: String) = client.lookupNote(recordName)
    override suspend fun fetchAssetBytes(downloadUrl: String) = client.fetchAssetBytes(downloadUrl)
    override suspend fun deleteNote(recordName: String, recordChangeTag: String) =
        client.deleteNote(recordName, recordChangeTag)

    override suspend fun createNote(
        title: String,
        body: String,
        folderRef: JsonElement,
        ourReplicaUuid: ByteArray,
        noteText: String,
    ) = client.createNote(
        title = title,
        body = body,
        folderRef = folderRef,
        ourReplicaUuid = ourReplicaUuid,
        noteText = noteText,
    )

    override suspend fun modifyNoteBody(
        recordName: String,
        recordChangeTag: String,
        newTextDataEncryptedB64: String,
        newTitle: String?,
        newSnippet: String?,
        replicaVersionPassThroughB64: String?,
    ) = client.modifyNoteBody(
        recordName,
        recordChangeTag,
        newTextDataEncryptedB64,
        newTitle = newTitle,
        newSnippet = newSnippet,
        replicaVersionPassThroughB64 = replicaVersionPassThroughB64,
    )

    override suspend fun fetchRecentsPage(continuationMarker: String?, resultsLimit: Int) =
        client.fetchRecentsPage(continuationMarker, resultsLimit)

    override suspend fun fetchZoneChanges(syncToken: String?, resultsLimit: Int) =
        client.fetchZoneChanges(syncToken, resultsLimit)

    override val accountId: String? get() = client.accountId
}

/** Sign-in plumbing: the WebView cookie jar and the CloudKit session bootstrap. */
interface AuthGateway {
    /** True when the WebView cookie jar already holds an iCloud auth token. */
    fun hasSavedLogin(): Boolean

    /** Bootstraps a CloudKit session from the cookie jar. Throws on failure. */
    suspend fun openSession(): NotesApi

    /** Forget the login (clears the WebView auth cookies). */
    fun clearLogin()

    /**
     * The current value of the iCloud auth-token cookie, or null. The sign-in
     * screen uses it to wait for a *new* token after a session expired,
     * instead of re-submitting the stale one straight away.
     */
    fun currentAuthToken(): String? = null
}

class CloudKitAuthGateway(
    private val httpClient: HttpClient,
    private val cookieReader: CookieReader,
) : AuthGateway {
    override fun hasSavedLogin(): Boolean = AUTH_TOKEN_COOKIE in cookieReader.cookieMap()

    override fun currentAuthToken(): String? = cookieReader.cookieMap()[AUTH_TOKEN_COOKIE]

    override suspend fun openSession(): NotesApi {
        val session = AppleNotesSession(httpClient, cookieReader).bootstrap().getOrThrow()
        return CloudKitNotesApi(AppleNotesClient(httpClient, session))
    }

    override fun clearLogin() = cookieReader.clearAuthCookies()
}
