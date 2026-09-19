package com.hackmit.app

import android.app.Application
import com.hackmit.app.di.AppContainer

class StrokeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
