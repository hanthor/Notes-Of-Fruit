package com.example.applenotes.update

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.applenotes.BuildConfig
import com.example.applenotes.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Holds the "new version" banner for the list (issue #22). */
class UpdateViewModel(private val checker: UpdateChecker) : ViewModel() {
    private val _available = MutableStateFlow<AvailableUpdate?>(null)
    val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()
    private var started = false

    /** Once per process: show a known update, and ask GitHub if a day has passed. */
    fun checkOnLaunch() {
        if (started) return
        started = true
        _available.value = checker.storedUpdate()
        viewModelScope.launch { _available.value = checker.checkIfDue() }
    }

    fun dismiss() {
        _available.value?.let(checker::dismiss)
        _available.value = null
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return viewModelFactory {
                initializer {
                    UpdateViewModel(
                        UpdateChecker(PrefsUpdateStore(app), BuildConfig.VERSION_NAME, UpdateChecker::fetchFromGitHub),
                    )
                }
            }
        }
    }
}

/** "Version X is available" with a link to the release page; dismissible. */
@Composable
internal fun UpdateBanner(update: AvailableUpdate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(18.dp))
            Text(
                stringResource(R.string.update_available, update.version),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, update.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text(stringResource(R.string.update_view)) }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.update_dismiss))
            }
        }
    }
}
