package chat.hunmeng.console

import org.junit.Assert.*
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator

class SessionEncryptionTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun authenticatedRoundTripAndFreshIv() {
        val key = key()
        val crypto = SessionEncryption { key }
        val clear = "TEST_ACCOUNT_SESSION_DO_NOT_USE".toByteArray()
        val first = crypto.seal(clear)
        val second = crypto.seal(clear)
        assertArrayEquals(clear, crypto.open(first))
        assertFalse(first.contentEquals(second))
        assertFalse(String(first, Charsets.ISO_8859_1).contains(String(clear)))
    }
    @Test fun rejectsChangesToHeaderIvAndCiphertext() {
        val key = key()
        val crypto = SessionEncryption { key }
        val envelope = crypto.seal(byteArrayOf(1, 2, 3))
        for (index in listOf(0, 4, envelope.lastIndex)) {
            val changed = envelope.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
            assertThrows(GeneralSecurityException::class.java) { crypto.open(changed) }
        }
    }
    @Test fun rejectsForeignKeyTruncationAndOversizedPayload() {
        val first = key(); val second = key()
        val encrypted = SessionEncryption { first }.seal(byteArrayOf(1))
        assertThrows(GeneralSecurityException::class.java) { SessionEncryption { second }.open(encrypted) }
        assertThrows(GeneralSecurityException::class.java) { SessionEncryption { first }.open(encrypted.copyOf(8)) }
        assertThrows(IllegalArgumentException::class.java) { SessionEncryption { first }.seal(ByteArray(16_385)) }
    }
}
