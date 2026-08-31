package org.aresfirst.marvin.marvin

import com.areslib.control.assist.ShotConfig
import com.areslib.math.geometry.Translation2d

/** Tuned Marvin XIX mechanism, shot, and official 2024 Crescendo field constants. */
object MarvinConfig {
    /** Bumper-to-bumper footprint used by field-boundary validation. */
    const val ROBOT_BUMPER_LENGTH_METERS = 0.80
    const val ROBOT_BUMPER_WIDTH_METERS = 0.80

    /** Surveyed mechanism envelopes in the robot frame (+X forward, +Y left). */
    object MechanismGeometry {
        const val INTAKE_CAPTURE_MIN_X_METERS = 0.25
        const val INTAKE_CAPTURE_MAX_X_METERS = 0.65
        const val INTAKE_CAPTURE_HALF_WIDTH_METERS = 0.40
        const val SHOOTER_EXIT_X_METERS = -0.45
        const val SHOOTER_EXIT_Y_METERS = -0.055626
        const val SHOOTER_EXIT_HEIGHT_METERS = 0.60
    }

    /**
     * Feeder transfer speed (RPS) commanded when a shot is fired. Matches the aligned
     * run speed used by [MarvinFeederController.updateFeeders] so autonomous and teleop
     * feed the shooter consistently. Without this, the feeder motor receives 0V and no
     * note is launched even after transfer is enabled.
     */
    const val FEEDER_SHOOT_SPEED_RPS = 10.0

    /** Manual spin-up presets used by the copilot controls. */
    object OperatorShotPresets {
        const val CLOSE_FLYWHEEL_RPM = 3350.0
        const val CLOSE_COWL_ROTATIONS = 0.5
        const val MID_FLYWHEEL_RPM = 3650.0
        const val MID_COWL_ROTATIONS = 1.1
    }

    /**
     * Maximum safe cowl/hood travel in mechanism rotations. Sourced by both the
     * [MarvinCowlController] software clamp and the [FRCCowlHardwareIO] TalonFX forward
     * soft limit so the controller clamp and the physical soft limit can never diverge
     * (previously the controller allowed up to 2.0 while the soft limit stopped at 1.80,
     * driving the motor into a sustained stall).
     */
    const val cowlMaxRotations = 1.80

    /** Mechanism travel limits shared by software arbitration and TalonFX configuration. */
    object MechanismLimits {
        const val climberMinRotations = 0.0
        const val climberMaxRotations = 1.73
        const val intakeStowedDegrees = 0.0
        const val intakeDeployedDegrees = 90.0
        const val intakeClearanceDegrees = 5.0
        const val climberClearanceRotations = 0.05
    }

    /**
     * Ballistic lookup configuration.
     *
     * Offsets and lookup keys are meters, time-of-flight values and delay are seconds, flywheel
     * values are RPM, and cowl values are mechanism rotations. A rearward-facing shooter requires
     * the aim solution to add pi radians to the field bearing.
     */
    val SHOT_CONFIG = ShotConfig(
        shooterOffsetX = MechanismGeometry.SHOOTER_EXIT_X_METERS,
        shooterOffsetY = MechanismGeometry.SHOOTER_EXIT_Y_METERS,
        tofKeys = doubleArrayOf(1.24, 2.0, 3.0, 4.0, 5.6),
        tofValues = doubleArrayOf(0.128, 0.212, 0.345, 0.481, 0.795),
        shotKeys = doubleArrayOf(
            1.24, 2.0, 2.2, 2.5, 3.0, 3.2, 3.4, 3.63, 3.80, 4.0, 4.2, 4.4, 4.6, 4.8, 5.0, 5.2, 5.4, 5.6
        ),
        shotRpm = doubleArrayOf(
            3350.0, 3400.0, 3450.0, 3500.0, 3550.0, 3600.0, 3650.0, 3700.0, 3750.0, 3800.0, 3850.0, 3900.0, 3950.0, 4000.0, 4050.0, 4100.0, 4150.0, 4200.0
        ),
        shotCowlRotations = doubleArrayOf(
            0.50, 0.70, 0.80, 0.95, 1.10, 1.15, 1.20, 1.25, 1.30, 1.35, 1.40, 1.45, 1.50, 1.55, 1.60, 1.65, 1.70, 1.75
        ),
        delayCompensationSeconds = 0.05,
        shooterFacesRearward = true
    )

    /**
     * Single source of truth for the FRC 2024 Crescendo speaker scoring-target
     * coordinates (meters). Referenced by teleop aiming, the dyn4j sim scoring
     * detector, and the sim field builder so every site agrees on the target.
     */
    object FieldTargets {
        // Official FIRST 2024 layout: the speaker/subwoofer centerline is at
        // Y=218.42 in. The scoring plane is the alliance wall at each field end.
        private const val SPEAKER_CENTER_Y_METERS = 218.42 * 0.0254
        val blueSpeaker = Translation2d(0.0, SPEAKER_CENTER_Y_METERS)
        val redSpeaker = Translation2d(
            com.areslib.math.coordinate.CoordinateTransformers.FRC_FIELD_LENGTH,
            SPEAKER_CENTER_Y_METERS
        )

        /** Active shuttle targets in the same blue-origin field frame as speaker aiming. */
        val blueShuttle = Translation2d(2.0, 2.0)
        val redShuttle = Translation2d(14.6, 2.0)
    }
}
