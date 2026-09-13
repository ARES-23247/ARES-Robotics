package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import com.ares.analytics.service.MonotonicClock
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.TelemetryStore
import com.ares.analytics.shared.models.TelemetryFrame
import com.areslib.telemetry.schema.TopologyNode
import com.areslib.telemetry.schema.TopologyNodeType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.mockito.Mockito.mock
import org.mockito.BDDMockito.given
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HardwareTopologyObservationAuditTest {
    private val motor = TopologyNode("Motors/arm", TopologyNodeType.MOTOR, "Friendly arm")
    private fun frame(key: String, value: Double) = TelemetryFrame(0, "live-telemetry", key, value)
    private val current = "Hardware/Motors/arm/CurrentAmps"
    private val velocity = "Hardware/Motors/arm/Velocity"

    @Test fun canonicalIdentityPrecedesDisplayNamesAndNeverDoublesTheMotorPrefix() {
        val tracker = TopologyTelemetryTracker(listOf(motor))
        tracker.accept(frame("Hardware/Motors/Friendly arm/CurrentAmps", 90.0), 0, 0)
        assertEquals(90.0, tracker.snapshot(0, 0).getValue(motor.id).currentAmps)
        tracker.accept(frame(current, 3.0), 0, 0)
        tracker.accept(frame(velocity, 4.0), 0, 0)
        tracker.accept(frame("Hardware/Motors/Motors/arm/CurrentAmps", 99.0), 0, 0)
        assertEquals(TopologyMotorReading(3.0, 4.0), tracker.snapshot(0, 0)[motor.id])
        val fullId = motor.copy(id = "Hardware/Motors/arm")
        val fullTracker = TopologyTelemetryTracker(listOf(fullId))
        fullTracker.accept(frame("/$current", 5.0), 0, 0)
        assertEquals(5.0, fullTracker.snapshot(0, 0)[fullId.id]?.currentAmps)
    }

    @Test fun onlyMotorNodesReadMotorMetricsEvenWhenNamesCollide() {
        val sensor = motor.copy(id = "Sensors/arm", type = TopologyNodeType.IMU)
        val tracker = TopologyTelemetryTracker(listOf(sensor))
        tracker.accept(frame("Hardware/Motors/Friendly arm/CurrentAmps", 10.0), 0, 0)
        assertTrue(tracker.snapshot(0, 0).isEmpty())
    }

    @Test fun invalidCanonicalSamplesAreUnknownWithoutRevivingAliasValues() {
        val tracker = TopologyTelemetryTracker(listOf(motor))
        tracker.accept(frame("Hardware/Motors/Friendly arm/CurrentAmps", 90.0), 0, 0)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            tracker.accept(frame(current, bad), 1, 0)
            assertTrue(tracker.snapshot(1, 0).isEmpty())
        }
        tracker.accept(frame(current, 0.0).copy(stringValue = "not numeric"), 1, 0)
        assertTrue(tracker.snapshot(1, 0).isEmpty())
        tracker.accept(frame(current, 0.0), 2, 0)
        assertEquals(0.0, tracker.snapshot(2, 0).getValue(motor.id).currentAmps)
    }

    @Test fun eachMetricExpiresIndependentlyWithoutUnrelatedTrafficRenewingIt() {
        val tracker = TopologyTelemetryTracker(listOf(motor))
        tracker.accept(frame(current, 3.0), 0, 0)
        tracker.accept(frame(velocity, 4.0), 1_000_000_000, 0)
        tracker.accept(frame("Robot/LoopTimeMs", 20.0), 2_000_000_000, 0)
        assertEquals(3.0, tracker.snapshot(2_000_000_000, 0).getValue(motor.id).currentAmps)
        assertEquals(TopologyMotorReading(null, 4.0), tracker.snapshot(2_000_000_001, 0)[motor.id])
        assertTrue(tracker.snapshot(3_000_000_001, 0).isEmpty())
    }

    @Test fun targetChangesAndClockRewindsCannotRetainOldReadings() {
        val tracker = TopologyTelemetryTracker(listOf(motor))
        tracker.accept(frame(current, 3.0), 100, 0)
        assertTrue(tracker.snapshot(99, 0).isEmpty())
        val owned = tracker.snapshot(100, 0)
        assertTrue(tracker.snapshot(100, 1).isEmpty())
        tracker.accept(frame(current, 9.0), 101, 1)
        assertEquals(3.0, owned[motor.id]?.currentAmps)
        assertEquals(9.0, tracker.snapshot(101, 1)[motor.id]?.currentAmps)
    }

    @Test fun nativeVelocityIsNotMislabelledAsRadiansAndFormattingIsStable() {
        assertEquals("2.50 A", topologyCurrentText(2.5))
        assertEquals("1000.0 encoder units/s", topologyVelocityText(1000.0))
        assertFalse(topologyVelocityText(1000.0).contains("rad/s"))
    }

    @Test fun flowHasOneCollectorBoundedPresentationAndRejectsRetainedOrOldTargetFrames() = runTest {
        val source = MutableSharedFlow<TelemetryFrame>(replay = 1)
        val store = TelemetryStore()
        val old = frame(current, 3.0)
        store.accept(old); source.emit(old)
        val results = mutableListOf<Map<String, TopologyMotorReading>>()
        val clock = MonotonicClock { testScheduler.currentTime * 1_000_000 }
        val collector = backgroundScope.launch {
            observeTopologyTelemetry(listOf(motor), source, store::currentTargetEpoch,
                store::isCurrentNotifiedFrame, clock).collect { results.add(it) }
        }
        runCurrent()
        assertEquals(1, source.subscriptionCount.value)
        assertTrue(results.last().isEmpty())
        val fresh = frame(current, 4.0)
        store.accept(fresh); source.emit(fresh)
        repeat(1000) {
            val unrelated = frame("Robot/LoopTimeMs", it.toDouble())
            store.accept(unrelated); source.emit(unrelated); runCurrent()
        }
        assertEquals(1, results.size)
        advanceTimeBy(100); runCurrent()
        assertEquals(4.0, results.last()[motor.id]?.currentAmps)
        store.clear(); source.emit(fresh)
        advanceTimeBy(100); runCurrent()
        assertTrue(results.last().isEmpty())
        collector.cancel(); runCurrent()
        assertEquals(0, source.subscriptionCount.value)
    }

    @Test fun compositionDisposesDisabledObserversAndWaitsForFreshPublicationsOnReenable() = runTest {
        val source = MutableSharedFlow<TelemetryFrame>(replay = 1)
        val store = TelemetryStore()
        val nt = mock(Nt4ClientService::class.java)
        given(nt.telemetryStore).willReturn(store)
        given(nt.uiTelemetryFlow).willReturn(source)
        val enabled = mutableStateOf(true)
        val clock = MonotonicClock { testScheduler.currentTime * 1_000_000 }
        var observed = emptyMap<String, TopologyMotorReading>()
        val scene = ImageComposeScene(10, 10, coroutineContext = StandardTestDispatcher(testScheduler))
        fun pump() {
            runCurrent(); scene.render(testScheduler.currentTime * 1_000_000).close()
            runCurrent(); scene.render(testScheduler.currentTime * 1_000_000).close(); runCurrent()
        }
        try {
            scene.setContent {
                val readings = rememberTopologyReadings(nt, listOf(motor), enabled.value, clock)
                SideEffect { observed = readings }
            }
            pump(); assertEquals(1, source.subscriptionCount.value)
            val first = frame(current, 2.0); store.accept(first); source.emit(first)
            advanceTimeBy(100); pump(); assertEquals(2.0, observed[motor.id]?.currentAmps)
            enabled.value = false; pump()
            assertTrue(observed.isEmpty()); assertEquals(0, source.subscriptionCount.value)
            enabled.value = true; pump()
            assertTrue(observed.isEmpty()); assertEquals(1, source.subscriptionCount.value)
            val second = frame(current, 7.0); store.accept(second); source.emit(second)
            advanceTimeBy(100); pump(); assertEquals(7.0, observed[motor.id]?.currentAmps)
            store.clear(); advanceTimeBy(100); pump(); assertTrue(observed.isEmpty())
        } finally { scene.close(); runCurrent() }
        assertEquals(0, source.subscriptionCount.value)
    }
}
