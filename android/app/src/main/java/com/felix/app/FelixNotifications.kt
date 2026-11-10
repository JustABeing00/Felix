package com.felix.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Notifications for hands-free mode: the ongoing listening notification and call results. */
object FelixNotifications {

    const val LISTENING_ID = 1001
    private const val RESULT_ID = 1002
    private const val CHANNEL_LISTENING = "felix_hands_free"
    private const val CHANNEL_RESULTS = "felix_call_results"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENING, "Hands-free listening", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, "Call results", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun listening(context: Context, text: String): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            context,
            1,
            FelixListeningService.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_LISTENING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Felix hands-free is on")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop listening", stop)
            .build()
    }

    fun updateListening(context: Context, text: String) {
        post(context, LISTENING_ID, listening(context, text))
    }

    /** Shows a result the user can act on with one tap, such as opening the dialer for a contact. */
    fun showResult(context: Context, title: String, text: String, dialUri: String?) {
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
        if (dialUri != null) {
            val dial = PendingIntent.getActivity(
                context,
                2,
                Intent(Intent.ACTION_DIAL, Uri.parse(dialUri)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentIntent(dial).addAction(0, "Open dialer", dial)
        }
        post(context, RESULT_ID, builder.build())
    }

    /**
     * The only place notifications are posted. areNotificationsEnabled() is false when the user has blocked
     * notifications or, on Android 13+, when POST_NOTIFICATIONS was refused. Felix then skips the notification
     * and the in-app status still shows everything.
     */
    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            manager.notify(id, notification)
        }
    }
}
