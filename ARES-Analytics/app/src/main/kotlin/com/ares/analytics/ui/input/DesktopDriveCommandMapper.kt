package com.ares.analytics.ui.input

import com.ares.analytics.shared.League

/** Canonical field-relative velocity command emitted by desktop keyboard or gamepad input. */
internal data class DesktopFieldDriveCommand(
    val vxMetersPerSecond: Double,
    val vyMetersPerSecond: Double,
    val omegaRadiansPerSecond: Double,
)

/**
 * Maps driver-perspective controls into the active league's canonical field axes.
 *
 * FTC driver stations face each other along field Y: from the Red perspective, forward is +Y and
 * right is +X. The season input boundary mirrors both translations for Blue, so this mapper must
 * not perform a second alliance transform. Rotation is CCW-positive on both alliances.
 *
 * FRC driver stations face each other along field X: from the Blue perspective, forward is +X and
 * right is -Y. The FRC season input boundary mirrors both translations for Red. This league split
 * applies only to field-centric input; robot-centric axes are identical in FTC and FRC.
 */
internal fun mapDesktopFieldCentricDrive(
    league: League,
    forward: Double,
    right: Double,
    counterClockwise: Double,
    maximumTranslationMps: Double = 4.0,
    maximumAngularRps: Double = if (league == League.FRC) Math.PI else 4.0,
): DesktopFieldDriveCommand {
    val forwardMps = forward.coerceFiniteUnit() * maximumTranslationMps
    val rightMps = right.coerceFiniteUnit() * maximumTranslationMps
    return when (league) {
        // Red-origin FTC: forward is +Y and driver-right is +X.
        League.FTC -> DesktopFieldDriveCommand(
            vxMetersPerSecond = rightMps,
            vyMetersPerSecond = forwardMps,
            omegaRadiansPerSecond = counterClockwise.coerceFiniteUnit() * maximumAngularRps,
        )
        // Blue-origin FRC: forward is +X and driver-right is -Y.
        League.FRC -> DesktopFieldDriveCommand(
            vxMetersPerSecond = forwardMps,
            vyMetersPerSecond = -rightMps,
            omegaRadiansPerSecond = counterClockwise.coerceFiniteUnit() * maximumAngularRps,
        )
    }
}

/**
 * Maps chassis-relative controls. Robot +X is forward and +Y is left in both leagues, so this
 * mapping deliberately has no league argument and must never receive an alliance transform.
 */
internal fun mapDesktopRobotCentricDrive(
    forward: Double,
    right: Double,
    counterClockwise: Double,
    maximumTranslationMps: Double = 4.0,
    maximumAngularRps: Double = 4.0,
): DesktopFieldDriveCommand = DesktopFieldDriveCommand(
    vxMetersPerSecond = forward.coerceFiniteUnit() * maximumTranslationMps,
    vyMetersPerSecond = -right.coerceFiniteUnit() * maximumTranslationMps,
    omegaRadiansPerSecond = counterClockwise.coerceFiniteUnit() * maximumAngularRps,
)

/** TeleOp + field-centric mode bits; alliance is data and does not actuate hardware. */
internal fun desktopDriveModeFlags(isRedAlliance: Boolean): Long =
    (1L shl 3) or (1L shl 4) or (if (isRedAlliance) 1L shl 5 else 0L)

private fun Double.coerceFiniteUnit(): Double = if (isFinite()) coerceIn(-1.0, 1.0) else 0.0
