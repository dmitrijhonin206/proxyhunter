package com.proxyhunter.telegram.checker.mtproto

import com.proxyhunter.telegram.data.checker.mtproto.MtProtoObfuscatedHandshakeBuilder
import com.proxyhunter.telegram.data.checker.mtproto.MtProtoSecretUnsupportedException
import com.proxyhunter.telegram.data.checker.mtproto.MtProtoTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MtProtoObfuscatedHandshakeBuilderTest {

    @Test
    fun `build without secret produces a 64 byte packet`() {
        val handshake = MtProtoObfuscatedHandshakeBuilder.build(MtProtoTransport.INTERMEDIATE, secretHex = null)

        assertEquals(64, handshake.packetToSend.size)
    }

    @Test
    fun `build with classic 16-byte hex secret succeeds`() {
        val secret = "ab".repeat(16) // 32 hex chars = 16 bytes

        val handshake = MtProtoObfuscatedHandshakeBuilder.build(MtProtoTransport.INTERMEDIATE, secret)

        assertEquals(64, handshake.packetToSend.size)
    }

    @Test
    fun `build with dd-prefixed padded secret succeeds`() {
        val secret = "dd" + "ab".repeat(16) // "dd" + 32 hex chars = 34 total

        val handshake = MtProtoObfuscatedHandshakeBuilder.build(MtProtoTransport.INTERMEDIATE, secret)

        assertEquals(64, handshake.packetToSend.size)
    }

    @Test
    fun `build with fake-tls ee-prefixed secret throws unsupported exception`() {
        val secret = "ee" + "ab".repeat(16)

        assertThrows(MtProtoSecretUnsupportedException::class.java) {
            MtProtoObfuscatedHandshakeBuilder.build(MtProtoTransport.INTERMEDIATE, secret)
        }
    }

    @Test
    fun `build with malformed secret length throws unsupported exception`() {
        assertThrows(MtProtoSecretUnsupportedException::class.java) {
            MtProtoObfuscatedHandshakeBuilder.build(MtProtoTransport.INTERMEDIATE, "abcd")
        }
    }

    @Test
    fun `generated header first byte is never the abridged reserved value 0xef`() {
        // Запускаем много раз, т.к. заголовок случайный — если бы проверка запрещённого
        // первого байта была сломана, это почти наверняка проявилось бы за 500 итераций.
        repeat(500) {
            val handshake = MtProtoObfuscatedHandshakeBuilder.build(MtProtoTransport.ABRIDGED, secretHex = null)
            // Первый байт пакета (до подстановки шифротекста в хвост) — это первый байт
            // исходного случайного заголовка, который не подменяется.
            assertNotEquals(0xef.toByte(), handshake.packetToSend[0])
        }
    }

    @Test
    fun `two consecutive builds produce different packets`() {
        val first = MtProtoObfuscatedHandshakeBuilder.build(MtProtoTransport.INTERMEDIATE, secretHex = null)
        val second = MtProtoObfuscatedHandshakeBuilder.build(MtProtoTransport.INTERMEDIATE, secretHex = null)

        assertFalse(first.packetToSend.contentEquals(second.packetToSend))
    }

    @Test
    fun `different transports produce same-length packets`() {
        // Байты [56:64) зависят от тега транспорта (он шифруется вместе с заголовком),
        // так что содержимое хвоста для разных транспортов отличается — здесь проверяем
        // только структурный инвариант (размер), т.к. сам заголовок случайный.
        val abridged = MtProtoObfuscatedHandshakeBuilder.build(MtProtoTransport.ABRIDGED, secretHex = null)
        val intermediate = MtProtoObfuscatedHandshakeBuilder.build(MtProtoTransport.INTERMEDIATE, secretHex = null)

        assertTrue(abridged.packetToSend.size == intermediate.packetToSend.size)
    }

    @Test
    fun `secret is case-insensitive`() {
        val lower = MtProtoObfuscatedHandshakeBuilder.build(MtProtoTransport.INTERMEDIATE, "ab".repeat(16))
        val upper = MtProtoObfuscatedHandshakeBuilder.build(MtProtoTransport.INTERMEDIATE, "AB".repeat(16))

        // Оба должны успешно построиться без исключения — сама проверка в том, что
        // построение не падает независимо от регистра hex-строки.
        assertEquals(64, lower.packetToSend.size)
        assertEquals(64, upper.packetToSend.size)
    }
}
