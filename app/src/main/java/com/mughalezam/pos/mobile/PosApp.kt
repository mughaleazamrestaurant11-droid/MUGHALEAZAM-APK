package com.mughalezam.pos.mobile

import android.app.Application
import com.mughalezam.pos.mobile.api.PosApi
import com.mughalezam.pos.mobile.util.Prefs

/**
 * Holds the single [PosApi] every screen shares.
 *
 * There is exactly one connection to the main server for the whole app, so a
 * table list refreshed on one screen is already fresh on the next, and the
 * live socket is not opened and closed as the waiter walks through the app.
 */
class PosApp : Application() {

    lateinit var api: PosApi
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        api = PosApi(Prefs.load(this))
        api.user = Prefs.username(this)
    }

    companion object {
        lateinit var instance: PosApp
            private set
    }
}
