package com.example.applenotes.data

import com.example.applenotes.auth.AUTH_TOKEN_COOKIE
import com.example.applenotes.auth.AppleNotesSession
import com.example.applenotes.auth.CookieReader
import com.example.applenotes.client.AppleNotesClient
import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NoteSummary
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
    suspend fun createNote(
        title: String,
        body: String,
        folderRef: JsonElement,
        ourReplicaUuid: ByteArray,
    ): NoteRecord
    suspend fun modifyNoteBody(
        recordName: String,
        recordChangeTag: String,
        newTextDataEncryptedB64: String,
        newTitle: String?,
        newSnippet: String?,
        replicaVersionPassThroughB64: String?,
    ): NoteRecord
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
    ) = client.createNote(
        title = title,
        body = body,
        folderRef = folderRef,
        ourReplicaUuid = ourReplicaUuid,
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
