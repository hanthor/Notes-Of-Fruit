package com.example.applenotes.diagnostics

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import com.example.applenotes.BuildConfig
import com.example.applenotes.R
import com.example.applenotes.data.AppGraph

/** Gathers [DiagnosticInfo] from the running app (counts and yes/no only). */
fun collectDiagnostics(context: Context): DiagnosticInfo {
    val repo = AppGraph.notesRepository(context)
    return DiagnosticInfo(
        appVersion = BuildConfig.VERSION_NAME,
        buildType = BuildConfig.BUILD_TYPE,
        appId = BuildConfig.APPLICATION_ID,
        androidRelease = Build.VERSION.RELEASE ?: "?",
        sdkInt = Build.VERSION.SDK_INT,
        deviceManufacturer = Build.MANUFACTURER ?: "?",
        deviceModel = Build.MODEL ?: "?",
        signedIn = repo.isSignedIn,
        cachedNotes = repo.notes.value.size,
        cachedFolders = repo.folders.value.size,
        pendingDrafts = repo.pendingDrafts.value.size,
        lastNote = LastOpenedNote.shape,
    )
}

/** "Copy diagnostic report" in Settings. */
fun copyDiagnosticReport(context: Context) {
    val report = buildDiagnosticReport(collectDiagnostics(context))
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(context.getString(R.string.settings_diagnostics_clip_label), report)
    // Nothing secret in it, but keep it out of clipboard previews anyway.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
    // Android 13+ confirms clipboard writes itself.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.settings_diagnostics_copied, Toast.LENGTH_SHORT).show()
    }
}
