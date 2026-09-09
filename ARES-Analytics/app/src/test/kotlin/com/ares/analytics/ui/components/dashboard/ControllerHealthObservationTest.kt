package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.MonotonicClock
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerHealthObservationTest {
    private fun frame(value: Double = 20.0, key: String = "Robot/LoopTimeMs") = TelemetryFrame(0,"live",key,value)

    @Test fun `each health topic expires independently of unrelated telemetry`() {
        val tracker = ControllerHealthTracker()
        tracker.accept(frame(), 0, 1)
        assertEquals(20.0, tracker.snapshot(2_000_000_000,1).snapshot.loopTimeMs)
        tracker.accept(frame(42.0,"Unrelated"),2_000_000_001,1)
        val result = tracker.snapshot(2_000_000_001,1)
        assertNull(result.snapshot.loopTimeMs); assertEquals(0,result.lastUpdateAgeMs)
    }
    @Test fun `fresh lower priority alias replaces expired canonical reading`() {
        val tracker = ControllerHealthTracker()
        tracker.accept(frame(20.0),0,1)
        tracker.accept(frame(30.0,"Profiling/LoopTime_ms"),1_000_000_000,1)
        assertEquals(20.0,tracker.snapshot(1_000_000_000,1).snapshot.loopTimeMs)
        assertEquals(30.0,tracker.snapshot(2_000_000_001,1).snapshot.loopTimeMs)
    }
    @Test fun `new target clears cached health even without incoming frames`() {
        val tracker = ControllerHealthTracker(); tracker.accept(frame(),0,1)
        val result = tracker.snapshot(1,2)
        assertNull(result.snapshot.loopTimeMs); assertEquals(-1,result.lastUpdateAgeMs)
    }
    @Test fun `new target first frame cannot preserve other target fields`() {
        val tracker = ControllerHealthTracker(); tracker.accept(frame(),0,1)
        tracker.accept(frame(12.0,"Robot/BatteryVoltage"),1,2)
        val result = tracker.snapshot(1,2).snapshot
        assertNull(result.loopTimeMs); assertEquals(12.0,result.batteryVoltage)
    }
    @Test fun `clock wrap and backward clock keep freshness arithmetic bounded`() {
        val tracker = ControllerHealthTracker(); val start = Long.MAX_VALUE - 500_000_000
        tracker.accept(frame(),start,1)
        val wrapped = tracker.snapshot(start + 1_000_000_000,1)
        assertEquals(1_000,wrapped.lastUpdateAgeMs); assertEquals(20.0,wrapped.snapshot.loopTimeMs)
        val backward = tracker.snapshot(start - 1,1)
        assertNull(backward.snapshot.loopTimeMs); assertEquals(-1,backward.lastUpdateAgeMs)
    }
    @Test fun `replay exclusively resolves selected frame including reused sequence numbers`() {
        val live = ControllerHealthObservation(ControllerHealthSnapshot(loopTimeMs=1.0),0,ControllerHealthSource.LIVE)
        for (value in listOf(20.0,30.0)) {
            val result = resolveControllerHealth(live,ReplayFrame(0,mapOf("Robot/LoopTimeMs" to value),sequence=1),true,true)
            assertEquals(value,result.snapshot.loopTimeMs); assertEquals(ControllerHealthSource.REPLAY,result.source)
        }
    }
    @Test fun `replay loading and offline cannot fall back to live values`() {
        val live = ControllerHealthObservation(ControllerHealthSnapshot(loopTimeMs=20.0),0,ControllerHealthSource.LIVE)
        val loading = resolveControllerHealth(live,null,true,true)
        assertNull(loading.snapshot.loopTimeMs); assertEquals(ControllerHealthSource.REPLAY,loading.source)
        assertEquals(-1,loading.lastUpdateAgeMs)
        val offline = resolveControllerHealth(live,null,false,false)
        assertNull(offline.snapshot.loopTimeMs); assertEquals(ControllerHealthSource.OFFLINE,offline.source)
        assertSame(live,resolveControllerHealth(live,null,false,true))
    }
    @Test fun `current frame itself selects replay before mode signal changes`() {
        val result = resolveControllerHealth(ControllerHealthObservation(),ReplayFrame(0,emptyMap()),false,false)
        assertEquals(ControllerHealthSource.REPLAY,result.source); assertEquals(0,result.lastUpdateAgeMs)
    }
    @Test fun `flow skips retained history samples new values and releases collector on cancel`() = runTest {
        val frames = MutableSharedFlow<TelemetryFrame>(replay=1)
        frames.emit(frame(99.0))
        var latest = ControllerHealthObservation()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeLiveControllerHealth(frames,{1},{true},MonotonicClock { testScheduler.currentTime*1_000_000 })
                .collect { latest=it }
        }
        runCurrent(); assertNull(latest.snapshot.loopTimeMs)
        assertEquals(1,frames.subscriptionCount.value)
        frames.emit(frame(20.0)); advanceTimeBy(100); runCurrent()
        assertEquals(20.0,latest.snapshot.loopTimeMs)
        job.cancelAndJoin(); assertEquals(0,frames.subscriptionCount.value)
    }
    @Test fun `stale queued frame rejection and epoch polling remove old target values`() = runTest {
        val frames = MutableSharedFlow<TelemetryFrame>(extraBufferCapacity=5)
        var epoch = 1L
        var current: TelemetryFrame? = null
        var latest = ControllerHealthObservation()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeLiveControllerHealth(frames,{epoch},{it === current},MonotonicClock { testScheduler.currentTime*1_000_000 })
                .collect { latest=it }
        }
        val first = frame(); current=first; frames.emit(first); advanceTimeBy(100); runCurrent()
        assertEquals(20.0,latest.snapshot.loopTimeMs)
        epoch=2; current=null; frames.emit(first); advanceTimeBy(100); runCurrent()
        assertNull(latest.snapshot.loopTimeMs); assertEquals(-1,latest.lastUpdateAgeMs)
        job.cancelAndJoin()
    }
    @Test fun `presentation publications stay bounded under unrelated frame bursts`() = runTest {
        val frames = MutableSharedFlow<TelemetryFrame>(extraBufferCapacity=10_000)
        var publications=0
        val job=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeLiveControllerHealth(frames,{1},{true},MonotonicClock { testScheduler.currentTime*1_000_000 })
                .collect { publications++ }
        }
        repeat(10_000) { frames.emit(frame(it.toDouble(),"Topic/$it")) }
        runCurrent(); assertEquals(1,publications)
        advanceTimeBy(100); runCurrent(); assertEquals(2,publications)
        job.cancelAndJoin()
    }
    @Test fun `target change during frame validation cannot reassign old frame to new epoch`() = runTest {
        val frames=MutableSharedFlow<TelemetryFrame>(extraBufferCapacity=1)
        var epoch=1L
        var latest=ControllerHealthObservation()
        val job=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeLiveControllerHealth(frames,{epoch},{ epoch=2; true },
                MonotonicClock { testScheduler.currentTime*1_000_000 }).collect { latest=it }
        }
        frames.emit(frame(99.0)); advanceTimeBy(100); runCurrent()
        assertNull(latest.snapshot.loopTimeMs)
        assertEquals(-1,latest.lastUpdateAgeMs)
        job.cancelAndJoin()
    }
}
