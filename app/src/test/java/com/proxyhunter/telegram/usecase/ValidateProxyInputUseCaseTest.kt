package com.proxyhunter.telegram.usecase

import com.proxyhunter.telegram.domain.model.ProxyProtocol
import com.proxyhunter.telegram.domain.usecase.ValidateProxyInputUseCase
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ValidateProxyInputUseCaseTest {

    private val validate = ValidateProxyInputUseCase()

    @Test
    fun `valid socks5 input with no secret field required passes`() {
        val result = validate("203.0.113.10", "1080", ProxyProtocol.SOCKS5, secretText = "")

        assertNull(result.ipError)
        assertNull(result.portError)
        assertNull(result.secretError)
        assert(result.isValid)
    }

    @Test
    fun `blank ip is rejected`() {
        val result = validate("", "1080", ProxyProtocol.HTTP, "")

        assertNotNull(result.ipError)
    }

    @Test
    fun `ip with wrong octet count is rejected`() {
        val result = validate("1.2.3", "1080", ProxyProtocol.HTTP, "")

        assertNotNull(result.ipError)
    }

    @Test
    fun `ip with out-of-range octet is rejected`() {
        val result = validate("1.2.3.999", "1080", ProxyProtocol.HTTP, "")

        assertNotNull(result.ipError)
    }

    @Test
    fun `ip with leading zero octet is rejected`() {
        val result = validate("192.168.001.1", "1080", ProxyProtocol.HTTP, "")

        assertNotNull(result.ipError)
    }

    @Test
    fun `valid ip passes`() {
        val result = validate("8.8.8.8", "1080", ProxyProtocol.HTTP, "")

        assertNull(result.ipError)
    }

    @Test
    fun `non-numeric port is rejected`() {
        val result = validate("8.8.8.8", "abc", ProxyProtocol.HTTP, "")

        assertNotNull(result.portError)
    }

    @Test
    fun `port zero is rejected`() {
        val result = validate("8.8.8.8", "0", ProxyProtocol.HTTP, "")

        assertNotNull(result.portError)
    }

    @Test
    fun `port above 65535 is rejected`() {
        val result = validate("8.8.8.8", "70000", ProxyProtocol.HTTP, "")

        assertNotNull(result.portError)
    }

    @Test
    fun `port at boundaries 1 and 65535 is accepted`() {
        assertNull(validate("8.8.8.8", "1", ProxyProtocol.HTTP, "").portError)
        assertNull(validate("8.8.8.8", "65535", ProxyProtocol.HTTP, "").portError)
    }

    @Test
    fun `empty mtproto secret is allowed`() {
        val result = validate("8.8.8.8", "443", ProxyProtocol.MTPROTO, "")

        assertNull(result.secretError)
    }

    @Test
    fun `mtproto secret with non-hex characters is rejected`() {
        val result = validate("8.8.8.8", "443", ProxyProtocol.MTPROTO, "zz11223344556677889900112233445566")

        assertNotNull(result.secretError)
    }

    @Test
    fun `mtproto classic 32-char hex secret is accepted`() {
        val result = validate("8.8.8.8", "443", ProxyProtocol.MTPROTO, "ab".repeat(16))

        assertNull(result.secretError)
    }

    @Test
    fun `mtproto dd-prefixed secret is accepted`() {
        val result = validate("8.8.8.8", "443", ProxyProtocol.MTPROTO, "dd" + "ab".repeat(16))

        assertNull(result.secretError)
    }

    @Test
    fun `mtproto ee-prefixed secret is accepted at the format level`() {
        // Формат валиден сам по себе, даже если фактическая проверка через handshake
        // такой секрет не поддерживает (см. MtProtoObfuscatedHandshakeBuilder) — это
        // намеренно: пользователь может ввести валидный ee-секрет, просто проверка
        // деградирует до TCP-only, а не блокируется на этапе ввода.
        val result = validate("8.8.8.8", "443", ProxyProtocol.MTPROTO, "ee" + "ab".repeat(16))

        assertNull(result.secretError)
    }

    @Test
    fun `mtproto secret with wrong length is rejected`() {
        val result = validate("8.8.8.8", "443", ProxyProtocol.MTPROTO, "ab".repeat(10))

        assertNotNull(result.secretError)
    }

    @Test
    fun `secret is ignored for non-mtproto protocols even if malformed`() {
        val result = validate("8.8.8.8", "1080", ProxyProtocol.SOCKS5, secretText = "not-hex-at-all")

        assertNull(result.secretError)
    }
}
