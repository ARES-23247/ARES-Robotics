package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.mockito.Mockito.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardHealthAuditTest {
    private class Fixture(val test: TestScope) {
        val store = TelemetryStore()
        val database = DatabaseMetrics()
        val replay = MutableStateFlow(ReplayCacheMetrics())
        var connection = Nt4ConnectionMetrics(1, 1, 0, true)
        var nanos = 0L
        lateinit var service: DashboardHealthService
        suspend fun accept(key: String = "Value", value: Double = 0.0, text: String? = null) {
            store.accept(TelemetryFrame(1_000, "live", key, value, text))
        }
        suspend fun bytes(current: Double, completed: Double) {
            accept("Diagnostics/Logging/CurrentFileBytes", current)
            accept("Diagnostics/Logging/CompletedBytes", completed)
        }
        fun stop() { if (::service.isInitialized) service.dispose() }
        fun start() {
            val nt = mock(Nt4ClientService::class.java)
            `when`(nt.connectionMetrics()).thenAnswer { connection }
            val engine = mock(ReplayEngineService::class.java)
            `when`(engine.cacheMetrics).thenReturn(replay)
            service = DashboardHealthService(store, database, nt, engine,
                MonotonicClock { nanos }, StandardTestDispatcher(test.testScheduler))
            test.runCurrent()
        }
        fun tick(at: Long = nanos + 1_000_000_000L): DashboardHealthSnapshot {
            nanos = at
            test.advanceTimeBy(1_000)
            test.runCurrent()
            return service.health.value
        }
    }
    private fun audit(block: suspend Fixture.() -> Unit) = runTest {
        val fixture = Fixture(this)
        try { fixture.block() } finally {
            fixture.stop()
            runCurrent()
        }
    }

    @Test fun `existing counters are a baseline instead of startup traffic`() = audit {
        repeat(10) { accept() }; bytes(1_000.0, 2_000.0); start()
        val result = tick()
        assertEquals(0.0, result.ingestFramesPerSecond)
        assertEquals(0.0, result.robotLogBytesPerSecond)
    }
    @Test fun `ordinary elapsed time determines new traffic rates`() = audit {
        bytes(10.0, 20.0); start(); tick()
        repeat(6) { accept() }; bytes(20.0, 20.0)
        val result = tick(nanos + 2_000_000_000L)
        assertEquals(4.0, result.ingestFramesPerSecond)
        assertEquals(5.0, result.robotLogBytesPerSecond)
    }
    @Test fun `zero elapsed time retains pending traffic for next valid interval`() = audit {
        start(); tick(); repeat(3) { accept() }
        assertEquals(0.0, tick(nanos).ingestFramesPerSecond)
        assertEquals(3.0, tick().ingestFramesPerSecond)
    }
    @Test fun `backward clock does not consume pending traffic or move baseline`() = audit {
        start(); tick(); repeat(3) { accept() }
        assertEquals(0.0, tick(500_000_000L).ingestFramesPerSecond)
        assertEquals(3.0, tick(2_000_000_000L).ingestFramesPerSecond)
    }
    @Test fun `nano clock signed wrap preserves elapsed interval`() = audit {
        nanos = Long.MAX_VALUE - 500_000_000L; start(); repeat(3) { accept() }
        assertEquals(3.0, tick().ingestFramesPerSecond)
    }
    @Test fun `new target epoch resets rates even when counters exceed previous target`() = audit {
        repeat(2) { accept() }; bytes(100.0, 0.0); start(); tick()
        store.clear(); repeat(20) { accept() }; bytes(1_000.0, 0.0)
        val changed = tick()
        assertEquals(0.0, changed.ingestFramesPerSecond)
        assertEquals(0.0, changed.robotLogBytesPerSecond)
        accept(); assertEquals(1.0, tick().ingestFramesPerSecond)
    }
    @Test fun `first available robot byte counters establish a baseline`() = audit {
        start(); tick(); bytes(1_000.0, 2_000.0)
        assertEquals(0.0, tick().robotLogBytesPerSecond)
        bytes(1_010.0, 2_000.0)
        assertEquals(10.0, tick().robotLogBytesPerSecond)
    }
    @Test fun `large byte totals retain small increments without overflow or rounding loss`() = audit {
        bytes(0.0, Double.MAX_VALUE); start(); tick(); bytes(1.0, Double.MAX_VALUE)
        assertEquals(1.0, tick().robotLogBytesPerSecond)
        bytes(2.0, Double.MAX_VALUE)
        assertEquals(1.0, tick().robotLogBytesPerSecond)
    }
    @Test fun `byte increase may exceed signed long without becoming zero`() = audit {
        bytes(0.0, 0.0); start(); tick(); bytes(Double.MAX_VALUE, Double.MAX_VALUE)
        assertEquals(Long.MAX_VALUE.toDouble() * 2.0, tick().robotLogBytesPerSecond)
    }
    @Test fun `positive submillisecond interval is not inflated to one millisecond`() = audit {
        start(); tick(); accept()
        assertEquals(1_000_000_000.0, tick(nanos + 1L).ingestFramesPerSecond, 0.000001)
    }
    @Test fun `log rotation preserves total progress and counter decrease rebases`() = audit {
        bytes(100.0, 200.0); start(); tick(); bytes(20.0, 300.0)
        assertEquals(20.0, tick().robotLogBytesPerSecond)
        bytes(1.0, 0.0); assertEquals(0.0, tick().robotLogBytesPerSecond)
        bytes(11.0, 0.0); assertEquals(10.0, tick().robotLogBytesPerSecond)
    }
    @Test fun `prefetch hits are a subset of installed window loads`() = audit {
        start(); replay.value = ReplayCacheMetrics(windowLoads = 4, prefetchHits = 3)
        assertEquals(0.75, tick().replayCacheHitRatio)
        replay.value = ReplayCacheMetrics(windowLoads = Long.MAX_VALUE, prefetchHits = Long.MAX_VALUE)
        assertEquals(1.0, tick().replayCacheHitRatio)
        replay.value = ReplayCacheMetrics(); assertEquals(0.0, tick().replayCacheHitRatio)
    }
    @Test fun `queue thresholds and critical precedence remain exact`() = audit {
        start(); accept("Diagnostics/Logging/QueueDepth", 499.0)
        assertEquals(DashboardHealthStatus.HEALTHY, tick().status)
        accept("Diagnostics/Logging/QueueDepth", 500.0)
        assertEquals(DashboardHealthStatus.DEGRADED, tick().status)
        accept("Diagnostics/Logging/QueueDepth", 900.0)
        assertEquals(DashboardHealthStatus.CRITICAL, tick().status)
        accept("Diagnostics/Logging/QueueDepth", 0.0)
        replay.value = ReplayCacheMetrics(truncatedWindows = 1)
        assertEquals(DashboardHealthStatus.CRITICAL, tick().status)
    }
    @Test fun `database and connection metrics retain units and status thresholds`() = audit {
        start(); database.recordRead(75_000_000)
        assertEquals(DashboardHealthStatus.DEGRADED, tick().status)
        database.recordRead(250_000_000)
        val slow = tick()
        assertEquals(DashboardHealthStatus.CRITICAL, slow.status)
        assertEquals(250.0, slow.databaseP95Ms); assertEquals(2, slow.databaseQueries)
        assertTrue(slow.connected)
    }
    @Test fun `reconnects and robot drops degrade health independently`() = audit {
        start(); connection = connection.copy(reconnects = 3)
        assertEquals(DashboardHealthStatus.DEGRADED, tick().status)
        connection = connection.copy(reconnects = 0)
        accept("Diagnostics/Logging/DroppedFrames", 1.0)
        val dropped = tick()
        assertEquals(DashboardHealthStatus.DEGRADED, dropped.status)
        assertEquals(1, dropped.robotLogDroppedFrames); assertNull(dropped.droppedFrames)
    }
    @Test fun `logging numeric inputs are finite nonnegative and bounded`() = audit {
        accept("Diagnostics/Logging/QueueDepth", Double.MAX_VALUE)
        accept("Diagnostics/Logging/CurrentFileBytes", Double.NaN)
        accept("Diagnostics/Logging/CompletedBytes", Double.POSITIVE_INFINITY)
        accept("Diagnostics/Logging/DroppedFrames", -5.0)
        accept("Diagnostics/Logging/PrunedFiles", 2.9)
        val health = readRobotLoggingHealth(store)
        assertEquals(Int.MAX_VALUE, health.queueDepth); assertEquals(0, health.currentFileBytes)
        assertEquals(0, health.completedBytes); assertEquals(0, health.droppedFrames)
        assertEquals(2, health.prunedFiles); assertEquals("UNKNOWN", health.profile)
    }
    @Test fun `unavailable byte counters discard baseline until both return`() = audit {
        bytes(100.0, 200.0); start(); tick()
        accept("Diagnostics/Logging/CurrentFileBytes", Double.NaN)
        assertEquals(0.0, tick().robotLogBytesPerSecond)
        bytes(1_000.0, 2_000.0)
        assertEquals(0.0, tick().robotLogBytesPerSecond)
        bytes(1_010.0, 2_000.0)
        assertEquals(10.0, tick().robotLogBytesPerSecond)
    }
    @Test fun `dispose stops sampling and remains idempotent`() = audit {
        start(); tick(); val previous = service.health.value
        service.dispose(); service.dispose(); accept()
        assertSame(previous, tick())
    }
}
