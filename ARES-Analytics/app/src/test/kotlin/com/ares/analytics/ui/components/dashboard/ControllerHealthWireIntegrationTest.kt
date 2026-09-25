package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.MonotonicClock
import com.ares.analytics.service.Nt4ClientService
import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server
import com.areslib.util.RobotClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.mockito.Mockito.mock
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Real loopback NT4 and the UI health collector; no hardware or simulator process is started. */
class ControllerHealthWireIntegrationTest {
    @Test fun `stationary health remains fresh over real wire then expires when producer stops`() = runBlocking {
        assertNull(NT4Server.getInstance(), "The test must not replace another owned server")
        val server = NT4Instance.defaultInstance.startServer("127.0.0.1", 0)
        val client = Nt4ClientService(mock(DatabaseService::class.java))
        val receiptClock = AtomicLong()
        val observation = MutableStateFlow(ControllerHealthObservation())
        // The runtime flushes every loop. A single flush may defer a value while the
        // socket still drains its preceding topic announcement.
        val flusher = launch(Dispatchers.Default) {
            while (isActive) {
                server.flush()
                delay(10)
            }
        }
        val collector = launch(Dispatchers.Default) {
            observeLiveControllerHealth(client.uiTelemetryFlow, client.telemetryStore::currentTargetEpoch,
                client.telemetryStore::isCurrentNotifiedFrame, MonotonicClock(receiptClock::get))
                .collect { observation.value = it }
        }
        try {
            await { server.port > 0 }
            client.start("127.0.0.1", "acceptance", "test", "stationary", port = server.port)
            withTimeout(5_000) { client.isConnected.first { it } }
            repeat(7) { step ->
                val robotMillis = 10_000L + step * 500L
                receiptClock.set(step * 500_000_000L)
                RobotClock.useMockTime(robotMillis)
                server.putTopic("Robot/LoopTimeMs", 20.0)
                server.putTopic("Robot/BatteryVoltage", 12.8)
                server.flush()
                await { client.latestValues["Robot/LoopTimeMs"]?.timestampMs == robotMillis }
                withTimeout(5_000) {
                    observation.first { it.snapshot.loopTimeMs == 20.0 && it.snapshot.batteryVoltage == 12.8 }
                }
            }
            receiptClock.addAndGet(ControllerHealthTracker.STALE_AFTER_NANOS + 1)
            RobotClock.useMockTime(16_000)
            server.putTopic("Drive/TestCounter", 99.0)
            server.flush()
            await { client.latestValues["Drive/TestCounter"]?.value == 99.0 }
            withTimeout(5_000) {
                observation.first { it.snapshot.loopTimeMs == null && it.snapshot.batteryVoltage == null }
            }
            assertTrue(client.isConnected.value, "An open connection does not renew stale health readings")
        } finally {
            withContext(NonCancellable) {
                collector.cancelAndJoin()
                flusher.cancelAndJoin()
                try { assertTrue(client.disposeAndJoin()) }
                finally {
                    server.stop()
                    NT4Server.resetSharedState()
                    RobotClock.useSystemTime()
                }
            }
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }
}
