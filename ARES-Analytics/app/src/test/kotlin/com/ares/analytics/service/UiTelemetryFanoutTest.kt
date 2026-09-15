package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

class UiTelemetryFanoutTest {
    @Test
    fun `same-topic bursts become one latest UI update while distinct topics survive`() = runTest {
        val fanout = UiTelemetryFanout(backgroundScope, frameIntervalMs = 10L)
        val received = mutableListOf<TelemetryFrame>()
        val collector = backgroundScope.launch { fanout.updates.take(2).collect(received::add) }

        fanout.offer(frame("Drive/Pose_X", 1.0))
        fanout.offer(frame("Drive/Pose_X", 2.0))
        fanout.offer(frame("Drive/Pose_Y", 3.0))

        withTimeout(1_000L) { collector.join() }
        assertEquals(
            mapOf("Drive/Pose_X" to 2.0, "Drive/Pose_Y" to 3.0),
            received.associate { it.key to it.value },
        )
    }

    @Test
    fun `target reset drops pending and replayed values from the previous target`() = runTest {
        val fanout = UiTelemetryFanout(backgroundScope, frameIntervalMs = 10L)
        fanout.offer(frame("Old/Value", 1.0), frameTargetEpoch = 0L)
        fanout.reset(nextTargetEpoch = 1L)

        assertEquals(null, withTimeoutOrNull(50L) { fanout.updates.first() })

        fanout.offer(frame("New/Value", 2.0), frameTargetEpoch = 1L)
        assertEquals(2.0, withTimeout(1_000L) { fanout.updates.first() }.value)

        fanout.reset(nextTargetEpoch = 2L)
        assertEquals(null, withTimeoutOrNull(50L) { fanout.updates.first() })
    }

    private fun frame(key: String, value: Double) = TelemetryFrame(
        timestampMs = 100L,
        sessionId = "test",
        key = key,
        value = value,
    )
}
