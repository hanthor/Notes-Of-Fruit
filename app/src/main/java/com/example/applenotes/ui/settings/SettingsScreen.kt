package com.example.applenotes.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.applenotes.R
import androidx.compose.material3.OutlinedButton
import com.example.applenotes.automation.AutomationSettings
import com.example.applenotes.diagnostics.copyDiagnosticReport
import com.example.applenotes.update.PrefsUpdateStore

/** Minimal settings: the automation API toggle (+ allowed apps) and the app lock (both off by default). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(onBack: () -> Unit, onOpenAllowedApps: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AutomationSettings(context) }
    var automationEnabled by remember { mutableStateOf(settings.isEnabled()) }
    // Android 13+: needed for the "‹App› wants to add notes" notification.
    // Declining is fine: requests still show up under Allowed apps.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val updateStore = remember { PrefsUpdateStore(context) }
    var updateCheckEnabled by remember { mutableStateOf(updateStore.enabled) }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawer_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_automation),
                summary = stringResource(R.string.settings_automation_summary),
                checked = automationEnabled,
                onCheckedChange = {
                    settings.setEnabled(it)
                    automationEnabled = it
                    if (it && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
            OutlinedButton(onClick = onOpenAllowedApps, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.settings_allowed_apps))
            }
            Spacer(Modifier.padding(top = 16.dp))
            Text(
                stringResource(R.string.settings_automation_details),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 24.dp))
            AppLockRow()
            Spacer(Modifier.padding(top = 24.dp))
            // Issue #22: opt-in, off by default; debug builds never check.
            SettingsSwitchRow(
                title = stringResource(R.string.settings_update_check),
                summary = stringResource(R.string.settings_update_check_summary),
                checked = updateCheckEnabled,
                onCheckedChange = {
                    updateStore.enabled = it
                    updateCheckEnabled = it
                },
            )
            Spacer(Modifier.padding(top = 24.dp))
            Text(stringResource(R.string.settings_diagnostics), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_diagnostics_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { copyDiagnosticReport(context) }, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.settings_diagnostics_copy))
            }
        }
    }
}

/**
 * A title + summary + switch row. The whole row is one toggle (one touch
 * target, read as "App lock, switch, off, …" by screen readers).
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        // The row handles the toggle.
        Switch(checked = checked, onCheckedChange = null)
    }
}
