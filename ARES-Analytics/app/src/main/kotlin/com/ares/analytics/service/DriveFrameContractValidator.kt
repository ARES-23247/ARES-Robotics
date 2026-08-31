package com.ares.analytics.service

import com.areslib.telemetry.schema.DesktopDriveProtocol
import kotlin.math.abs
import kotlin.math.floor

internal data class DriveFrameSendState(
    val sessionNonce: Double,
    val sequence: Double,
    val clientMonotonicMs: Double,
)

/** Stateful sender-side mirror of the receiver's fail-closed desktop drive session contract. */
internal class DriveFrameContractValidator {
    private var committedState: DriveFrameSendState? = null

    fun validate(values: DoubleArray): DriveFrameSendState {
        require(values.size == DesktopDriveProtocol.VALUE_COUNT) { "drive frame must contain exactly 8 values" }
        require(values.all(Double::isFinite)) { "drive frame values must be finite" }
        require(values[DesktopDriveProtocol.VERSION_INDEX] == DesktopDriveProtocol.VERSION) {
            "unsupported drive frame protocol"
        }
        requireSafeInteger(values[DesktopDriveProtocol.SESSION_INDEX], positive = true, label = "session nonce")
        requireSafeInteger(values[DesktopDriveProtocol.SEQUENCE_INDEX], positive = false, label = "sequence")
        requireSafeInteger(values[DesktopDriveProtocol.CLIENT_TIME_INDEX], positive = false, label = "client monotonic time")
        requireSafeInteger(values[DesktopDriveProtocol.FLAGS_INDEX], positive = false, label = "flags")
        require(values[DesktopDriveProtocol.FLAGS_INDEX].toLong() and DesktopDriveProtocol.KNOWN_FLAGS_MASK.inv() == 0L) {
            "drive flags contain unknown bits"
        }
        require(abs(values[DesktopDriveProtocol.VX_INDEX]) <= DesktopDriveProtocol.MAX_TRANSLATION_METERS_PER_SECOND &&
            abs(values[DesktopDriveProtocol.VY_INDEX]) <= DesktopDriveProtocol.MAX_TRANSLATION_METERS_PER_SECOND) {
            "drive translation exceeds ${DesktopDriveProtocol.MAX_TRANSLATION_METERS_PER_SECOND} m/s"
        }
        require(abs(values[DesktopDriveProtocol.OMEGA_INDEX]) <= DesktopDriveProtocol.MAX_ANGULAR_RADIANS_PER_SECOND) {
            "drive rotation exceeds ${DesktopDriveProtocol.MAX_ANGULAR_RADIANS_PER_SECOND} rad/s"
        }

        val next = DriveFrameSendState(
            values[DesktopDriveProtocol.SESSION_INDEX],
            values[DesktopDriveProtocol.SEQUENCE_INDEX],
            values[DesktopDriveProtocol.CLIENT_TIME_INDEX],
        )
        val current = committedState
        if (current == null || current.sessionNonce != next.sessionNonce) {
            val flags = values[DesktopDriveProtocol.FLAGS_INDEX].toLong()
            require(values[DesktopDriveProtocol.VX_INDEX] == 0.0 &&
                values[DesktopDriveProtocol.VY_INDEX] == 0.0 &&
                values[DesktopDriveProtocol.OMEGA_INDEX] == 0.0) {
                "a new drive session must begin with neutral axes"
            }
            require((flags and DesktopDriveProtocol.ACTUATING_OR_EDGE_FLAGS) == 0L) {
                "a new drive session must begin with neutral actuator and edge flags"
            }
        } else {
            require(next.sequence > current.sequence) { "drive sequence must strictly increase" }
            require(next.clientMonotonicMs >= current.clientMonotonicMs) {
                "drive client monotonic time moved backwards"
            }
        }
        return next
    }

    fun commit(state: DriveFrameSendState) {
        committedState = state
    }

    fun reset() {
        committedState = null
    }

    private fun requireSafeInteger(value: Double, positive: Boolean, label: String) {
        require(value == floor(value) && value <= DesktopDriveProtocol.MAX_SAFE_INTEGER_DOUBLE &&
            (if (positive) value > 0.0 else value >= 0.0)) {
            "drive $label must be ${if (positive) "a positive" else "a non-negative"} exactly representable integer"
        }
    }
}
