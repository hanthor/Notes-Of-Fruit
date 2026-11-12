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

/** Show the system prompt. [onResult] gets true on success, false on cancel / error. */
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
