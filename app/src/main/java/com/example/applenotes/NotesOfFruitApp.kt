package com.example.applenotes

import android.app.Application
import com.example.applenotes.integrations.LocalSurfaces
import com.example.applenotes.log.CoreLogBridge

/**
 * Process-wide setup that must happen before any component runs (activities,
 * receivers, workers, the tile service all start the process here first).
 * Keep it tiny: nothing blocking, no network (LocalSurfaces reads its
 * setting in the background).
 */
class NotesOfFruitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // :core (proto/ + client/) logs through CoreLog; send it to AppLog.
        CoreLogBridge.install()
        // Surfaces outside the app hide notes when the app lock is turned on.
        LocalSurfaces.start(this)
    }
}
