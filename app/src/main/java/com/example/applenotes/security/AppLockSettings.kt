package com.example.applenotes.security

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.appLockStore by preferencesDataStore("app_lock")

/**
 * The app lock toggle (issue #16), OFF by default. In DataStore, excluded
 * from backup like everything else (see the manifest), so a restored device
 * starts with it off.
 */
class AppLockSettings(context: Context) {
    private val store = context.applicationContext.appLockStore

    /**
     * The stored value. If the store can't be read the lock counts as ON,
     * so a broken file doesn't silently expose notes; the lock screen still
     * offers turning it off when the device can't authenticate.
     */
    val enabled: Flow<Boolean> = store.data
        .map { it[KEY_ENABLED] ?: DEFAULT_ENABLED }
        .catch { emit(true) }
        .distinctUntilChanged()

    suspend fun setEnabled(value: Boolean) {
        store.edit { it[KEY_ENABLED] = value }
    }

    companion object {
        const val DEFAULT_ENABLED = false
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
    }
}
