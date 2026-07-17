package com.secretdialer.app

import android.app.Application

class DialerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        ContactCache.preload(this)
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            com.secretdialer.app.recorder.CallRecorder.performAutoDeleteIfNeeded(this)
        }
    }

    companion object {
        lateinit var instance: DialerApp
            private set
    }
}
