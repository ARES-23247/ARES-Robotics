package com.areslib.telemetry

import com.areslib.logging.ReplaySummary
import java.lang.Thread.sleep

/**
 * AdvantageScope live playback streaming publisher.
 * Satisfies TEL-01 by broadcasting twin traces (/Odom/Real and /Odom/Ghost)
 * sequentially over NT4 NetworkTables with authentic frame-to-frame sleep delays.
 */
class ReplayPublisher(private val telemetry: NT4Telemetry = NT4Telemetry()) {

    /**
     * Streams the replayed dual traces over NetworkTables at the specified speed multiplier.
     * @param summary The replayed state summary containing the parallel coordinates
     * @param speedMultiplier Playback rate speed scaler (1.0 = real-time, 2.0 = double speed, etc.)
     */
    fun publishReplay(summary: ReplaySummary, speedMultiplier: Double = 1.0) {
        val steps = summary.steps
        if (steps.isEmpty()) return

        val firstTimestamp = steps[0].timestampMs
        var lastStepTime = firstTimestamp

        for (step in steps) {
            val deltaMs = step.timestampMs - lastStepTime
            if (deltaMs > 0) {
                val sleepTime = (deltaMs / speedMultiplier).toLong()
                if (sleepTime > 0) {
                    try {
                        sleep(sleepTime)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }

            // Stream dual pose arrays to AdvantageScope formatted NT4 topics
            telemetry.putPose2d("/Odom/Real", step.realPose.x, step.realPose.y, step.realPose.heading.radians)
            telemetry.putPose2d("/Odom/Ghost", step.ghostPose.x, step.ghostPose.y, step.ghostPose.heading.radians)

            lastStepTime = step.timestampMs
        }
    }
}
