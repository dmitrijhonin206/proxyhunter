package com.proxyhunter.telegram.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.proxyhunter.telegram.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_ID = "proxyhunter_status"
private const val NOTIF_ID_PARSING = 1001
private const val NOTIF_ID_PROXY_DOWN = 1002

// Уведомления о завершении парсинга, о падении активного прокси и о новых рабочих
// прокси, согласно разделу "Дополнительный функционал" в ТЗ.
@Singleton
class ProxyHunterNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Статус прокси",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun notifyParsingFinished(newProxyCount: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Парсинг завершён")
            .setContentText("Найдено новых прокси: $newProxyCount")
            .setAutoCancel(true)
            .build()
        notify(NOTIF_ID_PARSING, notification)
    }

    fun notifyActiveProxyDown(replacementAvailable: Boolean) {
        val text = if (replacementAvailable) {
            "Текущий прокси перестал работать. Есть рабочая замена — откройте приложение, чтобы переключиться."
        } else {
            "Текущий прокси перестал работать. Подходящей замены пока не найдено."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Прокси недоступен")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notify(NOTIF_ID_PROXY_DOWN, notification)
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        // POST_NOTIFICATIONS запрашивается в UI (Android 13+) до вызова этого метода;
        // areNotificationsEnabled() — дополнительная защита от SecurityException на некоторых прошивках.
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
        }
    }
}
