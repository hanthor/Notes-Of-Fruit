package com.example.applenotes.auth

import kotlinx.serialization.json.Json

/*
 * The platform-free part of the iCloud session: what the CloudKit client
 * needs to make requests. How the session is obtained (WebView cookies,
 * setup.icloud.com /validate) is Android-specific and stays in the app
 * (`auth/Session.kt`, `auth/CookieReader.kt`).
 */

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

/** Device locale in the `en_US` form iCloud uses. */
fun defaultLocale(): String = java.util.Locale.getDefault().toString().ifEmpty { "en_US" }

/** Device time zone id (e.g. `Europe/Berlin`). */
fun defaultTimeZone(): String = java.util.TimeZone.getDefault().id
