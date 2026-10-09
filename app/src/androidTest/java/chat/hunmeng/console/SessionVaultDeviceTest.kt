package chat.hunmeng.console

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class SessionVaultDeviceTest {
    @Test fun deviceKeystoreEncryptsRestoresAndErasesOnlyTestSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val purpose = VaultPurpose.INSTRUMENTATION_TEST
        val vault = EncryptedSessionVault(context, purpose)
        val fixture = "TEST_SYNTHETIC_ACCOUNT_SESSION".toByteArray()
        // Dedicated test alias and filename; no production credential file is opened.
        vault.erase()
        try {
            vault.save(fixture)
            val file = File(context.noBackupFilesDir, purpose.fileName)
            assertTrue(file.exists())
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(String(fixture)))
            assertArrayEquals(fixture, EncryptedSessionVault(context, purpose).load())
            vault.erase()
            assertNull(vault.load())
            assertFalse(file.exists())
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(purpose.keyAlias))
        } finally { vault.erase(); fixture.fill(0) }
    }
}
