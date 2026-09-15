package com.areslib.sim

import com.areslib.math.geometry.Pose2d
import com.areslib.sim.opmode.SimOpModeKind

/**
 * Applies explicit mode-based simulator pose ownership without using coordinate sentinels.
 *
 * Autonomous owns its authored OpMode pose even when all components are zero. TeleOp owns the
 * configured physics/alliance spawn. Pinpoint and Redux are always reset to the selected pose, and
 * autonomous additionally moves the physics body to that pose.
 */
internal fun synchronizeSimulatorStartPose(
    modeKind: SimOpModeKind,
    opModePose: Pose2d,
    physicsPose: Pose2d,
    applyPhysicsPose: (Pose2d) -> Unit,
    initializePinpoint: (Pose2d) -> Unit,
    resetReduxPose: (Pose2d) -> Unit,
): Pose2d {
    val selectedPose = when (modeKind) {
        SimOpModeKind.AUTONOMOUS -> opModePose
        SimOpModeKind.TELEOP -> physicsPose
    }
    if (modeKind == SimOpModeKind.AUTONOMOUS) applyPhysicsPose(selectedPose)
    initializePinpoint(selectedPose)
    resetReduxPose(selectedPose)
    return selectedPose
}
