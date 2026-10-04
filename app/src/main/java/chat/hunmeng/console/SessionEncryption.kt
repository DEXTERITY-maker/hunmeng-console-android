package chat.hunmeng.console

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated envelope for account sessions. Bot API tokens never enter this store. */
internal class SessionEncryption(private val key: () -> SecretKey, private val maxPlaintext: Int) {
    constructor(key: () -> SecretKey) : this(key, MAX_PLAINTEXT)
    fun seal(plaintext: ByteArray): ByteArray {
        require(plaintext.size in 1..maxPlaintext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore supplies a fresh, unpredictable IV for each encryption.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plaintext)
        check(cipher.iv.size == IV_SIZE)
        return ByteBuffer.allocate(HEADER.size + IV_SIZE + encrypted.size)
            .put(HEADER).put(cipher.iv).put(encrypted).array()
    }

    fun open(envelope: ByteArray): ByteArray {
        if (envelope.size !in (HEADER.size + IV_SIZE + TAG_SIZE + 1)..(maxPlaintext + 32) ||
            !envelope.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            throw GeneralSecurityException("Invalid session envelope")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, envelope.copyOfRange(HEADER.size, HEADER.size + IV_SIZE)))
        cipher.updateAAD(AAD)
        return cipher.doFinal(envelope, HEADER.size + IV_SIZE, envelope.size - HEADER.size - IV_SIZE)
    }

    companion object {
        private val HEADER = byteArrayOf(0x48, 0x43, 0x53, 0x01)
        private val AAD = "chat.hunmeng.console:account-session:v1".toByteArray(Charsets.UTF_8)
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 16
        const val MAX_PLAINTEXT = 16_384
        const val MAX_ENVELOPE = MAX_PLAINTEXT + 32
    }
}
