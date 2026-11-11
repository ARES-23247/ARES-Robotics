package com.areslib.frc

import com.areslib.action.RobotAction
import com.areslib.hardware.HardwareRegistry
import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.hardware.vision.VisionFilterConfig
import com.areslib.hardware.vision.VisionIO
import com.areslib.reducer.rootReducer
import com.areslib.state.RobotState
import com.areslib.state.VisionState
import com.areslib.subsystem.Subsystem
import com.areslib.telemetry.*
import com.areslib.telemetry.ITelemetry

/**
 * Fluent Kotlin DSL builder for configuring and instantiating an [FrcSwerveRobot].
 *
 * Parallels `ftcMecanumRobot` on the FTC side by providing a declarative configuration block for
 * CTRE Phoenix 6 swerve IO, AprilTag vision IO, initial Redux state, custom reducers, and
 * registered mechanisms.
 *
 * ### Example Usage:
 * ```kotlin
 * val robot = frcSwerveRobot {
 *     swerveIO = ctreSwerveIO
 *     visionIO = FrcLimelightIO("limelight-front")
 *     isSimulation = RobotBase.isSimulation()
 *     subsystems(elevatorSubsystem, shooterSubsystem)
 * }
 * ```
 *
 * @see FrcSwerveRobot
 * @see frcSwerveRobot
 */
class FrcSwerveRobotBuilder {
    /** Underlying CTRE Phoenix 6 swerve hardware IO (or `null` in headless simulation). */
    var swerveIO: SwerveHardwareIO? = null

    /** Optional Limelight / AprilTag vision IO (or `null` if unattached). */
    var visionIO: VisionIO? = null

    /** `true` when running inside the WPILib / desktop simulator. */
    var isSimulation: Boolean = false

    /** Initial immutable [RobotState] snapshot. Defaults to FRC vision filter defaults. */
    var initialState: RobotState = RobotState(
        vision = VisionState(filterConfig = VisionFilterConfig.frcDefaults()),
    )

    /** Root Redux reducer function composing state transitions. */
    var reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer

    /** Hardware registry tracking device health across the robot. */
    var hardwareRegistry: HardwareRegistry = HardwareRegistry()

    /** Platform telemetry backend. Defaults to [FRCTelemetry]. */
    var baseTelemetry: ITelemetry = FRCTelemetry()

    /** Whether live dashboard tuning updates are enabled on the built robot. */
    var isLiveTuningEnabled: Boolean = false

    /** Optional override for the active DriverStation enable-state supplier (useful in unit tests). */
    var isEnabledProvider: (() -> Boolean)? = null

    /** Optional override for the active FRC match-mode string supplier (useful in unit tests). */
    var robotModeProvider: (() -> String)? = null

    private val registeredSubsystems = mutableListOf<Subsystem>()

    /** Registers one or more [Subsystem] instances with the built [FrcSwerveRobot]. */
    fun subsystems(vararg subsystems: Subsystem) {
        registeredSubsystems += subsystems
    }

    /** Constructs and returns the configured [FrcSwerveRobot] instance. */
    fun build(): FrcSwerveRobot {
        val robot = FrcSwerveRobot(
            swerveIO = swerveIO,
            visionIO = visionIO,
            isSimulation = isSimulation,
            initialState = initialState,
            reducer = reducer,
            hardwareRegistry = hardwareRegistry,
            baseTelemetry = baseTelemetry,
            isEnabledProvider = isEnabledProvider ?: {
                try {
                    edu.wpi.first.wpilibj.DriverStation.isEnabled()
                } catch (_: Throwable) {
                    false
                }
            },
            robotModeProvider = robotModeProvider ?: {
                try {
                    when {
                        edu.wpi.first.wpilibj.DriverStation.isAutonomous() -> "Auto"
                        edu.wpi.first.wpilibj.DriverStation.isTeleop() -> "Teleop"
                        edu.wpi.first.wpilibj.DriverStation.isTest() -> "Test"
                        else -> "Disabled"
                    }
                } catch (_: Throwable) {
                    "Active"
                }
            },
        )
        robot.isLiveTuningEnabled = isLiveTuningEnabled
        registeredSubsystems.forEach { robot.registerSubsystem(it) }
        return robot
    }
}

/**
 * Creates and configures an [FrcSwerveRobot] using a fluent Kotlin DSL.
 *
 * @param block Configuration lambda applied to [FrcSwerveRobotBuilder].
 * @return Initialized [FrcSwerveRobot] instance.
 */
fun frcSwerveRobot(block: FrcSwerveRobotBuilder.() -> Unit = {}): FrcSwerveRobot =
    FrcSwerveRobotBuilder().apply(block).build()
