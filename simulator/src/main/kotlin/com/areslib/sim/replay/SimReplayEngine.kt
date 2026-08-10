package com.areslib.sim.replay

import com.areslib.logging.CloudReplayProvider
import com.areslib.math.geometry.Vector3
import com.areslib.telemetry.NT4Telemetry
import com.areslib.telemetry.ReplayPublisher
import com.areslib.sim.network.TelemetryPublisher

/**
 * Blocking simulator entry point for fetching a cloud run and streaming it over local NT4.
 *
 * Network fetch, decoding, and publication run on the caller's thread. Any failure is reported to
 * stderr and swallowed so an optional replay cannot terminate simulator startup. Robot code remains
 * offline-first; this desktop-only component is the cloud boundary.
 */
object SimReplayEngine {

    /**
     * Loads [replayCloudId], optionally recomputes vision fusion with [customVisionStdDevs], and
     * publishes the complete replay sequentially before returning.
     */
    fun replayCloudRun(replayCloudId: String, customVisionStdDevs: Vector3?) {
        println("[Simulator] Replaying cloud run $replayCloudId...")
        try {
            TelemetryPublisher.javaClass // Ensure NT4 is initialized
            val nt4Telemetry = NT4Telemetry()
            val publisher = ReplayPublisher(nt4Telemetry)
            val summary = CloudReplayProvider.loadRun(replayCloudId, customVisionStdDevs)
            println("[Simulator] Fetched cloud run summary: ${summary.steps.size} steps. Streaming to NetworkTables...")
            publisher.publishReplay(summary)
            println("[Simulator] Cloud replay completed.")
        } catch (e: Exception) {
            System.err.println("Failed to replay cloud run: ${e.message}")
        }
    }
}
