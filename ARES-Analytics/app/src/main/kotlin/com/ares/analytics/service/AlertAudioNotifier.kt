package com.ares.analytics.service

import kotlinx.coroutines.*
import javax.sound.sampled.LineUnavailableException

/** At most one queued/running attempt; cooldown is measured from worker start on a monotonic clock. */
internal class AlertAudioNotifier(
    private val clockNanos: () -> Long = System::nanoTime,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val play: suspend () -> Unit = JavaSoundAlertTone()::play,
) {
    private val lock = Any()
    private var active: Job? = null
    private var closed = false
    private var hasStarted = false
    private var lastStartNanos = 0L

    fun trigger(scope: CoroutineScope) {
        val job = synchronized(lock) {
            if (closed || active != null || !scope.isActive) return
            // Signed subtraction intentionally supports nanoTime's wraparound for ordinary intervals.
            if (hasStarted && clockNanos() - lastStartNanos < MINIMUM_INTERVAL_NS) return
            scope.launch(dispatcher, start = CoroutineStart.LAZY) {
                synchronized(lock) {
                    if (closed || !isActive) return@launch
                    lastStartNanos = clockNanos()
                    hasStarted = true
                }
                ensureActive()
                try { play() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: LineUnavailableException) { /* Audio is optional; a later alert may retry. */ }
                catch (_: IllegalArgumentException) { /* No compatible device/format. */ }
                catch (_: SecurityException) { /* Audio access denied. */ }
            }.also { reserved ->
                active = reserved
                reserved.invokeOnCompletion {
                    synchronized(lock) { if (active === reserved) active = null }
                }
            }
        }
        // Playback and its cleanup must never run under the admission lock.
        job.start()
    }

    /** Cancel the current attempt without disabling future alerts; ownership lasts through cleanup. */
    fun stop() { synchronized(lock) { active }?.cancel() }

    /** Terminal cancellation; the owning scope can join all accepted work before releasing resources. */
    fun close() {
        val job = synchronized(lock) { closed = true; active }
        job?.cancel()
    }

    private companion object { const val MINIMUM_INTERVAL_NS = 1_500_000_000L }
}
