package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.MonotonicClock
import com.ares.analytics.service.hardware.SubsystemHealthSnapshot
import com.ares.analytics.service.hardware.SubsystemHealthStatus
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SubsystemHealthObservationTest {
    private fun frame(signal: String, value: Double = 1.0) =
        TelemetryFrame(0L, "live", "Subsystems/arm/$signal", value)

    private fun readyFrames() = listOf(
        frame("TelemetryHeartbeat"), frame("ConfigurationHealthy"), frame("FeedbackValid"),
        frame("Homed"), frame("Calibrated"), frame("CurrentReadingValid"),
        frame("HomingFaultLatched", 0.0), frame("OutputFaultLatched", 0.0),
    )

    @Test
    fun `reopening a card requires a fresh heartbeat while preserving retained state flags`() = runTest {
        val frames = MutableSharedFlow<TelemetryFrame>(replay = 20)
        val retained = readyFrames()
        retained.forEach { frames.emit(it) }
        var latest = emptyList<SubsystemHealthSnapshot>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeSubsystemHealth(frames, { 0L }, { true }, MonotonicClock { testScheduler.currentTime * 1_000_000L })
                .collect { latest = it }
        }
        runCurrent()
        assertEquals(SubsystemHealthStatus.INCOMPLETE, latest.single().status)
        frames.emit(retained.first()) // Re-emission of the same retained object cannot renew its age.
        advanceTimeBy(250L); runCurrent()
        assertEquals(SubsystemHealthStatus.INCOMPLETE, latest.single().status)
        frames.emit(frame("TelemetryHeartbeat", 2.0))
        advanceTimeBy(250L); runCurrent()
        assertEquals(SubsystemHealthStatus.HEALTHY, latest.single().status)
        advanceTimeBy(1_001L)
        frames.emit(frame("position", 0.42))
        advanceTimeBy(250L); runCurrent()
        assertEquals(SubsystemHealthStatus.STALE, latest.single().status)
        job.cancelAndJoin()
        assertEquals(0, frames.subscriptionCount.value)
    }

    @Test
    fun `target reset and queued old frames cannot carry health into a different robot`() = runTest {
        val frames = MutableSharedFlow<TelemetryFrame>(extraBufferCapacity = 20)
        var epoch = 0L
        var current: TelemetryFrame? = null
        var latest = emptyList<SubsystemHealthSnapshot>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeSubsystemHealth(frames, { epoch }, { it === current }, MonotonicClock { testScheduler.currentTime * 1_000_000L })
                .collect { latest = it }
        }
        for (frame in readyFrames()) { current = frame; frames.emit(frame) }
        advanceTimeBy(250L); runCurrent()
        assertEquals(SubsystemHealthStatus.HEALTHY, latest.single().status)
        val old = requireNotNull(current)
        epoch = 1L; current = null
        advanceTimeBy(250L); runCurrent()
        assertTrue(latest.isEmpty())
        frames.emit(old)
        advanceTimeBy(250L); runCurrent()
        assertTrue(latest.isEmpty())
        current = frame("TelemetryHeartbeat", 2.0); frames.emit(requireNotNull(current))
        advanceTimeBy(250L); runCurrent()
        assertEquals(SubsystemHealthStatus.INCOMPLETE, latest.single().status)
        job.cancelAndJoin()
        assertEquals(0, frames.subscriptionCount.value)
    }

    @Test
    fun `target switch during current-frame check rejects the racing old sample`() = runTest {
        val frames = MutableSharedFlow<TelemetryFrame>(extraBufferCapacity = 1)
        var epoch = 0L
        var latest = emptyList<SubsystemHealthSnapshot>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeSubsystemHealth(frames, { epoch }, { epoch++; true }, MonotonicClock { 0L })
                .collect { latest = it }
        }
        frames.emit(frame("FeedbackValid"))
        advanceTimeBy(250L); runCurrent()
        assertTrue(latest.isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun `same-topic bursts retain only latest values and publish at four hertz`() = runTest {
        val frames = MutableSharedFlow<TelemetryFrame>(extraBufferCapacity = 10_000)
        var publications = 0
        var latest = emptyList<SubsystemHealthSnapshot>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeSubsystemHealth(frames, { 0L }, { true }, MonotonicClock { testScheduler.currentTime * 1_000_000L })
                .collect { latest = it; publications++ }
        }
        repeat(5_000) { frames.emit(frame("position", it.toDouble())) }
        advanceTimeBy(500L); runCurrent()
        assertEquals(3, publications)
        assertEquals(mapOf("position" to 4_999.0), latest.single().measurements)
        job.cancelAndJoin()
        assertEquals(0, frames.subscriptionCount.value)
    }
}
