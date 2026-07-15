package com.copilot.remote

import android.app.Application
import com.copilot.remote.data.SettingsStore
import com.copilot.remote.data.WebSocketManager

class CopilotApplication : Application() {
    lateinit var settingsStore: SettingsStore
    lateinit var webSocketManager: WebSocketManager
    lateinit var notificationHelper: NotificationHelper

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        webSocketManager = WebSocketManager()
        notificationHelper = NotificationHelper(this)
    }
}
