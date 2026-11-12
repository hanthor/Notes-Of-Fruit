package com.example.applenotes.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Fingerprint / face, or the device PIN / pattern / password as a fallback.
 * BIOMETRIC_WEAK | DEVICE_CREDENTIAL is the combination androidx.biometric
 * supports on every API level from our minSdk (26) up.
 */
internal const val APP_LOCK_AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

/** True when the device has a screen lock or enrolled biometrics the app lock can use. */
internal fun canUseAppLock(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(APP_LOCK_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * Show the system prompt. [onResult] gets true on success, false on cancel / error.
 *
 * Deliberately not bound to a CryptoObject: the app lock is a privacy screen
 * (hides the UI, Recents thumbnails and screenshots), not encryption. The
 * local cache and drafts are not encrypted at rest, so there is no key for a
 * CryptoObject to unlock, and someone who can hook the app process could
 * bypass this callback. See docs/advice.md "Known limitations" for what a
 * real at-rest-encryption design would cost (BIOMETRIC_STRONG only, PIN
 * fallback Android 11+, no background draft sync while locked).
 */
internal fun showAppLockPrompt(
    activity: FragmentActivity,
    title: String,
    subtitle: String?,
    onResult: (Boolean) -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)

            // A rejected fingerprint: the prompt stays up and lets the user retry.
            override fun onAuthenticationFailed() = Unit
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .apply { subtitle?.let { setSubtitle(it) } }
        .setAllowedAuthenticators(APP_LOCK_AUTHENTICATORS)
        .build()
    prompt.authenticate(info)
}

/** The FragmentActivity behind a Compose [Context], if any. */
internal fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}
