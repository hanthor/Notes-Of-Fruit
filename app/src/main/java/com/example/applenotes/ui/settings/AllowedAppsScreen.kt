package com.example.applenotes.ui.settings

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.applenotes.R
import com.example.applenotes.automation.AllowListRules
import com.example.applenotes.automation.AllowListState
import com.example.applenotes.automation.AndroidApprovalNotifier
import com.example.applenotes.automation.DataStoreAllowListStore
import kotlinx.coroutines.launch

/**
 * Settings → Allowed apps (issue #21): the apps that may use the automation
 * API, and the ones that asked. Approving trusts the package name together
 * with the signing certificate it asked with; nothing the app sent is kept or
 * replayed, so the user re-runs their task afterwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllowedAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DataStoreAllowListStore(context) }
    val state by store.state.collectAsState(initial = AllowListState())
    val scope = rememberCoroutineScope()
    var confirmApprove by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    fun change(packageName: String, transform: (AllowListState) -> AllowListState) {
        AndroidApprovalNotifier.cancel(context, packageName)
        scope.launch { store.update(transform) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.allowed_apps_title)) },
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
            Text(
                stringResource(R.string.allowed_apps_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.pending.isNotEmpty()) {
                SectionTitle(stringResource(R.string.allowed_apps_pending))
                for (p in state.pending.sortedByDescending { it.firstSeenMs }) {
                    AppRow(
                        label = p.label,
                        packageName = p.packageName,
                        detail = stringResource(
                            R.string.allowed_apps_asked,
                            DateUtils.getRelativeTimeSpanString(p.firstSeenMs).toString(),
                        ),
                    ) {
                        TextButton(onClick = { change(p.packageName) { AllowListRules.remove(it, p.packageName) } }) {
                            Text(stringResource(R.string.allowed_apps_dismiss))
                        }
                        OutlinedButton(onClick = { confirmApprove = p.packageName }) {
                            Text(stringResource(R.string.allowed_apps_allow))
                        }
                    }
                }
            }
            SectionTitle(stringResource(R.string.allowed_apps_approved))
            if (state.approved.isEmpty()) {
                Text(
                    stringResource(R.string.allowed_apps_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (a in state.approved.sortedBy { it.label.lowercase() }) {
                AppRow(
                    label = a.label,
                    packageName = a.packageName,
                    detail = stringResource(
                        R.string.allowed_apps_certificate,
                        a.certSha256.joinToString(", ") { it.take(16) },
                    ),
                ) {
                    TextButton(onClick = { change(a.packageName) { AllowListRules.remove(it, a.packageName) } }) {
                        Text(stringResource(R.string.allowed_apps_remove))
                    }
                }
            }
        }
    }

    val approving = confirmApprove?.let { pkg -> state.pending.firstOrNull { it.packageName == pkg } }
    if (approving != null) {
        AlertDialog(
            onDismissRequest = { confirmApprove = null },
            title = { Text(stringResource(R.string.allowed_apps_confirm_title, approving.label)) },
            text = { Text(stringResource(R.string.allowed_apps_confirm_text, approving.label, approving.packageName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmApprove = null
                    val now = System.currentTimeMillis()
                    change(approving.packageName) { AllowListRules.approve(it, approving.packageName, now) }
                }) { Text(stringResource(R.string.allowed_apps_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmApprove = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.padding(top = 20.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

@Composable
private fun AppRow(label: String, packageName: String, detail: String, actions: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        actions()
    }
}
