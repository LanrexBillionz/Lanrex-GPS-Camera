package com.lanrex.sitecam

import android.app.Application
import android.content.Context
import com.lanrex.sitecam.util.CrashLog

class SiteCamApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        container = AppContainer(this)
        container.onAppStart()
    }
}

/** The app-wide objects (repositories, database, settings). */
val Context.appContainer: AppContainer
    get() = (applicationContext as SiteCamApp).container
