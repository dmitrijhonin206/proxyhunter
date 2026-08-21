package com.proxyhunter.telegram.vpn

import com.proxyhunter.telegram.worker.vpn.IP_PROTOCOL_TCP
import com.proxyhunter.telegram.worker.vpn.IP_PROTOCOL_UDP
import com.proxyhunter.telegram.worker.vpn.Ipv4PacketUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv4PacketUtilsTest {

    @Test
    fun `parseIpv4Header rejects non-IPv4 version`() {
        val packet = ByteArray(20)
        packet[0] = 0x65 // version = 6 (IPv6), IHL = 5

        assertNull(Ipv4PacketUtils.parseIpv4Header(packet))
    }

    @Test
    fun `parseIpv4Header rejects packet shorter than 20 bytes`() {
        assertNull(Ipv4PacketUtils.parseIpv4Header(ByteArray(10)))
    }

    @Test
    fun `parseIpv4Header extracts protocol and addresses correctly`() {
        val packet = buildMinimalIpv4Header(protocol = IP_PROTOCOL_UDP, src = "1.2.3.4", dst = "5.6.7.8")

        val result = Ipv4PacketUtils.parseIpv4Header(packet)

        assertNotNull(result)
        val header = requireNotNull(result)
        assertEquals(4, header.version)
        assertEquals(20, header.headerLength)
        assertEquals(IP_PROTOCOL_UDP, header.protocol)
        assertEquals("1.2.3.4", Ipv4PacketUtils.ipBytesToString(header.sourceIp))
        assertEquals("5.6.7.8", Ipv4PacketUtils.ipBytesToString(header.destIp))
    }

    @Test
    fun `parseUdpDatagram returns null for TCP packets`() {
        val packet = buildMinimalIpv4Header(protocol = IP_PROTOCOL_TCP, src = "1.1.1.1", dst = "2.2.2.2")

        assertNull(Ipv4PacketUtils.parseUdpDatagram(packet))
    }

    @Test
    fun `parseUdpDatagram extracts ports and payload`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val packet = buildFullUdpPacket(srcPort = 53, dstPort = 5000, payload = payload)

        val result = Ipv4PacketUtils.parseUdpDatagram(packet)

        assertNotNull(result)
        val datagram = requireNotNull(result)
        assertEquals(53, datagram.sourcePort)
        assertEquals(5000, datagram.destPort)
        assertArrayEquals(payload, datagram.payload)
    }

    @Test
    fun `buildIpv4UdpPacket then parseUdpDatagram round-trips payload and ports`() {
        val payload = "hello".toByteArray()
        val srcIp = byteArrayOf(10, 0, 0, 1)
        val dstIp = byteArrayOf(10, 0, 0, 2)

        val packet = Ipv4PacketUtils.buildIpv4UdpPacket(
            sourceIp = srcIp, sourcePort = 1234,
            destIp = dstIp, destPort = 4321,
            payload = payload,
        )

        val result = Ipv4PacketUtils.parseUdpDatagram(packet)

        assertNotNull(result)
        val parsed = requireNotNull(result)
        assertEquals(1234, parsed.sourcePort)
        assertEquals(4321, parsed.destPort)
        assertArrayEquals(payload, parsed.payload)
        assertEquals("10.0.0.1", Ipv4PacketUtils.ipBytesToString(parsed.ipHeader.sourceIp))
        assertEquals("10.0.0.2", Ipv4PacketUtils.ipBytesToString(parsed.ipHeader.destIp))
    }

    @Test
    fun `buildIpv4UdpPacket produces a valid IP header checksum`() {
        val packet = Ipv4PacketUtils.buildIpv4UdpPacket(
            sourceIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 1),
            sourcePort = 80,
            destIp = byteArrayOf(8, 8, 8, 8),
            destPort = 53,
            payload = byteArrayOf(0x11, 0x22),
        )

        // Свойство корректной IPv4 checksum: если посчитать чексумму по всему 20-байтовому
        // заголовку ВКЛЮЧАЯ уже проставленное поле checksum, результат должен быть 0
        // (это стандартный self-check алгоритма ones-complement из RFC 791).
        val selfCheck = Ipv4PacketUtils.computeIpChecksum(packet, 0, 20)

        assertEquals(0, selfCheck)
    }

    @Test
    fun `computeIpChecksum handles odd-length input by zero-padding the last byte`() {
        // [0x01, 0x02, 0x03] -> слова: 0x0102, затем 0x03 дополняется нулём -> 0x0300.
        // sum = 0x0102 + 0x0300 = 0x0402 (переноса за 16 бит нет), checksum = ~0x0402 & 0xFFFF = 0xFBFD.
        val checksum = Ipv4PacketUtils.computeIpChecksum(byteArrayOf(0x01, 0x02, 0x03), 0, 3)

        assertEquals(0xFBFD, checksum)
    }

    @Test
    fun `ipBytesToString formats unsigned bytes correctly`() {
        val ip = byteArrayOf(192.toByte(), 168.toByte(), 1, 255.toByte())

        assertEquals("192.168.1.255", Ipv4PacketUtils.ipBytesToString(ip))
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }

    private fun buildMinimalIpv4Header(protocol: Int, src: String, dst: String): ByteArray {
        val packet = ByteArray(20)
        packet[0] = 0x45 // version=4, IHL=5
        packet[9] = protocol.toByte()
        src.split(".").map { it.toInt().toByte() }.toByteArray().copyInto(packet, destinationOffset = 12)
        dst.split(".").map { it.toInt().toByte() }.toByteArray().copyInto(packet, destinationOffset = 16)
        return packet
    }

    private fun buildFullUdpPacket(srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        val packet = ByteArray(20 + udpLength)
        packet[0] = 0x45
        packet[9] = IP_PROTOCOL_UDP.toByte()
        packet[20] = (srcPort shr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (dstPort shr 8).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = (udpLength shr 8).toByte()
        packet[25] = (udpLength and 0xFF).toByte()
        payload.copyInto(packet, destinationOffset = 28)
        return packet
    }
}
