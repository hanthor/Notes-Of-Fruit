package com.example.applenotes.ui.login

import android.annotation.SuppressLint
import com.example.applenotes.log.AppLog
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.applenotes.auth.AUTH_TOKEN_COOKIE
import com.example.applenotes.auth.USER_AGENT
import kotlinx.coroutines.delay

private const val TAG = "AppleNotesUi"
private const val ICLOUD_NOTES_URL = "https://www.icloud.com/notes"

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun LoginScreen(
    onCookiesArrived: () -> Unit,
    /**
     * After a session expired: the auth-token cookie that stopped working.
     * Only a *different* token counts as "signed in", so the stale cookie
     * isn't handed straight back to the bootstrap (which would loop).
     */
    staleToken: String? = null,
) {
    var detected by remember { mutableStateOf(false) }
    val currentOnCookiesArrived by rememberUpdatedState(onCookiesArrived)
    val check: () -> Unit = {
        if (!detected && hasFreshAuthToken(staleToken)) {
            detected = true
            AppLog.i(TAG) { "Login cookies detected" }
            currentOnCookiesArrived()
        }
    }
    // iCloud.com can refresh the token with an XHR after the page has
    // finished loading, so don't rely on onPageFinished alone.
    LaunchedEffect(staleToken) {
        while (!detected) {
            delay(2_000)
            check()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // Configure cookies for the embedded idmsa.apple.com auth iframe.
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = USER_AGENT
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Watch for X-APPLE-WEBAUTH-TOKEN landing in the cookie jar.
                        check()
                    }
                }
                loadUrl(ICLOUD_NOTES_URL)
            }
        },
    )
}

/** True when the jar holds an auth token that isn't [staleToken]. */
private fun hasFreshAuthToken(staleToken: String?): Boolean {
    val cookies = CookieManager.getInstance().getCookie("https://www.icloud.com").orEmpty()
    val token = cookies.split(";").map { it.trim() }
        .firstOrNull { it.startsWith("$AUTH_TOKEN_COOKIE=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotEmpty() }
        ?: return false
    return token != staleToken
}
