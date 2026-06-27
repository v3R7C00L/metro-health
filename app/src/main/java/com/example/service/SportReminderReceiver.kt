package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity

class SportReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sharedPrefs = context.getSharedPreferences("health_prefs", Context.MODE_PRIVATE)
        val lastNotified = sharedPrefs.getLong("last_sport_reminder_time", 0L)
        val now = System.currentTimeMillis()
        if (now - lastNotified < 2000L) {
            // Prevent duplicate notification delivery within 2 seconds
            return
        }
        sharedPrefs.edit().putLong("last_sport_reminder_time", now).apply()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "sporting_reminders_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Sporting Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Periodic sporting and fitness encouragement"
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Sport Reminder")
            .setContentText("yo you need to sport you know")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
            
        notificationManager.notify(3001, notification)
    }
}
