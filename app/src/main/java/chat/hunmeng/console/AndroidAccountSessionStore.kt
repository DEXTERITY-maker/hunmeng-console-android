package chat.hunmeng.console

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidAccountSessionStore(context: Context, private val tools: AndroidAccountToolsStore = AndroidAccountToolsStore(context)) : AccountSessionStore {
    private val vault = EncryptedSessionVault(context)
    override suspend fun load(): AccountSessionRecord? = withContext(Dispatchers.IO) {
        val payload = vault.load() ?: return@withContext null
        try { AccountSessionRecord.decode(payload) } finally { payload.fill(0) }
    }
    override suspend fun save(session: AccountSessionRecord) = withContext(Dispatchers.IO) {
        val payload = session.encode()
        try { vault.save(payload) } finally { payload.fill(0) }
    }
    override suspend fun erase() = withContext(Dispatchers.IO) {
        var failure: Exception? = null
        try { vault.erase() } catch (error: Exception) { failure = error }
        try { tools.erase() } catch (error: Exception) { failure = error }
        failure?.let { throw it }
        Unit
    }
}
