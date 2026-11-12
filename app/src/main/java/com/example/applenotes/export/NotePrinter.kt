package com.example.applenotes.export

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Print / "Save as PDF": render the note's HTML in an off-screen WebView and
 * hand its print adapter to [PrintManager]. JavaScript, file access and
 * network loads are all off; images are embedded as data: URIs.
 */
internal object NotePrinter {
    /**
     * Starts printing. The caller must keep the returned WebView referenced
     * until the system print dialog appears (it is only referenced by the
     * print job once the page has loaded).
     */
    fun print(activityContext: Context, jobName: String, html: String): WebView {
        val webView = WebView(activityContext)
        webView.settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = activityContext.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                pm?.print(jobName, view.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        return webView
    }
}
