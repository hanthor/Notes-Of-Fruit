package com.example.applenotes.integrations

import android.content.Context
import androidx.core.content.edit
import java.security.SecureRandom

/**
 * A random per-install secret carried by the "open note" shortcuts we
 * publish. [IntegrationEntryActivity] is exported (launchers and the share
 * sheet must reach it), so without this any app could open arbitrary notes
 * through it. Rotated on sign-out, which also invalidates pinned shortcuts.
 * Not backed up (allowBackup=false; the prefs file is excluded anyway).
 */
internal object ShortcutToken {
    private const val PREFS = "integrations"
    private const val KEY = "shortcut_token"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        return rotate(context)
    }

    fun rotate(context: Context): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY, token) }
        return token
    }
}
