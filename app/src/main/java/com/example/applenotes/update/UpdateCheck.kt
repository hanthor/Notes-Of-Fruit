package com.example.applenotes.update

import android.content.Context
import androidx.core.content.edit
import com.example.applenotes.data.runCatchingCancellable
import com.example.applenotes.log.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "UpdateCheck"

/**
 * Opt-in "Check for updates" (issue #22). Off by default. When on, at most
 * once a day on launch the app asks GitHub for the latest release, with a
 * plain HTTP client that has no cookies and no iCloud session, and shows a
 * banner when its tag is newer than this build. Debug builds never check.
 */
object UpdateCheck {
    const val LATEST_RELEASE_API = "https://api.github.com/repos/hanthor/Notes-Of-Fruit/releases/latest"
    const val RELEASES_PAGE = "https://github.com/hanthor/Notes-Of-Fruit/releases/latest"
    private const val RELEASE_URL_PREFIX = "https://github.com/hanthor/Notes-Of-Fruit/releases/"
    const val INTERVAL_MS = 24 * 60 * 60 * 1000L

    /** "v1.2.3" / "1.2.3" / "1.2.3-debug" → [1, 2, 3]; null when there's no leading number. */
    fun parseVersion(raw: String): List<Int>? {
        val core = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** True when release [tag] is a strictly newer version than [current] (missing parts count as 0). */
    fun isNewer(tag: String, current: String): Boolean {
        val remote = parseVersion(tag) ?: return false
        val local = parseVersion(current) ?: return false
        for (i in 0 until maxOf(remote.size, local.size)) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    /** Debug builds carry the "-debug" versionName suffix and never check. */
    fun isDebugVersion(versionName: String): Boolean = versionName.endsWith("-debug")

    /** The once-a-day gate. A clock that went backwards also allows a check. */
    fun isDue(enabled: Boolean, versionName: String, lastCheckMs: Long?, nowMs: Long): Boolean {
        if (!enabled || isDebugVersion(versionName)) return false
        if (lastCheckMs == null || nowMs < lastCheckMs) return true
        return nowMs - lastCheckMs >= INTERVAL_MS
    }

    /** Only ever link to this project's release pages, whatever the response says. */
    fun safeReleaseUrl(htmlUrl: String?): String =
        htmlUrl?.takeIf { it.startsWith(RELEASE_URL_PREFIX) } ?: RELEASES_PAGE

    private val json = Json { ignoreUnknownKeys = true }

    fun parseRelease(body: String): GithubRelease = json.decodeFromString(GithubRelease.serializer(), body)
}

/** The fields we read from GitHub's "latest release" response. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String? = null,
)

/** A newer release to tell the user about. */
data class AvailableUpdate(val version: String, val url: String)

/** Where the toggle and the last result live (SharedPreferences in the app, a fake in tests). */
interface UpdateStore {
    var enabled: Boolean
    var lastCheckMs: Long?
    var latestTag: String?
    var latestUrl: String?
    var dismissedTag: String?
}

class PrefsUpdateStore(context: Context) : UpdateStore {
    private val prefs = context.applicationContext.getSharedPreferences("update_check", Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit { putBoolean("enabled", value) }
    override var lastCheckMs: Long?
        get() = prefs.getLong("last_check_ms", -1L).takeIf { it >= 0 }
        set(value) = prefs.edit { if (value == null) remove("last_check_ms") else putLong("last_check_ms", value) }
    override var latestTag: String?
        get() = prefs.getString("latest_tag", null)
        set(value) = prefs.edit { putString("latest_tag", value) }
    override var latestUrl: String?
        get() = prefs.getString("latest_url", null)
        set(value) = prefs.edit { putString("latest_url", value) }
    override var dismissedTag: String?
        get() = prefs.getString("dismissed_tag", null)
        set(value) = prefs.edit { putString("dismissed_tag", value) }
}

/**
 * Runs the check. [fetchLatest] returns GitHub's response body; it throws on
 * a network error or a non-200 answer.
 */
class UpdateChecker(
    private val store: UpdateStore,
    private val currentVersion: String,
    private val fetchLatest: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The update to show right now from what is stored (no network). */
    fun storedUpdate(): AvailableUpdate? {
        if (!store.enabled || UpdateCheck.isDebugVersion(currentVersion)) return null
        val tag = store.latestTag ?: return null
        if (tag == store.dismissedTag || !UpdateCheck.isNewer(tag, currentVersion)) return null
        return AvailableUpdate(tag.removePrefix("v"), UpdateCheck.safeReleaseUrl(store.latestUrl))
    }

    /** Asks GitHub if a day has passed since the last attempt; returns what to show. */
    suspend fun checkIfDue(): AvailableUpdate? {
        val now = clock()
        if (!UpdateCheck.isDue(store.enabled, currentVersion, store.lastCheckMs, now)) return storedUpdate()
        // Counted when attempted, so a failing network doesn't retry on every launch.
        store.lastCheckMs = now
        runCatchingCancellable { UpdateCheck.parseRelease(fetchLatest()) }
            .onSuccess {
                store.latestTag = it.tagName
                store.latestUrl = it.htmlUrl
            }
            .onFailure { AppLog.w(TAG, "update check failed", it) }
        return storedUpdate()
    }

    /** The banner was closed: don't show this version again. */
    fun dismiss(update: AvailableUpdate) {
        store.dismissedTag = store.latestTag ?: update.version
    }

    companion object {
        /** No cookies, no iCloud session, no auth: a client of its own. */
        private val client by lazy { HttpClient(OkHttp) { expectSuccess = false } }

        suspend fun fetchFromGitHub(): String {
            val response = client.get(UpdateCheck.LATEST_RELEASE_API) {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "NotesOfFruit-update-check")
            }
            check(response.status.value == 200) { "GitHub answered HTTP ${response.status.value}" }
            return response.bodyAsText()
        }
    }
}
