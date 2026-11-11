package com.example.applenotes.ui

/**
 * Kept in the `ui` package because `debug/DebugReceiver` imports it from
 * here. The implementation lives in [com.example.applenotes.data.computeTitleAndSnippet].
 */
internal fun computeTitleAndSnippet(body: String): Pair<String, String> =
    com.example.applenotes.data.computeTitleAndSnippet(body)
