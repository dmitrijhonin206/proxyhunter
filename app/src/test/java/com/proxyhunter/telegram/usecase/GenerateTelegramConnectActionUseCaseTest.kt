package com.proxyhunter.telegram.usecase

import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.model.ProxyProtocol
import com.proxyhunter.telegram.domain.usecase.GenerateTelegramConnectActionUseCase
import com.proxyhunter.telegram.domain.usecase.TelegramConnectAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateTelegramConnectActionUseCaseTest {

    private val useCase = GenerateTelegramConnectActionUseCase()

    @Test
    fun `mtproto proxy with secret produces deep link with server port and secret`() {
        val proxy = proxy(protocol = ProxyProtocol.MTPROTO, ip = "1.2.3.4", port = 443, secret = "ee1234abcd")

        val action = useCase(proxy)

        assertTrue(action is TelegramConnectAction.DeepLink)
        val uri = (action as TelegramConnectAction.DeepLink).uri
        assertEquals("tg://proxy?server=1.2.3.4&port=443&secret=ee1234abcd", uri)
    }

    @Test
    fun `mtproto proxy without secret omits secret param`() {
        val proxy = proxy(protocol = ProxyProtocol.MTPROTO, ip = "1.2.3.4", port = 443, secret = null)

        val action = useCase(proxy) as TelegramConnectAction.DeepLink

        assertEquals("tg://proxy?server=1.2.3.4&port=443", action.uri)
    }

    @Test
    fun `socks5 proxy produces manual instruction with copy text`() {
        val proxy = proxy(protocol = ProxyProtocol.SOCKS5, ip = "5.6.7.8", port = 1080)

        val action = useCase(proxy)

        assertTrue(action is TelegramConnectAction.ManualInstruction)
        val instruction = action as TelegramConnectAction.ManualInstruction
        assertTrue(instruction.copyText.contains("5.6.7.8:1080"))
        assertTrue(instruction.steps.isNotEmpty())
    }

    @Test
    fun `socks5 proxy with credentials includes username in copy text`() {
        val proxy = proxy(protocol = ProxyProtocol.SOCKS5, ip = "5.6.7.8", port = 1080, username = "bob")

        val action = useCase(proxy) as TelegramConnectAction.ManualInstruction

        assertTrue(action.copyText.contains("bob"))
    }

    @Test
    fun `http proxy produces manual instruction`() {
        val proxy = proxy(protocol = ProxyProtocol.HTTP, ip = "9.9.9.9", port = 3128)

        val action = useCase(proxy) as TelegramConnectAction.ManualInstruction

        assertEquals("HTTP  9.9.9.9:3128", action.copyText)
    }

    private fun proxy(
        protocol: ProxyProtocol,
        ip: String,
        port: Int,
        secret: String? = null,
        username: String? = null,
    ) = Proxy(
        ip = ip,
        port = port,
        protocol = protocol,
        mtprotoSecret = secret,
        username = username,
        sourceUrl = "test",
        addedAt = System.currentTimeMillis(),
    )
}
