package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One dashboard-owned control frame waiting for best-effort analytics indexing. */
internal data class DriveFrameTelemetrySnapshot(
    val timestampMs: Long,
    val sessionId: String,
    val values: DoubleArray,
)

/**
 * Keeps telemetry-history bookkeeping out of the safety-critical drive heartbeat.
 *
 * The channel is deliberately conflated: if telemetry ingestion is busy, analysis keeps the newest
 * control snapshot instead of delaying the next simulator lease renewal. [offer] takes ownership of
 * [DriveFrameTelemetrySnapshot.values]; callers must not mutate that array afterward.
 */
internal class DriveFrameTelemetryRecorder(
    scope: CoroutineScope,
    private val sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS,
    private val accept: suspend (TelemetryFrame) -> Unit,
) {
    private val snapshots = Channel<DriveFrameTelemetrySnapshot>(Channel.CONFLATED)

    init {
        require(sampleIntervalMs > 0L) { "sampleIntervalMs must be positive" }
        scope.launch {
            for (firstSnapshot in snapshots) {
                // Control is transmitted at 50 Hz, but driver analysis does not need that rate.
                // Wait for one analysis interval, then drain to the newest pending snapshot.
                delay(sampleIntervalMs)
                var snapshot = firstSnapshot
                while (true) snapshot = snapshots.tryReceive().getOrNull() ?: break

                ANALYTICS_AXIS_INDICES.forEach { index ->
                    accept(
                        TelemetryFrame(
                            timestampMs = snapshot.timestampMs,
                            sessionId = snapshot.sessionId,
                            key = "ARES/Input/driveFrame/$index",
                            value = snapshot.values[index],
                        )
                    )
                }
            }
        }
    }

    /** Never suspends the control publisher; a newer pending snapshot replaces an older one. */
    fun offer(snapshot: DriveFrameTelemetrySnapshot): Boolean = snapshots.trySend(snapshot).isSuccess

    private companion object {
        const val DEFAULT_SAMPLE_INTERVAL_MS = 100L
        val ANALYTICS_AXIS_INDICES = intArrayOf(4, 5, 6)
    }
}
