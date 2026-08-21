package com.proxyhunter.telegram.vpn

import com.proxyhunter.telegram.worker.vpn.Socks5UdpAssociateClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5UdpAssociateFramingTest {

    @Test
    fun `wrapUdpDatagram produces RFC1928 header followed by payload`() {
        val destIp = byteArrayOf(8, 8, 8, 8)
        val payload = byteArrayOf(0x01, 0x02, 0x03)

        val wrapped = Socks5UdpAssociateClient.wrapUdpDatagram(destIp, destPort = 53, payload = payload)

        // RSV(2)=0, FRAG(1)=0, ATYP(1)=0x01, DST.ADDR(4), DST.PORT(2), затем payload
        assertEquals(0, wrapped[0].toInt())
        assertEquals(0, wrapped[1].toInt())
        assertEquals(0, wrapped[2].toInt()) // FRAG
        assertEquals(0x01, wrapped[3].toInt()) // ATYP = IPv4
        assertArrayEquals(destIp, wrapped.copyOfRange(4, 8))
        val port = ((wrapped[8].toInt() and 0xFF) shl 8) or (wrapped[9].toInt() and 0xFF)
        assertEquals(53, port)
        assertArrayEquals(payload, wrapped.copyOfRange(10, wrapped.size))
    }

    @Test
    fun `wrapUdpDatagram rejects non-IPv4 addresses`() {
        val sixByteAddress = ByteArray(6)

        val result = runCatching {
            Socks5UdpAssociateClient.wrapUdpDatagram(sixByteAddress, destPort = 53, payload = byteArrayOf())
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `unwrapUdpDatagram extracts source address port and payload`() {
        val wrapped = Socks5UdpAssociateClient.wrapUdpDatagram(
            destIp = byteArrayOf(1, 1, 1, 1), destPort = 443, payload = byteArrayOf(0x0A, 0x0B),
        )

        val result = Socks5UdpAssociateClient.unwrapUdpDatagram(wrapped, wrapped.size)

        assertNotNull(result)
        val unwrapped = requireNotNull(result)
        assertEquals(443, unwrapped.sourcePort)
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), unwrapped.sourceIp)
        assertArrayEquals(byteArrayOf(0x0A, 0x0B), unwrapped.payload)
    }

    @Test
    fun `wrap then unwrap round-trips address port and payload`() {
        val destIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 1)
        val payload = "dns query".toByteArray()

        val wrapped = Socks5UdpAssociateClient.wrapUdpDatagram(destIp, destPort = 5353, payload = payload)
        val result = Socks5UdpAssociateClient.unwrapUdpDatagram(wrapped, wrapped.size)

        assertNotNull(result)
        val unwrapped = requireNotNull(result)
        assertArrayEquals(destIp, unwrapped.sourceIp)
        assertEquals(5353, unwrapped.sourcePort)
        assertArrayEquals(payload, unwrapped.payload)
    }

    @Test
    fun `unwrapUdpDatagram returns null for truncated input shorter than header`() {
        val tooShort = ByteArray(5)

        assertNull(Socks5UdpAssociateClient.unwrapUdpDatagram(tooShort, tooShort.size))
    }

    @Test
    fun `unwrapUdpDatagram returns null for fragmented datagrams`() {
        val wrapped = Socks5UdpAssociateClient.wrapUdpDatagram(
            destIp = byteArrayOf(1, 1, 1, 1), destPort = 53, payload = byteArrayOf(0x01),
        )
        wrapped[2] = 0x01 // FRAG != 0 — фрагментация, которую мы намеренно не поддерживаем

        assertNull(Socks5UdpAssociateClient.unwrapUdpDatagram(wrapped, wrapped.size))
    }

    @Test
    fun `unwrapUdpDatagram returns null for non-IPv4 address type`() {
        val wrapped = Socks5UdpAssociateClient.wrapUdpDatagram(
            destIp = byteArrayOf(1, 1, 1, 1), destPort = 53, payload = byteArrayOf(0x01),
        )
        wrapped[3] = 0x04 // ATYP = IPv6 — не обрабатываем

        assertNull(Socks5UdpAssociateClient.unwrapUdpDatagram(wrapped, wrapped.size))
    }

    @Test
    fun `unwrapUdpDatagram handles empty payload`() {
        val wrapped = Socks5UdpAssociateClient.wrapUdpDatagram(
            destIp = byteArrayOf(1, 1, 1, 1), destPort = 53, payload = byteArrayOf(),
        )

        val result = Socks5UdpAssociateClient.unwrapUdpDatagram(wrapped, wrapped.size)

        assertNotNull(result)
        assertEquals(0, requireNotNull(result).payload.size)
    }
}
