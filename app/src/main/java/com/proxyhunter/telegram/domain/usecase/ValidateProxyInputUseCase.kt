package com.proxyhunter.telegram.domain.usecase

import com.proxyhunter.telegram.domain.model.ProxyProtocol
import javax.inject.Inject

data class ProxyFormValidation(
    val ipError: String? = null,
    val portError: String? = null,
    val secretError: String? = null,
) {
    val isValid: Boolean get() = ipError == null && portError == null && secretError == null
}

// Валидация полей формы ручного добавления прокси (AddProxyScreen). Вынесена в domain,
// т.к. это бизнес-правило (что считается валидным IP/портом/MTProto-секретом), а не
// UI-деталь — так её можно покрыть unit-тестами без зависимости от Compose.
class ValidateProxyInputUseCase @Inject constructor() {

    operator fun invoke(
        ip: String,
        portText: String,
        protocol: ProxyProtocol,
        secretText: String,
    ): ProxyFormValidation = ProxyFormValidation(
        ipError = validateIp(ip),
        portError = validatePort(portText),
        secretError = if (protocol == ProxyProtocol.MTPROTO) validateSecret(secretText) else null,
    )

    private fun validateIp(ip: String): String? {
        if (ip.isBlank()) return "Введите IP-адрес"
        val octets = ip.trim().split(".")
        val isValidIpv4 = octets.size == 4 && octets.all { octet ->
            val value = octet.toIntOrNull()
            // "01" и подобные с ведущим нулём — тоже отклоняем, это не канонический вид IPv4.
            value != null && value in 0..255 && (octet == "0" || !octet.startsWith("0"))
        }
        return if (isValidIpv4) null else "Некорректный IPv4-адрес"
    }

    private fun validatePort(portText: String): String? {
        val port = portText.toIntOrNull() ?: return "Порт должен быть числом"
        return if (port in 1..65535) null else "Порт должен быть в диапазоне 1–65535"
    }

    // Формат зеркалит то, что реально принимает MtProtoObfuscatedHandshakeBuilder.parseSecret:
    // пусто (без секрета), 32 hex-символа (классический), или 34 символа с префиксом
    // "dd"/"ee" (padded / fake-TLS — последний прокси всё равно не сможет проверить через
    // handshake, но формат секрета сам по себе валиден, поэтому не блокируем его на вводе).
    private fun validateSecret(secretText: String): String? {
        if (secretText.isBlank()) return null
        val normalized = secretText.trim().lowercase()
        if (!normalized.all { it in "0123456789abcdef" }) return "Секрет должен быть в hex-формате"

        val validLength = normalized.length == 32 ||
            (normalized.length == 34 && (normalized.startsWith("dd") || normalized.startsWith("ee")))
        return if (validLength) null else "Секрет: 32 hex-символа, либо 34 с префиксом dd/ee"
    }
}
