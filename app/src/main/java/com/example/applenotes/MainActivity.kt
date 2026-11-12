package com.example.applenotes

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.applenotes.security.AppLock
import com.example.applenotes.security.AppLockPolicy
import com.example.applenotes.security.AppLockSettings
import com.example.applenotes.security.LockScreen
import com.example.applenotes.security.canUseAppLock
import com.example.applenotes.security.showAppLockPrompt
import com.example.applenotes.theme.NotesOfFruitTheme
import com.example.applenotes.ui.AppleNotesApp
import kotlinx.coroutines.launch

/**
 * A FragmentActivity (not a plain ComponentActivity) because
 * androidx.biometric's prompt needs one, for the optional app lock.
 */
class MainActivity : FragmentActivity() {
    private val lockPolicy get() = AppLock.policy
    private lateinit var lockSettings: AppLockSettings

    /** A prompt is on screen; don't stack another. */
    private var prompting = false

    /**
     * The prompt was already shown automatically for this lock. Cancelling it
     * then leaves the lock screen with its Unlock button instead of
     * re-prompting on every resume. Reset when unlocked or backgrounded.
     */
    private var autoPrompted = false

    /** Whether the device can authenticate at all (re-checked when locking). */
    private var canAuthenticate by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run BEFORE super.onCreate to back-port the Android-12+ splash
        // API to older releases. Theme is configured in res/values/themes.xml
        // (Theme.App.Starting) and switches to Theme.NotesOfFruit once the
        // first frame of content is ready.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lockSettings = AppLockSettings(this)
        // Keep the splash up until the app lock setting is known, so no note
        // content can flash before the lock screen.
        splash.setKeepOnScreenCondition { lockPolicy.state.value == AppLockPolicy.State.UNKNOWN }

        lifecycleScope.launch {
            lockSettings.enabled.collect { enabled ->
                // While the lock is on, keep note content out of screenshots
                // and the Recents thumbnail.
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                lockPolicy.setEnabled(enabled)
            }
        }
        // Ask for the fingerprint / PIN as soon as we're locked and visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                lockPolicy.state.collect {
                    when {
                        it == AppLockPolicy.State.UNLOCKED -> autoPrompted = false
                        it == AppLockPolicy.State.LOCKED && !autoPrompted -> {
                            autoPrompted = true
                            promptUnlock()
                        }
                    }
                }
            }
        }

        setContent {
            NotesOfFruitTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val lockState by lockPolicy.state.collectAsStateWithLifecycle()
                    when (lockState) {
                        // Splash is still showing.
                        AppLockPolicy.State.UNKNOWN -> Unit
                        AppLockPolicy.State.LOCKED -> LockScreen(
                            canAuthenticate = canAuthenticate,
                            onUnlock = ::promptUnlock,
                            onTurnOff = { lifecycleScope.launch { lockSettings.setEnabled(false) } },
                        )
                        AppLockPolicy.State.UNLOCKED -> AppleNotesApp()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lockPolicy.onForeground()
    }

    override fun onStop() {
        super.onStop()
        lockPolicy.onBackground()
        // Not while our own prompt is up: before Android 11 the device
        // credential screen is a separate activity that stops this one.
        if (!prompting) autoPrompted = false
    }

    private fun promptUnlock() {
        if (prompting) return
        canAuthenticate = canUseAppLock(this)
        if (!canAuthenticate) return
        prompting = true
        showAppLockPrompt(this, title = getString(R.string.lock_prompt_title), subtitle = null) { ok ->
            prompting = false
            if (ok) lockPolicy.onAuthenticated()
        }
    }
}
