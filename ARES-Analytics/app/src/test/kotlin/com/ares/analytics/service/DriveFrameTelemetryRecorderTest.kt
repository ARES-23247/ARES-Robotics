package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DriveFrameTelemetryRecorderTest {
    @Test
    fun `completed recorder rejects new snapshots`() = runBlocking {
        val owner = Job()
        val recorder = DriveFrameTelemetryRecorder(CoroutineScope(coroutineContext + owner)) { }
        owner.cancelAndJoin()
        assertFalse(recorder.offer(snapshot(1.0)))
    }

    @Test
    fun `already cancelled scope cannot accept snapshots`() = runBlocking {
        val owner = Job().apply { cancel() }
        val recorder = DriveFrameTelemetryRecorder(CoroutineScope(coroutineContext + owner)) { }
        owner.join()
        assertFalse(recorder.offer(snapshot(1.0)))
    }

    @Test
    fun `slow telemetry indexing never blocks offer and pending snapshots are conflated`() = runBlocking {
        val accepted = mutableListOf<TelemetryFrame>()
        val recorder = DriveFrameTelemetryRecorder(this, sampleIntervalMs = 10L) { frame ->
            kotlinx.coroutines.delay(10L)
            accepted += frame
        }

        withTimeout(1_000L) {
            assertTrue(recorder.offer(snapshot(axisValue = 1.0)))
            assertTrue(recorder.offer(snapshot(axisValue = 2.0)))
            assertTrue(recorder.offer(snapshot(axisValue = 3.0)))
        }

        withTimeout(1_000) {
            while (accepted.size < 3) kotlinx.coroutines.yield()
        }

        assertEquals(listOf("ARES/Input/driveFrame/4", "ARES/Input/driveFrame/5", "ARES/Input/driveFrame/6"), accepted.map { it.key })
        assertEquals(listOf(3.0, 3.0, 3.0), accepted.map { it.value })
        assertEquals(3, accepted.size)
        coroutineContext.cancelChildren()
    }

    private fun snapshot(axisValue: Double) = DriveFrameTelemetrySnapshot(
        timestampMs = 100L,
        sessionId = "test",
        values = doubleArrayOf(2.0, 7.0, axisValue, 10.0, axisValue, axisValue, axisValue, 56.0),
    )
}
