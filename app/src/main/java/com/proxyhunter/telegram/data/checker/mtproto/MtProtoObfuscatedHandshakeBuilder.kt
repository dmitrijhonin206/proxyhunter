package com.proxyhunter.telegram.data.checker.mtproto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class MtProtoTransport(val tag: ByteArray) {
    ABRIDGED(byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())),
    INTERMEDIATE(byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())),
    PADDED_INTERMEDIATE(byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte())),
}

// Секрет в формате "ee..." (fake-TLS) заворачивает весь handshake в поддельный TLS
// ClientHello — это отдельный протокол поверх obfuscated2, который этот упрощённый
// чекер не реализует (вынесено как явная, а не тихая деградация).
class MtProtoSecretUnsupportedException(message: String) : Exception(message)

data class ObfuscatedHandshake(
    val packetToSend: ByteArray,   // 64 байта — отправляются первыми на сокет
    val encryptor: Cipher,         // AES-256-CTR keystream для дальнейших исходящих байт
    val decryptor: Cipher,         // AES-256-CTR keystream для входящих байт от сервера
)

// Строит handshake-пакет транспортной обфускации Telegram MTProto — публично
// задокументирован: core.telegram.org/mtproto/mtproto-transports#transport-obfuscation.
// Назначение — ТОЛЬКО подтвердить, что прокси действительно говорит на MTProto (принимает
// корректно построенный заголовок и не рвёт соединение сразу). Полноценный auth-key
// exchange (реальная авторизация в Telegram) сознательно не реализуется: это уже, по сути,
// написание клиента Telegram с нуля, а для проверки "жив ли прокси" — избыточно.
object MtProtoObfuscatedHandshakeBuilder {

    private val secureRandom = SecureRandom()

    private val FORBIDDEN_PREFIXES = listOf(
        byteArrayOf(0x48, 0x45, 0x41, 0x44), // "HEAD"
        byteArrayOf(0x50, 0x4f, 0x53, 0x54), // "POST"
        byteArrayOf(0x47, 0x45, 0x54, 0x20), // "GET "
        byteArrayOf(0x4f, 0x50, 0x54, 0x49), // "OPTI"
        byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte()),
        byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte()),
    )

    // secretHex — как хранится в domain-модели (Proxy.mtprotoSecret), hex-строка без "0x".
    // Бросает MtProtoSecretUnsupportedException для fake-TLS секретов — вызывающий код
    // должен явно откатиться на TCP-only проверку, а не тихо считать прокси нерабочим.
    fun build(transport: MtProtoTransport, secretHex: String?): ObfuscatedHandshake {
        val secretBytes = parseSecret(secretHex)

        val header = generateValidHeader()
        transport.tag.copyInto(header, destinationOffset = 56)

        val forwardKeyMaterial = header.copyOfRange(8, 40)   // 32 байта — ключ исходящего потока
        val forwardIv = header.copyOfRange(40, 56)           // 16 байт — IV исходящего потока

        val reversedMiddle = header.copyOfRange(8, 56).also { it.reverse() } // те же 48 байт, развёрнуты
        val backwardKeyMaterial = reversedMiddle.copyOfRange(0, 32)
        val backwardIv = reversedMiddle.copyOfRange(32, 48)

        val encryptKey = deriveKey(forwardKeyMaterial, secretBytes)
        val decryptKey = deriveKey(backwardKeyMaterial, secretBytes)

        val encryptor = aesCtrCipher(Cipher.ENCRYPT_MODE, encryptKey, forwardIv)
        val decryptor = aesCtrCipher(Cipher.DECRYPT_MODE, decryptKey, backwardIv)
        // Для AES/CTR шифрование и расшифровка — одна и та же XOR-операция с keystream'ом,
        // поэтому ENCRYPT_MODE/DECRYPT_MODE здесь дают идентичный побайтовый результат;
        // режимы расставлены по смыслу, а не потому что это меняет вывод.

        // Шифруем весь 64-байтовый заголовок его же keystream'ом (self-referential) и
        // заменяем байты [56:64) на их зашифрованную форму — это одновременно прячет тег
        // транспорта на проводе и "продвигает" keystream encryptor'а на 64 байта вперёд,
        // так что любые дальнейшие исходящие данные продолжают его без дополнительных вызовов.
        val encryptedHeader = encryptor.update(header)
        val packetToSend = header.copyOf(64)
        encryptedHeader.copyInto(packetToSend, destinationOffset = 56, startIndex = 56, endIndex = 64)

        return ObfuscatedHandshake(packetToSend, encryptor, decryptor)
    }

    private fun generateValidHeader(): ByteArray {
        while (true) {
            val header = ByteArray(64)
            secureRandom.nextBytes(header)

            if (header[0] == 0xef.toByte()) continue

            val first4 = header.copyOfRange(0, 4)
            if (FORBIDDEN_PREFIXES.any { it.contentEquals(first4) }) continue

            val second4 = header.copyOfRange(4, 8)
            if (second4.all { it == 0.toByte() }) continue

            return header
        }
    }

    private fun deriveKey(rawKeyMaterial: ByteArray, secret: ByteArray?): ByteArray {
        if (secret == null || secret.isEmpty()) return rawKeyMaterial
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(rawKeyMaterial)
        digest.update(secret)
        return digest.digest() // 32 байта — ровно ключ для AES-256
    }

    private fun aesCtrCipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher
    }

    // Секрет может быть: без префикса (классический, 16 байт → 32 hex-символа), с префиксом
    // "dd" (padded — криптографически идентичен классическому для наших целей, разница
    // только в паддинге трафика, который мы не отправляем), или с префиксом "ee" (fake-TLS,
    // не поддерживается — см. класс-докстринг MtProtoSecretUnsupportedException).
    private fun parseSecret(secretHex: String?): ByteArray? {
        if (secretHex.isNullOrBlank()) return null
        val normalized = secretHex.trim().lowercase()

        return when {
            normalized.startsWith("ee") && normalized.length == 34 ->
                throw MtProtoSecretUnsupportedException("fake-TLS (ee-) секреты не поддерживаются")
            normalized.startsWith("dd") && normalized.length == 34 ->
                hexToBytes(normalized.substring(2))
            normalized.length == 32 -> hexToBytes(normalized)
            else -> throw MtProtoSecretUnsupportedException("Нераспознанный формат MTProto-секрета")
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
}
