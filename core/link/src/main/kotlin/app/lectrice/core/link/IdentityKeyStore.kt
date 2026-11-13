package app.lectrice.core.link

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This install's X25519 private key (file 24 §3.3), stored encrypted with an AES-GCM key that lives in the Android
 * Keystore and can't be exported. The file is `IV ‖ ciphertext+tag`. If the Keystore key is gone (data restored onto
 * another phone, Keystore reset) the old file can't be read: a new identity is made and the computers must be paired
 * again, which is the safe outcome.
 */
internal class IdentityKeyStore(private val file: File) {
    @Synchronized
    fun privateKey(): ByteArray = read() ?: create()

    /** Forgetting every computer doesn't need a new key; this is for "the key can't be read". */
    private fun create(): ByteArray {
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(create = true))
        LinkFiles.writeAtomically(file, cipher.iv + cipher.doFinal(key))
        return key
    }

    private fun read(): ByteArray? {
        if (!file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            val key = wrappingKey(create = false) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).takeIf { it.size == KEY_BYTES }
        }.getOrNull()
    }

    private fun wrappingKey(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "lectrice_link_identity"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val AES_BITS = 256
    }
}
