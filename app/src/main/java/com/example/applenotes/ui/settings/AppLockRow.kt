package com.example.applenotes.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.applenotes.security.AppLockSettings
import com.example.applenotes.security.canUseAppLock
import com.example.applenotes.security.findFragmentActivity
import com.example.applenotes.security.showAppLockPrompt
import kotlinx.coroutines.launch

/**
 * App lock toggle (issue #16). Turning it on asks for the fingerprint / PIN
 * once, so nobody locks themselves out with a lock they can't open.
 */
@Composable
internal fun AppLockRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { AppLockSettings(context) }
    val enabled by settings.enabled.collectAsStateWithLifecycle(initialValue = AppLockSettings.DEFAULT_ENABLED)

    fun turnOn() {
        val activity = context.findFragmentActivity()
        if (activity == null || !canUseAppLock(context)) {
            Toast.makeText(context, "Set a screen lock on this device first", Toast.LENGTH_LONG).show()
            return
        }
        showAppLockPrompt(activity, title = "Turn on app lock", subtitle = "Confirm it's you") { ok ->
            if (ok) scope.launch { settings.setEnabled(true) }
        }
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("App lock", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ask for your fingerprint, face or screen lock when the app starts and after " +
                    "it has been in the background for more than a minute. Also hides notes " +
                    "from the recent-apps screen and blocks screenshots.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = enabled,
            onCheckedChange = { on ->
                if (on) turnOn() else scope.launch { settings.setEnabled(false) }
            },
        )
    }
}
