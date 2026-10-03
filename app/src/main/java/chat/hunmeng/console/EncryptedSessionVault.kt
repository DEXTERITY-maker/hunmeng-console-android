package chat.hunmeng.console

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Private, backup-excluded storage. Encryption failures never fall back to plaintext. */
internal enum class VaultPurpose(val fileName: String, val keyAlias: String) {
    ACCOUNT("telegram-account.session", "hunmeng.telegram.account.v1"),
    LOGIN_PENDING("telegram-login.pending", "hunmeng.telegram.login.pending.v1"),
    INSTRUMENTATION_TEST("telegram-account.test.session", "hunmeng.telegram.account.test.v1"),
}
internal class EncryptedSessionVault(context: Context, purpose: VaultPurpose = VaultPurpose.ACCOUNT) {
    private val file = AtomicFile(File(context.noBackupFilesDir, purpose.fileName))
    private val keyAlias = purpose.keyAlias
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val encryption = SessionEncryption(::key)

    @Synchronized fun save(payload: ByteArray) {
        val encrypted = encryption.seal(payload)
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        } finally { encrypted.fill(0) }
    }

    /** A stored payload must still be verified against the auth server before granting access. */
    @Synchronized fun load(): ByteArray? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        // A lost key must not be silently recreated for an existing session.
        check(keyStore.containsAlias(keyAlias)) { "Session key unavailable" }
        val encrypted = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (output.size() <= SessionEncryption.MAX_ENVELOPE) {
                val count = input.read(buffer, 0, minOf(buffer.size, SessionEncryption.MAX_ENVELOPE + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            buffer.fill(0)
            output.toByteArray()
        }
        return try { encryption.open(encrypted) } finally { encrypted.fill(0) }
    }

    /** Cryptographic erasure also makes any retained filesystem blocks unusable. */
    @Synchronized fun erase() {
        file.delete()
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        check(listOf(file.baseFile, File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new")).none { it.exists() } && !keyStore.containsAlias(keyAlias)) { "Session cleanup failed" }
    }

    private fun key(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

}
