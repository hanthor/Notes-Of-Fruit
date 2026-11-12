package com.example.applenotes.integrations

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings "New note" tile: opens the app on a new, empty draft.
 *
 * When the device is locked, [unlockAndRun] makes the user unlock first, so
 * the app (and any note) is never shown over the lock screen. There is no
 * lock-screen capture UI on purpose.
 */
class NewNoteTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { launch() } else launch()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch() {
        val intent = Intent(this, IntegrationEntryActivity::class.java)
            .setAction(EntryActions.NEW_NOTE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
