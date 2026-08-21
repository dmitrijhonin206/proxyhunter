package com.proxyhunter.telegram.domain.usecase

import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.model.ProxyProtocol
import java.net.URLEncoder
import javax.inject.Inject

sealed class TelegramConnectAction {
    data class DeepLink(val uri: String) : TelegramConnectAction()          // одним тапом открывает Telegram
    data class ManualInstruction(val steps: List<String>, val copyText: String) : TelegramConnectAction()
}

// Генерирует способ подключения прокси в Telegram согласно ТЗ:
// - MTProto → tg://proxy?server=...&port=...&secret=... (открывается прямо в клиенте)
// - SOCKS5/HTTP → официальный клиент Telegram не имеет deep-link для них,
//   поэтому предоставляем текст для копирования + пошаговую инструкцию по ручной настройке.
class GenerateTelegramConnectActionUseCase @Inject constructor() {

    operator fun invoke(proxy: Proxy): TelegramConnectAction = when (proxy.protocol) {
        ProxyProtocol.MTPROTO -> TelegramConnectAction.DeepLink(buildMtprotoLink(proxy))
        ProxyProtocol.SOCKS5 -> TelegramConnectAction.ManualInstruction(
            steps = socks5Steps(),
            copyText = "SOCKS5  ${proxy.ip}:${proxy.port}" +
                if (!proxy.username.isNullOrBlank()) "  (логин: ${proxy.username})" else "",
        )
        ProxyProtocol.HTTP, ProxyProtocol.HTTPS -> TelegramConnectAction.ManualInstruction(
            steps = httpSteps(),
            copyText = "${proxy.protocol.name}  ${proxy.ip}:${proxy.port}",
        )
    }

    private fun buildMtprotoLink(proxy: Proxy): String {
        val server = URLEncoder.encode(proxy.ip, "UTF-8")
        val secret = proxy.mtprotoSecret?.let { URLEncoder.encode(it, "UTF-8") }
        return buildString {
            append("tg://proxy?server=$server&port=${proxy.port}")
            if (!secret.isNullOrBlank()) append("&secret=$secret")
        }
    }

    private fun socks5Steps() = listOf(
        "Откройте Telegram → Настройки → Данные и память → Прокси",
        "Нажмите «Добавить прокси» → выберите SOCKS5",
        "Вставьте скопированные IP и порт, при необходимии — логин/пароль",
        "Сохраните и включите прокси переключателем",
    )

    private fun httpSteps() = listOf(
        "Откройте Telegram → Настройки → Данные и память → Прокси",
        "Нажмите «Добавить прокси» → выберите HTTP",
        "Вставьте скопированные IP и порт",
        "Сохраните и включите прокси переключателем",
    )
}
