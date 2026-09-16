package com.terrynamic.opendisplay

import android.app.Application

class ReceiverApp : Application() {
    lateinit var controller: ReceiverController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        controller = ReceiverController(this)
    }

    companion object {
        @Volatile
        lateinit var instance: ReceiverApp
            private set
    }
}
