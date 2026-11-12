package com.example.applenotes.ui.login

import android.annotation.SuppressLint
import com.example.applenotes.log.AppLog
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.applenotes.auth.USER_AGENT

private const val TAG = "AppleNotesUi"
private const val ICLOUD_NOTES_URL = "https://www.icloud.com/notes"

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun LoginScreen(onCookiesArrived: () -> Unit) {
    var detected by remember { mutableStateOf(false) }
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
                        val cookies = CookieManager.getInstance().getCookie("https://www.icloud.com").orEmpty()
                        if (!detected && "X-APPLE-WEBAUTH-TOKEN" in cookies) {
                            detected = true
                            AppLog.i(TAG) { "Login cookies detected" }
                            onCookiesArrived()
                        }
                    }
                }
                loadUrl(ICLOUD_NOTES_URL)
            }
        },
    )
}
