package com.copilot.remote

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class CopilotFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "Copilot Remote"
        val body = message.notification?.body ?: message.data["message"] ?: message.data["body"] ?: return
        (application as CopilotApplication).notificationHelper.show(title, body)
    }

    override fun onNewToken(token: String) {
        getSharedPreferences("fcm", MODE_PRIVATE).edit().putString("token", token).apply()
    }
}
