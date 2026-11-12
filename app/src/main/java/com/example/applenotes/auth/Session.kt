package com.example.applenotes.auth

import com.example.applenotes.client.CloudKitErrors
import com.example.applenotes.client.NotesError
import com.example.applenotes.log.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private const val TAG = "AppleNotesAuth"

const val USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.4 Safari/605.1.15"

val LENIENT_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * What we get back from setup.icloud.com — everything CloudKit needs.
 */
data class ICloudSession(
    val dsid: String,
    val ckdatabasewsUrl: String,
    val cookieHeader: String,
    val clientId: String,
    /** Falls back to the device's locale / time zone, not a hardcoded US default. */
    val locale: String = defaultLocale(),
    val timezone: String = defaultTimeZone(),
)

class AppleNotesSession(
    private val httpClient: HttpClient,
    private val cookieReader: CookieReader,
) {
    /**
     * Bootstrap the CloudKit session from the WebView cookie jar.
     *
     * POSTs `setup.icloud.com/setup/ws/1/validate` (the endpoint icloud.com's
     * SPA uses to refresh its session given cookies). Body is the literal
     * string "null"; cookies + clientId in the query string do all the auth.
     */
    suspend fun bootstrap(): Result<ICloudSession> = runCatching {
        val cookieMap = cookieReader.cookieMap()
        // No cookies / no auth token: nothing to refresh from, the user has
        // to sign in (again). Cookie *names* only ever go to the details.
        val cookieHeader = cookieReader.cookieHeader()
            ?: throw NotesError.AuthExpired("No icloud.com cookies in the WebView jar.")
        if (AUTH_TOKEN_COOKIE !in cookieMap) {
            throw NotesError.AuthExpired(
                "$AUTH_TOKEN_COOKIE cookie missing; sign-in didn't finish. Cookie names: ${cookieMap.keys}",
            )
        }

        val clientId = UUID.randomUUID().toString().uppercase()

        val response = try {
            httpClient.post("https://setup.icloud.com/setup/ws/1/validate") {
                url {
                    parameters.append("clientBuildNumber", "2612Build21")
                    parameters.append("clientMasteringNumber", "2612Build21")
                    parameters.append("clientId", clientId)
                }
                headers {
                    append(HttpHeaders.Cookie, cookieHeader)
                    append(HttpHeaders.Origin, "https://www.icloud.com")
                    append(HttpHeaders.Referrer, "https://www.icloud.com/")
                    append(HttpHeaders.UserAgent, USER_AGENT)
                }
                contentType(ContentType.Application.Json)
                setBody("null")
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw CloudKitErrors.fromThrowable(e)
        }

        val status = response.status.value
        val raw = response.body<String>()
        // The body carries the Apple ID, full name and dsid: never log it.
        AppLog.i(TAG) { "validate response: HTTP $status, ${raw.length}B" }
        CloudKitErrors.fromHttp(status, raw, response.headers[HttpHeaders.RetryAfter])?.let { throw it }

        // Like a browser, keep whatever refreshed cookies the server sent, so
        // the next bootstrap (and the WebView) sees them.
        cookieReader.storeSetCookies(response.headers.getAll(HttpHeaders.SetCookie).orEmpty())

        val parsed = try {
            LENIENT_JSON.decodeFromString(AccountLoginResponse.serializer(), raw)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            throw NotesError.Decode("validate: malformed JSON", details = "HTTP $status, ${raw.length} bytes", cause = e)
        }
        // A 2xx without dsInfo is what an expired-but-not-rejected session
        // looks like: treat it as needing a sign-in rather than a crash.
        val dsid = parsed.dsInfo?.dsid
            ?: throw NotesError.AuthExpired("setup.icloud.com /validate returned no dsInfo.dsid (HTTP $status)")
        val ckUrl = parsed.webservices?.ckdatabasews?.url
            ?: throw NotesError.Decode(
                "validate: no ckdatabasews endpoint",
                details = "setup.icloud.com /validate returned no webservices.ckdatabasews (HTTP $status)",
            )

        val refreshedHeader = cookieReader.cookieHeader() ?: cookieHeader

        ICloudSession(
            dsid = dsid,
            ckdatabasewsUrl = ckUrl.trimEnd('/'),
            cookieHeader = refreshedHeader,
            clientId = clientId,
            locale = parsed.dsInfo.locale ?: defaultLocale(),
            timezone = parsed.dsInfo.timezone ?: defaultTimeZone(),
        )
    }
}

/** The WebView cookie that marks a signed-in iCloud web session. */
const val AUTH_TOKEN_COOKIE = "X-APPLE-WEBAUTH-TOKEN"

/** Device locale in the `en_US` form iCloud uses. */
internal fun defaultLocale(): String = java.util.Locale.getDefault().toString().ifEmpty { "en_US" }

/** Device time zone id (e.g. `Europe/Berlin`). */
internal fun defaultTimeZone(): String = java.util.TimeZone.getDefault().id

@Serializable
internal data class AccountLoginResponse(
    val dsInfo: DsInfo? = null,
    val webservices: Webservices? = null,
)

@Serializable
internal data class DsInfo(
    val dsid: String? = null,
    val locale: String? = null,
    @SerialName("appleId") val appleId: String? = null,
    @SerialName("primaryEmail") val primaryEmail: String? = null,
    @SerialName("fullName") val fullName: String? = null,
    @SerialName("languageCode") val languageCode: String? = null,
    @SerialName("countryCode") val countryCode: String? = null,
    val timezone: String? = null,
)

@Serializable
internal data class Webservices(
    val ckdatabasews: WebserviceEndpoint? = null,
)

@Serializable
internal data class WebserviceEndpoint(
    val url: String? = null,
    val status: String? = null,
)
