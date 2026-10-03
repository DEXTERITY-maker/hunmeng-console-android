package chat.hunmeng.console

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

class PollingController(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var job: Job? = null
    @Volatile private var running = false
    @Volatile private var nextOffset: Long? = null

    suspend fun start(
        initialOffset: Long?,
        source: suspend (Long?) -> List<TelegramUpdate>,
        onUpdates: suspend (List<TelegramUpdate>) -> Unit,
        onStatus: (String) -> Unit,
    ): Boolean = mutex.withLock {
        if (job?.isCompleted == false || scope.coroutineContext[Job]?.isActive == false) return false
        nextOffset = listOfNotNull(nextOffset, initialOffset).maxOrNull()
        running = true
        job = scope.launch(start = CoroutineStart.LAZY) {
            var backoff = 1_000L
            var terminalStatus = "stopped"
            try {
                onStatus("starting")
                while (isActive) {
                    try {
                        val updates = source(nextOffset)
                            .distinctBy { it.updateId }
                            .sortedBy { it.updateId }
                            .filter { nextOffset == null || it.updateId >= nextOffset!! }
                        for (update in updates) {
                            currentCoroutineContext().ensureActive()
                            // Accept once per session before dispatching a potential reply.
                            // Restarting must not resend a reply whose delivery became unknown.
                            nextOffset = update.updateId + 1
                            onUpdates(listOf(update))
                        }
                        backoff = 1_000L
                        onStatus("connected")
                        // Protect against a proxy returning empty long polls immediately.
                        if (updates.isEmpty()) delay(250)
                    } catch (error: TelegramApiException) {
                        when (error.errorCode) {
                            401, 403 -> { terminalStatus = "authentication_failed"; break }
                            409 -> { terminalStatus = "conflict"; break }
                            in 400..499 -> if (error.errorCode != 429) {
                                terminalStatus = "error"
                                break
                            }
                        }
                        onStatus("retrying")
                        val wait = error.retryAfterSeconds?.takeIf { it > 0 }
                            ?.coerceAtMost(Long.MAX_VALUE / 1_000)?.times(1_000) ?: backoff
                        delay(wait)
                        backoff = min(backoff * 2, 60_000L)
                    } catch (_: TelegramNetworkException) {
                        onStatus("offline")
                        delay(backoff)
                        backoff = min(backoff * 2, 60_000L)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                terminalStatus = "error"
            } finally {
                running = false
                onStatus(terminalStatus)
            }
        }
        job!!.start()
        true
    }

    suspend fun stop() = mutex.withLock {
        job?.cancel()
        job?.join()
        job = null
        running = false
    }

    fun isRunning(): Boolean = running

    fun currentOffset(): Long? = nextOffset
}
