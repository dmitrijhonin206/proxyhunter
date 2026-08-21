package com.proxyhunter.telegram.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private const val KEYSTORE_ALIAS = "proxyhunter_credentials_key"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val GCM_TAG_LENGTH_BITS = 128

// Шифрует логины/пароли/секреты прокси перед сохранением в Room через ключ,
// который никогда не покидает Android Keystore (аппаратно поддерживаемый на большинстве устройств).
// Согласно ТЗ: "не хранить логины/пароли от прокси в открытом виде".
@Singleton
class CryptoManager @Inject constructor() {

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): javax.crypto.SecretKey {
        keyStore.getKey(KEYSTORE_ALIAS, null)?.let { return it as javax.crypto.SecretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    // Возвращает base64(iv) + ":" + base64(ciphertext) — IV генерируется случайно на каждое шифрование
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(cipherBytes, Base64.NO_WRAP)}"
    }

    fun decrypt(encoded: String): String {
        val (ivB64, cipherB64) = encoded.split(":", limit = 2)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val cipherBytes = Base64.decode(cipherB64, Base64.NO_WRAP)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }
}
