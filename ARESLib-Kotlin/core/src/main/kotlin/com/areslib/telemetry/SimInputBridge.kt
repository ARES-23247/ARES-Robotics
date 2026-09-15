package com.areslib.telemetry

import com.areslib.networktables.NT4Server
import com.areslib.util.RobotClock
import com.areslib.telemetry.schema.DesktopDriveProtocol
import com.areslib.telemetry.schema.DesktopDriveFrameGate

/**
 * Process-wide NT4 adapter for the canonical [DriveFrameReceiver]. The payload is exactly
 * [2, sessionNonce, sequence, clientMonotonicMs, vx, vy, omega, flags]. Standalone simulator
 * engines use their own receiver instead of sharing this process-global protocol state.
 */
object SimInputBridge {
    data class CommandFrame(
        val vx: Double,
        val vy: Double,
        val omega: Double,
        val isIntaking: Boolean,
        val isFlywheelOn: Boolean,
        val isTransferring: Boolean,
        val isTeleopMode: Boolean,
        val isFieldCentric: Boolean,
        val isRedAlliance: Boolean,
        val isButtonAPressed: Boolean,
        val isButtonBPressed: Boolean,
        val isButtonXPressed: Boolean,
        val isPoseReset: Boolean,
        val sessionNonce: Long,
        val sequence: Long,
        val clientMonotonicMs: Long,
        val receivedAtMs: Long
    )

    private val receiver = DriveFrameReceiver()
    private val inputBuffer = DoubleArray(DesktopDriveProtocol.VALUE_COUNT)
    private val malformedFrame = DoubleArray(0)

    @Synchronized
    @JvmStatic
    fun pollNetworkFrame(nowMs: Long = RobotClock.currentTimeMillis()): CommandFrame {
        val count = NT4Server.copyDoubleArray(TelemetryTopicConstants.DRIVE_INPUT_FRAME, inputBuffer)
        return receiver.acceptFrame(when {
            count == -1 -> null
            count == inputBuffer.size -> inputBuffer
            else -> malformedFrame
        }, nowMs)
    }

    @JvmStatic
    fun currentFrame(nowMs: Long = RobotClock.currentTimeMillis()): CommandFrame = receiver.currentFrame(nowMs)

    @Synchronized
    @JvmStatic
    fun copyAcknowledgement(destination: DoubleArray, nowMs: Long = RobotClock.currentTimeMillis()): Int =
        receiver.copyAcknowledgement(destination, nowMs)

    @Synchronized
    @JvmStatic
    fun reset() { receiver.reset(); inputBuffer.fill(0.0) }

    const val ACK_VALUE_COUNT = DesktopDriveFrameGate.ACK_VALUE_COUNT
    const val LEASE_TIMEOUT_MS = 500L
    const val MAX_TRANSLATION_MPS = DesktopDriveProtocol.MAX_TRANSLATION_METERS_PER_SECOND
    const val MAX_OMEGA_RADIANS_PER_SECOND = DesktopDriveProtocol.MAX_ANGULAR_RADIANS_PER_SECOND
}
