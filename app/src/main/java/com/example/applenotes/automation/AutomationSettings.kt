package com.example.applenotes.automation

import android.content.Context
import androidx.core.content.edit

/** The automation API is gated by this flag. OFF by default; only the in-app Settings screen turns it on. */
interface AutomationGate {
    fun isEnabled(): Boolean
}

/**
 * Stored in private SharedPreferences (read synchronously by the
 * automation entry points). Never backed up (allowBackup=false and
 * the extraction rules exclude every domain), so a restored device starts
 * with automation off.
 */
class AutomationSettings(context: Context) : AutomationGate {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(enabled: Boolean) = prefs.edit { putBoolean(KEY_ENABLED, enabled) }

    companion object {
        /** Off until the user turns it on in Settings. */
        internal const val DEFAULT_ENABLED = false
        private const val PREFS = "automation"
        private const val KEY_ENABLED = "enabled"
    }
}
