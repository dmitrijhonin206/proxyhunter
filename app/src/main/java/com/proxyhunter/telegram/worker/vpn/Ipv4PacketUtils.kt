package com.proxyhunter.telegram.worker.vpn

const val IP_PROTOCOL_TCP = 6
const val IP_PROTOCOL_UDP = 17

data class Ipv4Header(
    val version: Int,
    val headerLength: Int, // в байтах (IHL * 4)
    val protocol: Int,
    val sourceIp: ByteArray,
    val destIp: ByteArray,
)
// Примечание: data class с ByteArray-полями даёт equals/hashCode по ссылке, а не по
// содержимому массива — это осознанно не переопределено, т.к. на равенство эти DTO
// нигде не сравниваются, только читаются их поля.

data class UdpDatagramInfo(
    val ipHeader: Ipv4Header,
    val sourcePort: Int,
    val destPort: Int,
    val payload: ByteArray,
)

// Разбор и сборка сырых IPv4-пакетов — вынесены в чистые функции без зависимости от
// TUN-дескриптора/сокетов специально для unit-тестирования (см. Ipv4PacketUtilsTest).
object Ipv4PacketUtils {

    fun parseIpv4Header(packet: ByteArray): Ipv4Header? {
        if (packet.size < 20) return null
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || packet.size < headerLength) return null
        val protocol = packet[9].toInt() and 0xFF
        return Ipv4Header(
            version = version,
            headerLength = headerLength,
            protocol = protocol,
            sourceIp = packet.copyOfRange(12, 16),
            destIp = packet.copyOfRange(16, 20),
        )
    }

    fun parseUdpDatagram(packet: ByteArray): UdpDatagramInfo? {
        val header = parseIpv4Header(packet) ?: return null
        if (header.protocol != IP_PROTOCOL_UDP) return null

        val udpOffset = header.headerLength
        if (packet.size < udpOffset + 8) return null

        val sourcePort = readUInt16(packet, udpOffset)
        val destPort = readUInt16(packet, udpOffset + 2)
        val udpLength = readUInt16(packet, udpOffset + 4)

        val payloadStart = udpOffset + 8
        val payloadEnd = (udpOffset + udpLength).coerceAtMost(packet.size)
        if (payloadStart > payloadEnd) return null

        return UdpDatagramInfo(
            ipHeader = header,
            sourcePort = sourcePort,
            destPort = destPort,
            payload = packet.copyOfRange(payloadStart, payloadEnd),
        )
    }

    // Собирает валидный IPv4+UDP пакет для обратной доставки в TUN-интерфейс (ответ от
    // сервера, переупакованный так, будто он пришёл напрямую). UDP-чексумма выставлена
    // в 0 — для IPv4 это допустимое значение "чексумма не используется" (RFC 768 §fields),
    // что позволяет не реализовывать её вычисление для payload произвольной длины.
    // IP-заголовочная чексумма ОБЯЗАТЕЛЬНА и вычисляется корректно (см. computeIpChecksum).
    fun buildIpv4UdpPacket(
        sourceIp: ByteArray,
        sourcePort: Int,
        destIp: ByteArray,
        destPort: Int,
        payload: ByteArray,
    ): ByteArray {
        require(sourceIp.size == 4 && destIp.size == 4) { "Ожидаются IPv4-адреса (4 байта)" }

        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength
        val bytes = ByteArray(totalLength)

        // --- IPv4-заголовок (20 байт, без опций) ---
        bytes[0] = 0x45                              // version=4, IHL=5
        bytes[1] = 0                                  // ToS
        writeUInt16(bytes, 2, totalLength)
        writeUInt16(bytes, 4, 0)                      // identification
        writeUInt16(bytes, 6, 0x4000)                 // flags=DF, fragment offset=0
        bytes[8] = 64                                 // TTL
        bytes[9] = IP_PROTOCOL_UDP.toByte()
        writeUInt16(bytes, 10, 0)                      // checksum — дозаполняется ниже
        sourceIp.copyInto(bytes, destinationOffset = 12)
        destIp.copyInto(bytes, destinationOffset = 16)

        // --- UDP-заголовок ---
        writeUInt16(bytes, 20, sourcePort)
        writeUInt16(bytes, 22, destPort)
        writeUInt16(bytes, 24, udpLength)
        writeUInt16(bytes, 26, 0)                      // checksum = 0, см. докстринг выше
        payload.copyInto(bytes, destinationOffset = 28)

        val checksum = computeIpChecksum(bytes, 0, 20)
        writeUInt16(bytes, 10, checksum)
        return bytes
    }

    // Стандартный алгоритм one's-complement суммы для IPv4 checksum (RFC 791): суммируем
    // 16-битные слова, сворачиваем перенос за пределы 16 бит, инвертируем результат.
    fun computeIpChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end) {
            val high = data[i].toInt() and 0xFF
            val low = if (i + 1 < end) data[i + 1].toInt() and 0xFF else 0
            sum += (high shl 8) or low
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    fun ipBytesToString(ip: ByteArray): String = ip.joinToString(".") { (it.toInt() and 0xFF).toString() }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun writeUInt16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value shr 8).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }
}
