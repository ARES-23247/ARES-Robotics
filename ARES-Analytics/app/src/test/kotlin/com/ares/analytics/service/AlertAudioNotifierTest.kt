package com.ares.analytics.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sound.sampled.LineUnavailableException
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AlertAudioNotifierTest {
    @Test fun `first alert is eligible at a zero monotonic origin`() = runTest {
        var plays = 0
        val notifier = AlertAudioNotifier({ 0L }, StandardTestDispatcher(testScheduler)) { plays++ }
        notifier.trigger(backgroundScope); runCurrent(); assertEquals(1, plays)
    }
    @Test fun `first alert is eligible at a negative monotonic origin`() = runTest {
        var plays = 0
        val notifier = AlertAudioNotifier({ -9_000_000_000L }, StandardTestDispatcher(testScheduler)) { plays++ }
        notifier.trigger(backgroundScope); runCurrent(); assertEquals(1, plays)
    }
    @Test fun `exact interval is eligible and one nanosecond earlier is not`() = runTest {
        var now = 5_000_000_000L; var plays = 0
        val notifier = AlertAudioNotifier({ now }, StandardTestDispatcher(testScheduler)) { plays++ }
        notifier.trigger(backgroundScope); runCurrent()
        now += 1_499_999_999L; notifier.trigger(backgroundScope); runCurrent(); assertEquals(1, plays)
        now++; notifier.trigger(backgroundScope); runCurrent(); assertEquals(2, plays)
    }
    @Test fun `queued bursts reserve only one playback job`() = runTest {
        var now = 5_000_000_000L; var plays = 0
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val notifier = AlertAudioNotifier({ now }, StandardTestDispatcher(testScheduler)) { plays++; awaitCancellation() }
        try {
            repeat(100) { notifier.trigger(owner); now += 2_000_000_000L }
            assertEquals(1, owner.coroutineContext.job.children.count())
            runCurrent(); assertEquals(1, plays)
        } finally { owner.cancel(); runCurrent() }
    }
    @Test fun `busy triggers do not move the cooldown timestamp`() = runTest {
        var now = 5_000_000_000L; var plays = 0
        val gate = CompletableDeferred<Unit>()
        val notifier = AlertAudioNotifier({ now }, StandardTestDispatcher(testScheduler)) { plays++; if (plays == 1) gate.await() }
        notifier.trigger(backgroundScope); runCurrent()
        now = 8_000_000_000L; notifier.trigger(backgroundScope); runCurrent()
        gate.complete(Unit); runCurrent(); now += 100_000_000L
        notifier.trigger(backgroundScope); runCurrent(); assertEquals(2, plays)
    }
    @Test fun `cooldown starts when playback runs rather than when dispatch is requested`() = runTest {
        var now = 5_000_000_000L; var plays = 0
        val notifier = AlertAudioNotifier({ now }, StandardTestDispatcher(testScheduler)) { plays++ }
        notifier.trigger(backgroundScope); now = 10_000_000_000L; runCurrent()
        now += 500_000_000L; notifier.trigger(backgroundScope); runCurrent(); assertEquals(1, plays)
    }
    @Test fun `cancelled owner does not consume another owners cooldown`() = runTest {
        var plays = 0
        val cancelled = CoroutineScope(Job().apply { cancel() } + StandardTestDispatcher(testScheduler))
        val notifier = AlertAudioNotifier({ 5_000_000_000L }, StandardTestDispatcher(testScheduler)) { plays++ }
        notifier.trigger(cancelled); runCurrent(); notifier.trigger(backgroundScope); runCurrent()
        assertEquals(1, plays)
    }
    @Test fun `fatal playback failures reach the owner exception handler`() = runTest {
        val failures = mutableListOf<Throwable>()
        val failure = AssertionError("broken adapter")
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> failures += e })
        val notifier = AlertAudioNotifier({ 5_000_000_000L }, StandardTestDispatcher(testScheduler)) { throw failure }
        try { notifier.trigger(owner); runCurrent(); assertEquals(listOf<Throwable>(failure), failures) }
        finally { owner.cancel(); runCurrent() }
    }
    @Test fun `nanosecond wraparound preserves the cooldown interval`() = runTest {
        var now = Long.MAX_VALUE - 1_000_000_000L; var plays = 0
        val notifier = AlertAudioNotifier({ now }, StandardTestDispatcher(testScheduler)) { plays++ }
        notifier.trigger(backgroundScope); runCurrent()
        now += 1_499_999_999L; notifier.trigger(backgroundScope); runCurrent(); assertEquals(1, plays)
        now++; notifier.trigger(backgroundScope); runCurrent(); assertEquals(2, plays)
    }
    @Test fun `regressing injected clock cannot prematurely end the cooldown`() = runTest {
        var now = 5_000_000_000L; var plays = 0
        val notifier = AlertAudioNotifier({ now }, StandardTestDispatcher(testScheduler)) { plays++ }
        notifier.trigger(backgroundScope); runCurrent()
        now = 4_000_000_000L; notifier.trigger(backgroundScope); runCurrent(); assertEquals(1, plays)
        now = 6_500_000_000L; notifier.trigger(backgroundScope); runCurrent(); assertEquals(2, plays)
    }
    @Test fun `cancellation before dispatch releases reservation without spending cooldown`() = runTest {
        var plays = 0
        val notifier = AlertAudioNotifier({ 0L }, StandardTestDispatcher(testScheduler)) { plays++ }
        notifier.trigger(backgroundScope); notifier.stop(); runCurrent()
        notifier.trigger(backgroundScope); runCurrent(); assertEquals(1, plays)
    }
    @Test fun `stop retains ownership until cleanup finishes and permits later reuse`() = runTest {
        var now = 0L; var plays = 0
        val cleanup = CompletableDeferred<Unit>()
        val notifier = AlertAudioNotifier({ now }, StandardTestDispatcher(testScheduler)) {
            plays++
            if (plays == 1) try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }
        try {
            notifier.trigger(backgroundScope); runCurrent(); notifier.stop(); runCurrent()
            now = 2_000_000_000L; notifier.trigger(backgroundScope); runCurrent(); assertEquals(1, plays)
            cleanup.complete(Unit); runCurrent(); notifier.trigger(backgroundScope); runCurrent(); assertEquals(2, plays)
        } finally { cleanup.complete(Unit); notifier.close(); runCurrent() }
    }
    @Test fun `close prevents queued and future playback`() = runTest {
        var plays = 0
        val notifier = AlertAudioNotifier({ 0L }, StandardTestDispatcher(testScheduler)) { plays++ }
        notifier.trigger(backgroundScope); notifier.close(); notifier.close(); runCurrent()
        notifier.trigger(backgroundScope); runCurrent(); assertEquals(0, plays)
    }
    @Test fun `expected audio failures preserve rate limiting and allow a later retry`() = runTest {
        for (failure in listOf(LineUnavailableException(), IllegalArgumentException(), SecurityException())) {
            var now = 0L; var plays = 0
            val notifier = AlertAudioNotifier({ now }, StandardTestDispatcher(testScheduler)) { plays++; if (plays == 1) throw failure }
            notifier.trigger(backgroundScope); runCurrent()
            now = 1_499_999_999L; notifier.trigger(backgroundScope); runCurrent(); assertEquals(1, plays)
            now++; notifier.trigger(backgroundScope); runCurrent(); assertEquals(2, plays)
        }
    }
    @Test fun `simultaneous callers admit exactly one child job`() = runTest {
        val pool = Executors.newFixedThreadPool(8); val barrier = CyclicBarrier(8)
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var plays = 0
        val notifier = AlertAudioNotifier({ 0L }, StandardTestDispatcher(testScheduler)) { plays++; awaitCancellation() }
        try {
            val attempts = (0 until 8).map { pool.submit { barrier.await(5, TimeUnit.SECONDS); notifier.trigger(owner) } }
            attempts.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, owner.coroutineContext.job.children.count()); runCurrent(); assertEquals(1, plays)
        } finally { notifier.close(); owner.cancel(); runCurrent(); pool.shutdownNow() }
    }
}
