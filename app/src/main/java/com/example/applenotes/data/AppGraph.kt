package com.example.applenotes.data

import android.content.Context
import com.example.applenotes.AlphaWarning
import com.example.applenotes.auth.CookieReader
import com.example.applenotes.auth.DeviceIdentity
import com.example.applenotes.data.local.NotesDatabase
import com.example.applenotes.integrations.LocalSurfaces
import com.example.applenotes.data.local.RoomNotesStore
import com.example.applenotes.sync.WorkManagerDraftScheduler
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

/** First-launch alpha disclaimer acknowledgement (see [AlphaWarning]). */
interface AlphaGate {
    suspend fun isAcknowledged(): Boolean
    suspend fun acknowledge()
}

/**
 * Process-wide singletons, initialised lazily from the application context so
 * no `Application` subclass (and no manifest change) is needed.
 *
 * The single [HttpClient] lives for the life of the process — there is
 * nothing to close it on; the OS reclaims it with the process.
 */
object AppGraph {
    @Volatile
    private var repository: NotesRepository? = null

    fun notesRepository(context: Context): NotesRepository =
        repository ?: synchronized(this) {
            repository ?: createRepository(context.applicationContext).also {
                repository = it
                // Surfaces outside the app (widget, documents provider) follow the cache.
                LocalSurfaces.attach(context.applicationContext, it)
            }
        }

    fun alphaGate(context: Context): AlphaGate {
        val app = context.applicationContext
        return object : AlphaGate {
            override suspend fun isAcknowledged() = AlphaWarning.isAcknowledged(app)
            override suspend fun acknowledge() = AlphaWarning.acknowledge(app)
        }
    }

    private fun createRepository(app: Context): NotesRepository {
        val httpClient = HttpClient(OkHttp) {
            expectSuccess = false
        }
        return NotesRepository(
            auth = CloudKitAuthGateway(httpClient, CookieReader()),
            replicaUuidLoader = { DeviceIdentity.getOrCreate(app) },
            connectivity = AndroidConnectivityMonitor(app),
            store = RoomNotesStore(NotesDatabase.open(app)),
            draftScheduler = WorkManagerDraftScheduler(app),
            clearOtherLocalData = {
                withContext(Dispatchers.IO) {
                    AttachmentCache(FileCacheStore(File(app.cacheDir, ATTACHMENT_CACHE_DIR))).clear()
                }
            },
            // Main.immediate matches where these writes ran before the
            // refactor (the composable's scope): the proto builders are not
            // audited for thread-safety, and Ktor suspends off-thread anyway.
            externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
}

/** Directory under cacheDir holding downloaded attachment bytes (see AttachmentCache). */
internal const val ATTACHMENT_CACHE_DIR = "attachments"
