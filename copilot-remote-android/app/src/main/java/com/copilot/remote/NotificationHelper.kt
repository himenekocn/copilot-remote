package com.copilot.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class NotificationHelper(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Copilot Remote events", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun show(title: String, message: String) {
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify((title + message).hashCode(), NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title).setContentText(message.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.take(1000))).setContentIntent(intent).setAutoCancel(true).build())
    }

    companion object { private const val CHANNEL = "copilot_remote_events" }
}
