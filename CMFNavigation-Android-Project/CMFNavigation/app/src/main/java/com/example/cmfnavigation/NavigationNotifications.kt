package com.example.cmfnavigation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NavigationNotifications {
    const val ROUTE_CHANNEL = "route_alerts"
    const val SERVICE_CHANNEL = "navigation_service"
    const val ROUTE_ID = 2201
    const val SERVICE_ID = 2202

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val route = NotificationChannel(ROUTE_CHANNEL, "Navigation alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Turn-by-turn navigation alerts for notification mirroring"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            val service = NotificationChannel(SERVICE_CHANNEL, "Navigation service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent status while navigation is running"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            manager.createNotificationChannel(route)
            manager.createNotificationChannel(service)
        }
    }

    fun serviceNotification(context: Context, text: String = "Navigation is running") =
        base(context, SERVICE_CHANNEL)
            .setContentTitle("CMF Navigation")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    fun postRoute(context: Context, title: String, body: String) {
        val notification = base(context, ROUTE_CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ROUTE_ID, notification)
    }

    fun postTest(context: Context) {
        createChannels(context)
        postRoute(context, "Test alert to watch", "If Nothing X mirrors this app, this test should appear on your CMF Watch Pro 2.")
    }

    fun cancelRoute(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ROUTE_ID)
    }

    private fun base(context: Context, channel: String): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel)
        else @Suppress("DEPRECATION") Notification.Builder(context)
    }
}
