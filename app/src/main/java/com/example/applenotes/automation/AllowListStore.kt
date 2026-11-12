package com.example.applenotes.automation

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.applenotes.log.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Where the automation allow-list lives. Faked in tests. */
internal interface AllowListStore {
    val state: Flow<AllowListState>

    /** Atomically replace the state with [transform] of it; returns the new state. */
    suspend fun update(transform: (AllowListState) -> AllowListState): AllowListState

    suspend fun current(): AllowListState = state.first()
}

private val Context.automationAllowListStore by preferencesDataStore("automation_allow_list")

/**
 * The allow-list in DataStore, as one JSON value. Excluded from backup like
 * everything else (see the manifest), so a restored device trusts no app.
 * An unreadable value counts as an empty list: nothing is trusted.
 */
internal class DataStoreAllowListStore(context: Context) : AllowListStore {
    private val store = context.applicationContext.automationAllowListStore

    override val state: Flow<AllowListState> = store.data
        .map { decode(it[KEY]) }
        .catch {
            AppLog.w(TAG, "allow-list unreadable; trusting no app", it)
            emit(AllowListState())
        }
        .distinctUntilChanged()

    override suspend fun update(transform: (AllowListState) -> AllowListState): AllowListState {
        var result = AllowListState()
        store.edit { prefs ->
            result = transform(decode(prefs[KEY]))
            prefs[KEY] = JSON.encodeToString(AllowListState.serializer(), result)
        }
        return result
    }

    private fun decode(s: String?): AllowListState =
        s?.let { runCatching { JSON.decodeFromString(AllowListState.serializer(), it) }.getOrNull() } ?: AllowListState()

    companion object {
        private const val TAG = "NotesAutomation"
        private val KEY = stringPreferencesKey("state")
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
