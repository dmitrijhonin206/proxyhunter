package com.proxyhunter.telegram.worker.vpn

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress

private const val SOCKS_VERSION = 0x05
private const val CMD_UDP_ASSOCIATE = 0x03
private const val ATYP_IPV4 = 0x01
private const val AUTH_NONE = 0x00
private const val AUTH_USER_PASS = 0x02
private const val REPLY_SUCCESS = 0x00

class Socks5Error(message: String) : Exception(message)

// Результат UDP ASSOCIATE: адрес, на который нужно слать UDP-датаграммы, обёрнутые в
// SOCKS5 UDP-заголовок (см. wrapUdpDatagram/unwrapUdpDatagram). TCP-соединение control
// нужно держать открытым всё время сессии — RFC 1928 §7 требует этого явно: закрытие
// TCP-соединения завершает UDP-ассоциацию на стороне прокси.
data class UdpAssociateSession(
    val controlSocket: Socket,
    val relayAddress: SocketAddress,
)

// Реализация клиентской части SOCKS5 UDP ASSOCIATE (RFC 1928 §4, §6, §7; аутентификация
// user/pass — RFC 1929). Используется TunnelEngine для релея UDP-трафика VPN-туннеля
// через выбранный SOCKS5-прокси, вместо отправки пакетов напрямую в интернет.
object Socks5UdpAssociateClient {

    fun openSession(
        proxyHost: String,
        proxyPort: Int,
        username: String?,
        password: String?,
        connectTimeoutMs: Int,
    ): UdpAssociateSession {
        val socket = Socket()
        socket.connect(java.net.InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs)
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        negotiateAuth(input, output, username, password)
        val relayAddress = sendUdpAssociate(input, output)

        return UdpAssociateSession(socket, relayAddress)
    }

    private fun negotiateAuth(input: InputStream, output: OutputStream, username: String?, password: String?) {
        val hasCredentials = !username.isNullOrEmpty()
        val methods = if (hasCredentials) byteArrayOf(AUTH_NONE.toByte(), AUTH_USER_PASS.toByte())
        else byteArrayOf(AUTH_NONE.toByte())

        output.write(byteArrayOf(SOCKS_VERSION.toByte(), methods.size.toByte(), *methods))
        output.flush()

        val serverChoice = readExactly(input, 2)
        if (serverChoice[0].toInt() and 0xFF != SOCKS_VERSION) throw Socks5Error("Неверная версия SOCKS в ответе сервера")

        when (serverChoice[1].toInt() and 0xFF) {
            AUTH_NONE -> Unit
            AUTH_USER_PASS -> {
                if (!hasCredentials) throw Socks5Error("Прокси требует авторизацию, но логин/пароль не заданы")
                sendUserPassAuth(input, output, username!!, password.orEmpty())
            }
            0xFF -> throw Socks5Error("Прокси не поддерживает ни один из предложенных методов авторизации")
            else -> throw Socks5Error("Неподдерживаемый метод авторизации SOCKS5")
        }
    }

    private fun sendUserPassAuth(input: InputStream, output: OutputStream, username: String, password: String) {
        val userBytes = username.toByteArray(Charsets.UTF_8)
        val passBytes = password.toByteArray(Charsets.UTF_8)
        val request = byteArrayOf(0x01, userBytes.size.toByte(), *userBytes, passBytes.size.toByte(), *passBytes)
        output.write(request)
        output.flush()

        val reply = readExactly(input, 2)
        if (reply[1].toInt() and 0xFF != 0x00) throw Socks5Error("Авторизация SOCKS5 отклонена прокси")
    }

    private fun sendUdpAssociate(input: InputStream, output: OutputStream): SocketAddress {
        // DST.ADDR/DST.PORT в запросе — это ожидаемый адрес источника UDP-трафика клиента;
        // 0.0.0.0:0 означает "определи сам по входящим датаграммам", что и делают почти
        // все реализации SOCKS5-серверов.
        val request = byteArrayOf(
            SOCKS_VERSION.toByte(), CMD_UDP_ASSOCIATE.toByte(), 0x00, ATYP_IPV4.toByte(),
            0, 0, 0, 0, // DST.ADDR = 0.0.0.0
            0, 0, // DST.PORT = 0
        )
        output.write(request)
        output.flush()

        val header = readExactly(input, 4)
        if (header[0].toInt() and 0xFF != SOCKS_VERSION) throw Socks5Error("Неверная версия SOCKS в ответе на UDP ASSOCIATE")
        val reply = header[1].toInt() and 0xFF
        if (reply != REPLY_SUCCESS) throw Socks5Error("UDP ASSOCIATE отклонён прокси, код ответа: $reply")

        val atyp = header[3].toInt() and 0xFF
        val relayIp = when (atyp) {
            ATYP_IPV4 -> InetAddress.getByAddress(readExactly(input, 4))
            else -> throw Socks5Error("Неподдерживаемый тип адреса в ответе прокси (ATYP=$atyp)")
        }
        val portBytes = readExactly(input, 2)
        val relayPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        return java.net.InetSocketAddress(relayIp, relayPort)
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n == -1) throw Socks5Error("Соединение с прокси закрыто раньше времени")
            read += n
        }
        return buffer
    }

    // Оборачивает исходящую UDP-датаграмму в формат SOCKS5 UDP-запроса (RFC 1928 §7):
    // RSV(2)=0, FRAG(1)=0 (фрагментация не поддерживается — не нужна для наших целей),
    // ATYP(1), DST.ADDR, DST.PORT(2), затем полезная нагрузка. Только IPv4-адресация.
    fun wrapUdpDatagram(destIp: ByteArray, destPort: Int, payload: ByteArray): ByteArray {
        require(destIp.size == 4) { "Ожидается IPv4-адрес (4 байта)" }
        val header = ByteArray(10)
        // header[0..1] = RSV = 0 (уже 0 по умолчанию)
        header[2] = 0x00 // FRAG
        header[3] = ATYP_IPV4.toByte()
        destIp.copyInto(header, destinationOffset = 4)
        header[8] = (destPort shr 8).toByte()
        header[9] = (destPort and 0xFF).toByte()
        return header + payload
    }

    data class UnwrappedDatagram(val sourceIp: ByteArray, val sourcePort: Int, val payload: ByteArray)

    // Разбирает входящую SOCKS5 UDP-датаграмму от прокси. Поле DST в этом формате при
    // получении ответа фактически означает "откуда пришли данные" (RFC 1928 §7 переиспользует
    // одну и ту же структуру заголовка в обе стороны).
    fun unwrapUdpDatagram(raw: ByteArray, length: Int): UnwrappedDatagram? {
        if (length < 10) return null
        val frag = raw[2].toInt() and 0xFF
        if (frag != 0x00) return null // фрагментированные датаграммы не поддерживаем
        val atyp = raw[3].toInt() and 0xFF
        if (atyp != ATYP_IPV4) return null // IPv6/домены в ответе не ожидаем и не обрабатываем

        val sourceIp = raw.copyOfRange(4, 8)
        val sourcePort = ((raw[8].toInt() and 0xFF) shl 8) or (raw[9].toInt() and 0xFF)
        val payload = raw.copyOfRange(10, length)
        return UnwrappedDatagram(sourceIp, sourcePort, payload)
    }
}
