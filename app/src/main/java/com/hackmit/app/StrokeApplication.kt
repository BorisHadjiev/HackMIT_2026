package com.hackmit.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.hackmit.app.alerts.AlertManager
import com.hackmit.app.di.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class StrokeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AlertManager.ensureChannel(this)

        // Foreground-only monitoring: resume when the app comes to the foreground if
        // the user enabled it, and stop when the app goes to the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.appScope.launch {
                    if (container.settingsStore.monitoringEnabled.first()) {
                        container.speechMonitor.startMonitoring()
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                container.speechMonitor.stopMonitoring()
            }
        })
    }
}
