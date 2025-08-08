package com.iimoxi.odi_messanger

import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Constants moved here for common access
private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
private const val AES_ALGORITHM = "AES"
private const val AES_TRANSFORMATION = "AES/CBC/PKCS5Padding"
private const val SALT_SIZE_BYTES = 16
private const val IV_SIZE_BYTES = 16
private const val KEY_SIZE_BITS = 256
private const val ITERATION_COUNT = 65536

object AppConfig {
    const val ENCRYPTION_ENABLED = false // Set to true to enable, false to disable
}

fun encryptBytes(plainBytes: ByteArray, passkey: String): ByteArray? {
    if (!AppConfig.ENCRYPTION_ENABLED || plainBytes == null) {
        return plainBytes // Return plain bytes if encryption is disabled or input is null
    }
    try {
        val salt = ByteArray(SALT_SIZE_BYTES)
        SecureRandom().nextBytes(salt)

        val iv = ByteArray(IV_SIZE_BYTES)
        SecureRandom().nextBytes(iv)
        val ivParameterSpec = IvParameterSpec(iv)

        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
        val spec = PBEKeySpec(passkey.toCharArray(), salt, ITERATION_COUNT, KEY_SIZE_BITS)
        val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, AES_ALGORITHM)

        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivParameterSpec)
        val encryptedContentBytes = cipher.doFinal(plainBytes)

        // Combine: salt + iv + ciphertext
        return salt + iv + encryptedContentBytes
    } catch (e: Exception) {
        Log.e("ENCRYPT_BYTES_ERROR", "Error encrypting bytes", e)
        return null
    }
}

fun decryptBytes(encryptedPayloadWithSaltAndIv: ByteArray, passkey: String): ByteArray? {
    if (!AppConfig.ENCRYPTION_ENABLED || encryptedPayloadWithSaltAndIv == null) {
        return encryptedPayloadWithSaltAndIv // Return plain bytes if encryption is disabled or input is null
    }
    try {
        if (encryptedPayloadWithSaltAndIv.size < SALT_SIZE_BYTES + IV_SIZE_BYTES) {
            Log.e("DECRYPT_BYTES_ERROR", "Data too short to contain salt and IV.")
            return null
        }
        val salt = encryptedPayloadWithSaltAndIv.copyOfRange(0, SALT_SIZE_BYTES)
        val iv = encryptedPayloadWithSaltAndIv.copyOfRange(SALT_SIZE_BYTES, SALT_SIZE_BYTES + IV_SIZE_BYTES)
        val ivParameterSpec = IvParameterSpec(iv)
        val encryptedContentBytes = encryptedPayloadWithSaltAndIv.copyOfRange(SALT_SIZE_BYTES + IV_SIZE_BYTES, encryptedPayloadWithSaltAndIv.size)

        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
        val spec = PBEKeySpec(passkey.toCharArray(), salt, ITERATION_COUNT, KEY_SIZE_BITS)
        val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, AES_ALGORITHM)

        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)
        return cipher.doFinal(encryptedContentBytes)
    } catch (e: Exception) {
        Log.e("DECRYPT_BYTES_ERROR", "Error decrypting bytes", e)
        return null
    }
}

fun encryptString(plainText: String, passkey: String): String? {
    if (!AppConfig.ENCRYPTION_ENABLED || plainText == null) {
        return plainText // Return plain text if encryption is disabled or input is null
    }
    val plainBytes = plainText.toByteArray(Charsets.UTF_8)
    val encryptedBytes = encryptBytes(plainBytes, passkey)
    return encryptedBytes?.let { android.util.Base64.encodeToString(it, android.util.Base64.DEFAULT) }
}

fun decryptString(encryptedPayloadBase64: String, passkey: String): String? {
    if (!AppConfig.ENCRYPTION_ENABLED || encryptedPayloadBase64 == null) {
        return encryptedPayloadBase64 // Return plain text if encryption is disabled or input is null
    }
     val combinedInput = android.util.Base64.decode(encryptedPayloadBase64, android.util.Base64.DEFAULT)
     val decryptedBytes = decryptBytes(combinedInput, passkey)
     return decryptedBytes?.let { String(it, Charsets.UTF_8) }
}
