package org.aresfirst.marvin.marvin

import com.areslib.Store
import com.areslib.subsystem.SubsystemControllerBase

/** Shared Redux dispatch-on-change support for Marvin mechanism facades. */
abstract class MarvinControllerBase(store: Store) : SubsystemControllerBase(store)

/** Coordinates the feeder transfer latch and optional floor-roller assist. */
class MarvinFeederController(store: Store) : MarvinControllerBase(store) {
    /** True after a shot transfer has been explicitly started and before cancellation/timeout. */
    val transferActive: Boolean
        get() = store.state.superstructure.marvin.transferActive

    /** Ends the current trigger cycle so a later press may authorize one new transfer. */
    fun cancelTransfer() {
        val marvin = store.state.superstructure.marvin
        // Idle teleop ticks reach this every frame; only dispatch when a cycle is genuinely
        // armed or outputs are live, so the 20 ms loop does not churn no-op Redux reductions.
        if (marvin.transferActive || marvin.transferConsumedForTrigger ||
            marvin.feeder.targetVelocityRps != 0.0 || marvin.floor.targetVelocityRps != 0.0
        ) {
            store.dispatch(ResetTransferCycle())
            stopOutputTargets()
        }
    }

    private fun stopOutputTargets() {
        dispatchOnChange(store.state.superstructure.marvin.feeder.targetVelocityRps, 0.0, ::SetFeederSpeed) {}
        dispatchOnChange(store.state.superstructure.marvin.floor.targetVelocityRps, 0.0, ::SetFloorSpeed) {}
    }

    /**
     * Applies the heading/RPM firing interlock.
     *
     * A transfer already in progress is allowed to finish even if alignment moves out
     * of tolerance. [runFloorRollers] controls whether the floor mirrors feeder speed.
     */
    fun updateFeeders(
        rpmAligned: Boolean,
        headingAligned: Boolean,
        cowlReady: Boolean,
        runFloorRollers: Boolean = false
    ) {
        val nowMs = com.areslib.util.RobotClock.currentTimeMillis()
        val canStartTransfer = headingAligned && rpmAligned && cowlReady
        val transferState = store.state.superstructure.marvin
        if (transferState.transferActive) {
            val elapsedMs = nowMs - transferState.transferStartedAtMs
            if (nowMs < transferState.transferStartedAtMs || elapsedMs < 0L || elapsedMs >= TRANSFER_DURATION_MS) {
                store.dispatch(CompleteTransfer(timestampMs = nowMs))
                stopOutputTargets()
                return
            }
        }
        if (canStartTransfer && !transferState.transferActive && !transferState.transferConsumedForTrigger) {
            store.dispatch(StartTransfer(timestampMs = nowMs))
        }
        val speed = if (transferActive) {
            MarvinConfig.FEEDER_SHOOT_SPEED_RPS
        } else {
            0.0
        }
        dispatchOnChange(store.state.superstructure.marvin.feeder.targetVelocityRps, speed, ::SetFeederSpeed) {}

        val floorSpeed = if (runFloorRollers) speed else 0.0
        dispatchOnChange(store.state.superstructure.marvin.floor.targetVelocityRps, floorSpeed, ::SetFloorSpeed) {}
    }

    internal companion object {
        const val TRANSFER_DURATION_MS = 450L
    }
}

/** Redux facade for the cowl's mechanism-rotation target and software travel clamp. */
class MarvinCowlController(store: Store) : MarvinControllerBase(store) {

    /** Commands mechanism rotations, clamped to the same limit configured in TalonFX IO. */
    fun setCowlAngleRotations(rotations: Double) {
        require(rotations.isFinite()) { "Cowl target rotations must be finite" }
        val clampedRotations = rotations.coerceIn(0.0, MarvinConfig.cowlMaxRotations)
        dispatchOnChange(store.state.superstructure.marvin.cowl.targetAngleRotations, clampedRotations, ::SetCowlAngle) {}
    }

    /** True only when this loop's cowl sample is valid and within the firing tolerance. */
    fun isAngleAligned(targetRotations: Double): Boolean {
        val cowl = store.state.superstructure.marvin.cowl
        return targetRotations.isFinite() &&
            cowl.angleValid &&
            cowl.angleRotations.isFinite() &&
            kotlin.math.abs(cowl.angleRotations - targetRotations) <= COWL_READY_TOLERANCE_ROTATIONS
    }

    private companion object {
        const val COWL_READY_TOLERANCE_ROTATIONS = 0.05
    }
}

/** Redux facade for RPM commands and the fail-closed flywheel readiness gate. */
class MarvinFlywheelController(store: Store) : MarvinControllerBase(store) {

    /** Enables flywheel output and records [targetRpm] in RPM. */
    fun spinUp(targetRpm: Double) {
        require(targetRpm.isFinite() && targetRpm >= 0.0) { "Flywheel target RPM must be finite and nonnegative" }
        dispatchOnChange(store.state.superstructure.marvin.flywheel.targetVelocityRpm, targetRpm, ::SetFlywheelSpeed) {}
        dispatchOnChange(store.state.superstructure.marvin.flywheelActive, true, ::SetFlywheelActive) {}
    }

    /** Clears both the velocity target and active-output latch. */
    fun stop() {
        dispatchOnChange(store.state.superstructure.marvin.flywheel.targetVelocityRpm, 0.0, ::SetFlywheelSpeed) {}
        dispatchOnChange(store.state.superstructure.marvin.flywheelActive, false, ::SetFlywheelActive) {}
    }

    /** True only for a fresh sample within 150 RPM of a nontrivial target. */
    fun isRpmAligned(targetRpm: Double): Boolean {
        val flywheel = store.state.superstructure.marvin.flywheel
        return flywheel.velocityValid && flywheel.allMotorsAtTarget && targetRpm > 100.0 &&
            kotlin.math.abs(flywheel.velocityRpm - targetRpm) < 150.0
    }
}
