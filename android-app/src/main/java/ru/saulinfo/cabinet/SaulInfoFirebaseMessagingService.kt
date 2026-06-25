package ru.saulinfo.cabinet

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.messaging.FirebaseMessagingService

class SaulInfoFirebaseMessagingService : FirebaseMessagingService() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        PushTokenRegistrar.subscribeToBroadcastTopic()
    }

    override fun onNewToken(token: String) {
        PushTokenRegistrar.saveFcmToken(this, token)
        PushTokenRegistrar.subscribeToBroadcastTopic()
        PushTokenRegistrar.registerSavedToken(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        NotificationChannels.ensure(this)
        val title = message.notification?.title ?: "Новое уведомление"
        val body = message.notification?.body ?: message.data["body"] ?: return
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            1003,
            launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NotificationChannels.BROADCASTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
