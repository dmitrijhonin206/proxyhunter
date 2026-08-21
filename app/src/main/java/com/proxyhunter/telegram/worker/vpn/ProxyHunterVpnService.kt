package com.proxyhunter.telegram.worker.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.proxyhunter.telegram.MainActivity
import com.proxyhunter.telegram.R
import com.proxyhunter.telegram.domain.repository.ProxyRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val CHANNEL_ID = "proxyhunter_vpn"
private const val NOTIFICATION_ID = 2001
private const val VPN_ADDRESS = "10.10.10.2"
private const val VPN_ADDRESS_PREFIX_LENGTH = 32
private const val VPN_DNS = "1.1.1.1"
private const val VPN_MTU = 1500

// Оборачивает выбранный прокси в системный VPN-туннель Android (VpnService), чтобы через
// него шёл весь UDP-трафик устройства — в отличие от ручной настройки прокси в самом
// Telegram (GenerateTelegramConnectActionUseCase), это системный уровень, теоретически
// работающий для любого приложения. ВАЖНО: реально работает только UDP (см. докстринг
// TunnelEngine) и только для SOCKS5-прокси (см. докстринг там же про HTTP/MTProto) —
// вызывающий код (UI) обязан сам ограничивать, для каких прокси предлагать VPN-режим;
// сервис не проверяет протокол прокси повторно, доверяя вызывающей стороне.
@AndroidEntryPoint
class ProxyHunterVpnService : VpnService() {

    @Inject lateinit var proxyRepository: ProxyRepository
    @Inject lateinit var vpnStateHolder: VpnStateHolder

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var tunInterface: ParcelFileDescriptor? = null
    private var tunnelEngine: TunnelEngine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val proxyId = intent.getLongExtra(EXTRA_PROXY_ID, -1L)
                if (proxyId != -1L) startVpn(proxyId) else stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startVpn(proxyId: Long) {
        serviceScope.launch {
            val proxy = proxyRepository.getAllProxies().find { it.id == proxyId }
            if (proxy == null) {
                stopSelf()
                return@launch
            }

            val builder = Builder()
                .setSession("ProxyHunter")
                .setMtu(VPN_MTU)
                .addAddress(VPN_ADDRESS, VPN_ADDRESS_PREFIX_LENGTH)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(VPN_DNS)

            val fd = runCatching { builder.establish() }.getOrNull()
            if (fd == null) {
                // establish() возвращает null, если пользователь отозвал разрешение на VPN
                // между запросом и стартом сервиса, либо конфликтует другой активный VPN.
                stopSelf()
                return@launch
            }
            tunInterface = fd

            startForeground(NOTIFICATION_ID, buildNotification(proxy.ip, proxy.port))

            val engine = TunnelEngine(
                tunFd = fd,
                proxy = proxy,
                protectDatagramSocket = { socket -> protect(socket) },
                protectControlSocket = { socket -> protect(socket) },
            )
            tunnelEngine = engine
            vpnStateHolder.setActive(proxyId)
            engine.run() // блокирующий цикл чтения TUN — выполняется в serviceScope (Dispatchers.IO), не в главном потоке
        }
    }

    private fun stopVpn() {
        tunnelEngine?.stop()
        tunnelEngine = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        vpnStateHolder.setActive(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Система вызывает это, если пользователь отозвал разрешение на VPN через
    // системные настройки (Settings → VPN) в обход UI приложения — обязаны корректно
    // остановиться, иначе сервис останется висеть с закрытым, но не освобождённым туннелем.
    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun buildNotification(proxyIp: String, proxyPort: Int): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VPN активен")
            .setContentText("Через $proxyIp:$proxyPort")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.proxyhunter.telegram.vpn.START"
        const val ACTION_STOP = "com.proxyhunter.telegram.vpn.STOP"
        const val EXTRA_PROXY_ID = "proxy_id"
    }
}
