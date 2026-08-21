package com.proxyhunter.telegram.worker.vpn

import android.os.ParcelFileDescriptor
import com.proxyhunter.telegram.domain.model.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

private const val MAX_PACKET_SIZE = 32767
private const val CONTROL_CONNECT_TIMEOUT_MS = 5000
private const val UDP_SESSION_IDLE_TIMEOUT_MS = 60_000

// Ретранслирует трафик из TUN-интерфейса через выбранный прокси. РЕАЛЬНО релеится только
// UDP (включая DNS) — через SOCKS5 UDP ASSOCIATE (см. Socks5UdpAssociateClient), что уже
// само по себе ограничивает VPN-режим прокси типа SOCKS5 (ProxyHunterVpnService/UI не
// должны предлагать его для HTTP — там нет UDP-релея в принципе — и для MTProto — это
// Telegram-специфичный прикладной релей, а не общий IP/SOCKS-шлюз).
//
// TCP-пакеты распознаются (см. droppedTcpPackets), но НЕ ретранслируются. Корректный
// TCP-релей поверх сырых IP-пакетов требует полноценного пользовательского TCP/IP-стека
// (обработка SYN/ACK, ретрансмиссий, окон, MSS) — отдельный объёмный проект сам по себе,
// для которого в продакшене используют нативные библиотеки (go-tun2socks, badvpn-tun2socks,
// hev-socks5-tunnel) через JNI, а не написанный вручную на Kotlin разбор пакетов. Это
// осознанная и явно задокументированная граница текущей реализации, а не забытая
// недоделка — см. PLAN.md, раздел про VPN-режим.
class TunnelEngine(
    private val tunFd: ParcelFileDescriptor,
    private val proxy: Proxy,
    // Обе перегрузки VpnService.protect() (для DatagramSocket и для обычного TCP Socket)
    // передаются отдельными коллбэками — сам VpnService.protect() доступен только внутри
    // экземпляра VpnService, TunnelEngine намеренно не наследуется от него, чтобы оставаться
    // тестируемым без Android-окружения.
    private val protectDatagramSocket: (DatagramSocket) -> Boolean,
    private val protectControlSocket: (Socket) -> Boolean,
) {
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val sessions = mutableMapOf<UdpSessionKey, UdpSession>()
    private val sessionsMutex = Mutex()

    @Volatile private var running = false
    @Volatile var droppedTcpPackets: Long = 0
        private set

    fun run() {
        running = true
        val input = FileInputStream(tunFd.fileDescriptor)
        val output = FileOutputStream(tunFd.fileDescriptor)
        val buffer = ByteArray(MAX_PACKET_SIZE)

        while (running) {
            val length = runCatching { input.read(buffer) }.getOrDefault(-1)
            if (length <= 0) continue

            val packet = buffer.copyOf(length)
            val header = Ipv4PacketUtils.parseIpv4Header(packet) ?: continue // IPv6 и битые пакеты пропускаем

            when (header.protocol) {
                IP_PROTOCOL_UDP -> {
                    val datagram = Ipv4PacketUtils.parseUdpDatagram(packet) ?: continue
                    scope.launch { handleUdpDatagram(datagram, output) }
                }
                IP_PROTOCOL_TCP -> droppedTcpPackets++ // см. докстринг класса
                else -> Unit
            }
        }
    }

    fun stop() {
        running = false
        job.cancel()
        sessions.values.forEach {
            runCatching { it.controlSocket.close() }
            runCatching { it.udpSocket.close() }
        }
    }

    private suspend fun handleUdpDatagram(datagram: UdpDatagramInfo, output: FileOutputStream) {
        val key = UdpSessionKey(
            srcIp = Ipv4PacketUtils.ipBytesToString(datagram.ipHeader.sourceIp),
            srcPort = datagram.sourcePort,
            dstIp = Ipv4PacketUtils.ipBytesToString(datagram.ipHeader.destIp),
            dstPort = datagram.destPort,
        )

        val session = sessionsMutex.withLock {
            sessions[key] ?: runCatching { createSession(key, output) }.getOrNull()?.also { sessions[key] = it }
        } ?: return // не удалось открыть UDP ASSOCIATE через прокси — датаграмма молча теряется

        val wrapped = Socks5UdpAssociateClient.wrapUdpDatagram(
            destIp = datagram.ipHeader.destIp,
            destPort = datagram.destPort,
            payload = datagram.payload,
        )
        val outgoing = DatagramPacket(wrapped, wrapped.size, session.relayAddress)
        runCatching { session.udpSocket.send(outgoing) }
    }

    // Открывает SOCKS5 UDP ASSOCIATE-сессию через выбранный прокси и запускает читателя
    // ответов, который разворачивает их из SOCKS5 UDP-обёртки и записывает обратно в TUN.
    private fun createSession(key: UdpSessionKey, output: FileOutputStream): UdpSession {
        val associate = Socks5UdpAssociateClient.openSession(
            proxyHost = proxy.ip,
            proxyPort = proxy.port,
            username = proxy.username,
            password = proxy.password,
            connectTimeoutMs = CONTROL_CONNECT_TIMEOUT_MS,
        )
        protectControlSocket(associate.controlSocket) // иначе TCP control-соединение само уйдёт в туннель

        val udpSocket = DatagramSocket()
        protectDatagramSocket(udpSocket) // иначе UDP-трафик самого туннеля уйдёт в петлю через себя же

        val session = UdpSession(associate.controlSocket, udpSocket, associate.relayAddress as InetSocketAddress)

        scope.launch {
            val buf = ByteArray(MAX_PACKET_SIZE)
            while (running) {
                val incoming = DatagramPacket(buf, buf.size)
                val received = runCatching {
                    udpSocket.soTimeout = UDP_SESSION_IDLE_TIMEOUT_MS
                    udpSocket.receive(incoming)
                    true
                }.getOrDefault(false)
                if (!received) break

                val unwrapped = Socks5UdpAssociateClient.unwrapUdpDatagram(incoming.data, incoming.length) ?: continue
                val responsePacket = Ipv4PacketUtils.buildIpv4UdpPacket(
                    sourceIp = unwrapped.sourceIp,
                    sourcePort = unwrapped.sourcePort,
                    destIp = key.srcIp.toIpBytes(),
                    destPort = key.srcPort,
                    payload = unwrapped.payload,
                )
                runCatching { output.write(responsePacket) }
            }
            sessionsMutex.withLock { sessions.remove(key) }
            runCatching { udpSocket.close() }
            runCatching { session.controlSocket.close() }
        }

        return session
    }

    private fun String.toIpBytes(): ByteArray = split(".").map { it.toInt().toByte() }.toByteArray()
}

private data class UdpSessionKey(val srcIp: String, val srcPort: Int, val dstIp: String, val dstPort: Int)
private class UdpSession(val controlSocket: Socket, val udpSocket: DatagramSocket, val relayAddress: InetSocketAddress)
