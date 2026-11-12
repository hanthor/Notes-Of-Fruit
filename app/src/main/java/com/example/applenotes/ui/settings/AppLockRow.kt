package com.example.applenotes.ui.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.example.applenotes.R
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
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { AppLockSettings(context) }
    val enabled by settings.enabled.collectAsStateWithLifecycle(initialValue = AppLockSettings.DEFAULT_ENABLED)

    fun turnOn() {
        val activity = context.findFragmentActivity()
        if (activity == null || !canUseAppLock(context)) {
            Toast.makeText(context, R.string.settings_app_lock_needs_screen_lock, Toast.LENGTH_LONG).show()
            return
        }
        showAppLockPrompt(
            activity,
            title = resources.getString(R.string.settings_app_lock_prompt_title),
            subtitle = resources.getString(R.string.settings_app_lock_prompt_subtitle),
        ) { ok ->
            if (ok) scope.launch { settings.setEnabled(true) }
        }
    }

    SettingsSwitchRow(
        title = stringResource(R.string.settings_app_lock),
        summary = stringResource(R.string.settings_app_lock_summary),
        checked = enabled,
        onCheckedChange = { on ->
            if (on) turnOn() else scope.launch { settings.setEnabled(false) }
        },
    )
}
