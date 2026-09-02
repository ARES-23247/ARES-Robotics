package org.firstinspires.ftc.teamcode.opmodes.robot

import com.areslib.ftc.FtcMecanumRobot

/**
 * Bridges season convenience calls to low-rate Redux-backed telemetry.
 * Driver Station text is capped and rate-limited to avoid dominating the control loop.
 */
class AresTelemetryHelper(private val base: FtcMecanumRobot) {
    private var lastTelemetryUpdateMs: Long = 0L

    /** Stores one custom Driver Station value, truncated to 150 display characters. */
    fun addTelemetry(key: String, value: Any) {
        val truncated = value.toString().take(150)
        base.telemetryManager.customDriverStationText[key] = truncated
    }

    /** Publishes the low-rate pose, battery, and power-budget summary. */
    fun updateTelemetry() {
        val now = com.areslib.util.RobotClock.currentTimeMillis()
        if (now - lastTelemetryUpdateMs < TELEMETRY_PERIOD_MS) return
        lastTelemetryUpdateMs = now

        val alliance = base.store.state.drive.alliance.name
        val estPose = base.store.state.drive.poseEstimator.estimatedPose
        addTelemetry("Alliance", alliance)
        addTelemetry("EKF Pose X", estPose.x)
        addTelemetry("EKF Pose Y", estPose.y)
        addTelemetry("EKF Pose Deg", Math.toDegrees(estPose.heading.radians))
        
        val voltage = base.powerManager.batteryVoltage
        val batteryText = when {
            !voltage.isFinite() || voltage <= 0.0 -> "<font color='red'><b>INVALID</b></font>"
            voltage < 11.5 -> formatLowVoltage(voltage)
            else -> voltage
        }
        addTelemetry("Battery V", batteryText)
        
        addTelemetry("Power Scale", base.powerManager.powerScale)
    }

    private fun formatLowVoltage(voltage: Double): String {
        val tenths = kotlin.math.round(voltage * 10.0).toLong()
        val intPart = tenths / 10
        val fracPart = kotlin.math.abs(tenths % 10)
        return "<font color='red'><b>$intPart.${fracPart}V (LOW)</b></font>"
    }

    companion object {
        private const val TELEMETRY_PERIOD_MS = 100L
    }
}
