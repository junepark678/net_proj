package dev.junepark.oniontalkie.onion

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "onion-dial-service-key-v1"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val PREFIX = "android-aes-gcm:v1:"

actual fun createOnionServiceKeyProtector(): OnionServiceKeyProtector = AndroidKeystoreServiceKeyProtector

private object AndroidKeystoreServiceKeyProtector : OnionServiceKeyProtector {
    private val lock = Any()

    override fun protect(privateKey: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(privateKey.encodeToByteArray())
        return PREFIX + encode(cipher.iv) + ":" + encode(encrypted)
    }

    override fun unprotect(stored: String): String {
        require(stored.startsWith(PREFIX)) { "Stored Android service key is not Keystore protected" }
        val parts = stored.removePrefix(PREFIX).split(':', limit = 2)
        require(parts.size == 2) { "Stored Android service-key envelope is malformed" }
        val iv = decode(parts[0])
        val encrypted = decode(parts[1])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).decodeToString()
    }

    private fun secretKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }
            .generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
