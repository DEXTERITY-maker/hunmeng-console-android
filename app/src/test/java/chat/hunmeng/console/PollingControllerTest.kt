package chat.hunmeng.console

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PollingControllerTest {
    @Test fun secondStartDoesNotCreateAnotherCycle() = runTest {
        val controller = PollingController(this)
        var calls = 0
        val source: suspend (Long?) -> List<TelegramUpdate> = { calls++; awaitCancellation() }
        assertTrue(controller.start(null, source, {}, {}))
        runCurrent()
        assertFalse(controller.start(null, source, {}, {}))
        controller.stop()
        assertEquals(1, calls)
        assertFalse(controller.isRunning())
    }

    @Test fun stopCancelsAndWaitsForTheLongPoll() = runTest {
        val controller = PollingController(this)
        var sourceFinished = false
        val source: suspend (Long?) -> List<TelegramUpdate> = {
            try { awaitCancellation() } finally { sourceFinished = true }
        }
        controller.start(null, source, {}, {})
        runCurrent()
        controller.stop()
        assertTrue(sourceFinished)
        assertFalse(controller.isRunning())
    }

    @Test fun retryAfterLongerThanBackoffCapIsFullyRespected() = runTest {
        val controller = PollingController(this)
        var calls = 0
        val source: suspend (Long?) -> List<TelegramUpdate> = {
            calls++
            if (calls == 1) throw TelegramApiException(429, "rate limited", retryAfterSeconds = 90)
            awaitCancellation()
        }
        controller.start(null, source, {}, {})
        runCurrent()
        advanceTimeBy(89_999)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, calls)
        controller.stop()
    }

    @Test fun duplicateAndUnsortedUpdatesAreDispatchedOnlyOnceWithCorrectOffsets() = runTest {
        val controller = PollingController(this)
        val offsets = mutableListOf<Long?>()
        val handled = mutableListOf<Long>()
        val source: suspend (Long?) -> List<TelegramUpdate> = { offset ->
            offsets += offset
            when (offsets.size) {
                1 -> listOf(3, 1, 1, 2).map { TelegramUpdate(it.toLong()) }
                2 -> listOf(2, 3, 4).map { TelegramUpdate(it.toLong()) }
                else -> awaitCancellation()
            }
        }
        controller.start(null, source, { handled += it.map(TelegramUpdate::updateId) }, {})
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L, 4L), handled)
        assertEquals(listOf(null, 4L, 5L), offsets)
        assertEquals(5L, controller.currentOffset())
        controller.stop()
    }

    @Test fun restartKeepsOffsetEvenWithAnOlderInitialOffset() = runTest {
        val controller = PollingController(this)
        var firstCalls = 0
        controller.start(10, { firstCalls++; if (firstCalls == 1) listOf(TelegramUpdate(10)) else awaitCancellation() }, {}, {})
        runCurrent()
        controller.stop()
        var restartedAt: Long? = null
        controller.start(5, { restartedAt = it; awaitCancellation() }, {}, {})
        runCurrent()
        assertEquals(11L, restartedAt)
        controller.stop()
    }

    @Test fun cancelDuringHandlingCannotRepeatAnUnknownReplyOnRestart() = runTest {
        val controller = PollingController(this)
        var attempts = 0
        controller.start(null, { listOf(TelegramUpdate(20)) }, { attempts++; awaitCancellation() }, {})
        runCurrent()
        controller.stop()
        var restartOffset: Long? = null
        controller.start(null, { restartOffset = it; awaitCancellation() }, {}, {})
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(21L, restartOffset)
        controller.stop()
    }

    @Test fun authenticationAndConflictTerminateBeforeReportingTheirStatus() = runTest {
        for ((code, expected) in listOf(401 to "authentication_failed", 403 to "authentication_failed", 409 to "conflict")) {
            val controller = PollingController(this)
            var calls = 0
            val statuses = mutableListOf<Pair<String, Boolean>>()
            controller.start(null, { calls++; throw TelegramApiException(code, "rejected") }, {}, { statuses += it to controller.isRunning() })
            runCurrent()
            assertEquals(1, calls)
            assertEquals(expected to false, statuses.last())
            assertFalse(controller.isRunning())
            controller.stop()
        }
    }

    @Test fun networkFailureUsesBoundedExponentialBackoff() = runTest {
        val controller = PollingController(this)
        val callsAt = mutableListOf<Long>()
        controller.start(null, {
            callsAt += testScheduler.currentTime
            if (callsAt.size <= 7) throw TelegramNetworkException(IOException("Offline"))
            awaitCancellation()
        }, {}, {})
        runCurrent()
        advanceTimeBy(123_000)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L, 63_000L, 123_000L), callsAt)
        controller.stop()
    }

    @Test fun cancellationFromSourceIsPreservedAndNotRetried() = runTest {
        val controller = PollingController(this)
        var calls = 0
        val statuses = mutableListOf<String>()
        controller.start(null, { calls++; throw CancellationException("Canceled by caller") }, {}, { statuses += it })
        runCurrent()
        assertEquals(1, calls)
        assertEquals("stopped", statuses.last())
        assertFalse(controller.isRunning())
        controller.stop()
    }
}
