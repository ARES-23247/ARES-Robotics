package org.aresfirst.marvin.marvin

import com.areslib.Store

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
            if (elapsedMs < 0L || elapsedMs >= TRANSFER_DURATION_MS) {
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
