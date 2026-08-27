package com.areslib.frc

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import com.areslib.state.VisionState
import com.areslib.frc.marvin.*
import com.areslib.telemetry.GamepadState
import com.areslib.frc.hardware.FlywheelIO
import com.areslib.frc.hardware.CowlIO
import com.areslib.frc.hardware.IntakeIO
import com.areslib.frc.hardware.FeederIO
import com.areslib.frc.hardware.FloorIO
import com.areslib.frc.hardware.ClimberIO
import com.areslib.frc.hardware.FRCClimberHardwareIO
import com.areslib.frc.hardware.FRCCowlHardwareIO
import com.areslib.frc.hardware.FRCFeederHardwareIO
import com.areslib.frc.hardware.FRCFloorHardwareIO
import com.areslib.frc.hardware.FRCFlywheelHardwareIO
import com.areslib.frc.hardware.FRCIntakeHardwareIO

import edu.wpi.first.math.VecBuilder

import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.hardware.vision.VisionIO

import edu.wpi.first.wpilibj.TimedRobot
import edu.wpi.first.wpilibj.XboxController
import edu.wpi.first.wpilibj.RobotBase
import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.Filesystem

import com.areslib.frc.robot.FRCAutoOrchestrator
import com.areslib.frc.robot.FrcLocalizationCalibrationControls
import com.areslib.frc.robot.FrcAutoCapabilities
import com.areslib.frc.robot.FRCTeleOpDriveController
import com.areslib.frc.robot.FrcSysIdController
import com.areslib.frc.generatedruntime.FrcGeneratedControlsRuntime
import com.areslib.frc.vision.FrcLocalizationCalibrationSession
import com.areslib.frc.vision.FrcVisionTracker
import com.areslib.frc.generated.subsystems.GeneratedSubsystemRegistry
import com.areslib.frc.generated.subsystems.superstructure.GeneratedSuperstructureRegistry
import com.areslib.frc.sim.FrcDashboardDriveInput
import com.areslib.frc.sim.applyTo

/** Returns false when any real mechanism adapter reports failed or reset configuration. */
internal fun mechanismsConfigured(
    vararg devices: com.areslib.frc.hardware.FrcMechanismConfigurationStatus
): Boolean {
    for (device in devices) {
        if (!device.configurationValid) return false
    }
    return true
}

/** Returns false until every relative-only position mechanism has a deliberate safe zero. */
internal fun mechanismsHomed(
    vararg devices: com.areslib.frc.hardware.FrcMechanismHomingStatus
): Boolean {
    for (device in devices) {
        if (!device.homed) return false
    }
    return true
}

internal fun mechanismSafetyHealthy(
    configurationValid: Boolean,
    homingValid: Boolean,
    fatalUpdateFailure: Throwable?
): Boolean = configurationValid && homingValid && fatalUpdateFailure == null

internal fun mechanismHomingComboPressed(
    driverBack: Boolean,
    driverStart: Boolean,
    operatorBack: Boolean,
    operatorStart: Boolean
): Boolean = driverBack && driverStart && operatorBack && operatorStart

internal fun mechanismHomingRequestAllowed(isDisabled: Boolean, isTestEnabled: Boolean): Boolean =
    isDisabled && !isTestEnabled

/** Rejects an unavailable or suspicious enabled PDH total so shared fallback remains truthful. */
internal fun validatedPdhCurrent(readingAmps: Double, robotEnabled: Boolean): Double =
    if (readingAmps.isFinite() && readingAmps >= 0.0 && (!robotEnabled || readingAmps > 0.0)) {
        readingAmps
    } else {
        Double.NaN
    }

internal data class FrcFieldContract(
    val config: com.areslib.state.RobotFieldConfig,
    val aprilTagLayout: edu.wpi.first.apriltag.AprilTagFieldLayout,
)

/** Pure field loader used by both the roboRIO and desktop simulation composition paths. */
internal object FrcFieldContractLoader {
    var error: String? = null
        private set

    fun load(bytes: ByteArray): FrcFieldContract? {
        error = null
        val config = runCatching {
            com.areslib.state.RobotFieldDocument.decode(bytes.decodeToString())
        }.getOrElse { failure ->
            error = failure.message ?: failure::class.java.simpleName
            return null
        }
        val issues = com.areslib.state.RobotFieldValidator.validate(
            config,
            com.areslib.state.FieldType.FRC,
            requireAprilTags = true,
        )
        if (issues.isNotEmpty()) {
            error = issues.first().message
            return null
        }
        return runCatching {
            FrcFieldContract(
                config,
                com.areslib.frc.vision.FrcAprilTagFieldLayoutFactory.create(config),
            )
        }.getOrElse { failure ->
            error = failure.message ?: failure::class.java.simpleName
            null
        }
    }
}

internal fun loadFrcFieldContract(bytes: ByteArray): FrcFieldContract? = FrcFieldContractLoader.load(bytes)

/**
 * WPILib lifecycle and dependency-composition root for Marvin XIX.
 *
 * This shell selects real or simulated IO, owns controller snapshots, and delegates
 * state/control work to [FrcSwerveRobot], [FRCTeleOpDriveController], and
 * [FRCAutoOrchestrator]. Its registered 20 ms ARES update refreshes hardware before
 * sensor reads and Redux-derived output writes. Mode callbacks remain orchestration-only.
 */
class ARESRobot : TimedRobot() {

    private lateinit var robot: FrcSwerveRobot
    private var sim: Dyn4jSimulation? = null
    private var dashboardDriveInput: FrcDashboardDriveInput? = null
    private lateinit var marvinShooter: MarvinShooterSubsystem

    private val controller = XboxController(0)
    private val coPilotController = XboxController(1)
    private val controllerState = GamepadState()
    private val coPilotControllerState = GamepadState()

    private lateinit var teleOpController: FRCTeleOpDriveController
    private lateinit var autoOrchestrator: FRCAutoOrchestrator
    private lateinit var generatedControlsRuntime: FrcGeneratedControlsRuntime
    private lateinit var teleopGeneratedCapabilities: com.areslib.frc.generatedruntime.FrcGeneratedRoutineCapabilities
    private lateinit var sysIdController: FrcSysIdController
    private var localizationCalibration: FrcLocalizationCalibrationSession? = null
    private var localizationVisionTracker: FrcVisionTracker? = null
    private var calibrationControls: FrcLocalizationCalibrationControls? = null

    private var cachedAlliance: DriverStation.Alliance = DriverStation.Alliance.Blue
    private val RED_SPEAKER = MarvinConfig.FieldTargets.redSpeaker
    private val BLUE_SPEAKER = MarvinConfig.FieldTargets.blueSpeaker
    private val superstructureTelemetry = DoubleArray(14)
    private val swerveCalibrationSamples = SwerveOffsetCalibrationSampleCache()
    private val calibrationEncoderPositions = DoubleArray(SwerveOffsetCalibrationSampleCache.MODULE_COUNT)
    private var mechanismConfigurationValid = true
    private var mechanismConfigurationDevices =
        emptyArray<com.areslib.frc.hardware.FrcMechanismConfigurationStatus>()
    private var mechanismConfigurationContractComplete = true
    private var mechanismHomingValid = true
    private var mechanismHomingDevices = emptyArray<com.areslib.frc.hardware.FrcMechanismHomingStatus>()
    private var flywheelTuningStatus: com.areslib.frc.hardware.FrcFlywheelTuningStatus? = null
    private var homingComboWasPressed = false

    // Simulation timing
    private var lastSimTime = 0.0
    private val can2Bus = com.ctre.phoenix6.CANBus(
        com.areslib.frc.generated.drivebase.GeneratedAresDrivebaseConfig.CTRE_CAN_BUS
    )
    private var powerDistribution: edu.wpi.first.wpilibj.PowerDistribution? = null


    /** Constructs IO, the composed reducer/store, subsystem lifecycle, and mode controllers. */
    override fun robotInit() {
        edu.wpi.first.wpilibj.Threads.setCurrentThreadPriority(true, 10)

        val isReal = RobotBase.isReal()
        val fieldContract = runCatching {
            java.io.File(Filesystem.getDeployDirectory(), "paths/field.json").readBytes()
        }.getOrNull()?.let(::loadFrcFieldContract)
        if (fieldContract != null) {
            com.areslib.state.RobotFieldManager.setActiveConfig(fieldContract.config)
        } else {
            com.areslib.state.RobotFieldManager.setActiveConfig(
                com.areslib.state.RobotFieldConfig(
                    id = "unavailable-frc-season-field",
                    name = "Unavailable FRC season field",
                    fieldType = com.areslib.state.FieldType.FRC,
                    widthMeters = 16.541,
                    heightMeters = 8.211,
                    apriltags = emptyList(),
                )
            )
            DriverStation.reportError(
                "ARES: canonical field unavailable; AprilTag localization disabled: ${FrcFieldContractLoader.error}",
                false,
            )
        }

        // 1. Declare the hardware IO instances (either physical or simulation)
        val swerveIO: SwerveHardwareIO?
        val visionIO: VisionIO?
        val flywheelIO: FlywheelIO
        val cowlIO: CowlIO
        val intakeIO: IntakeIO
        val feederIO: FeederIO
        val floorIO: FloorIO
        val climberIO: ClimberIO

        if (isReal) {
            powerDistribution = try {
                edu.wpi.first.wpilibj.PowerDistribution()
            } catch (error: Exception) {
                DriverStation.reportError(
                    "ARES: PowerDistribution initialization failed; current monitoring will fail closed: " +
                        error.message,
                    false
                )
                null
            }
            // can2Bus is already defined as a class property
            val leftMasterFX = com.ctre.phoenix6.hardware.TalonFX(9, can2Bus)
            val leftFollowerFX = com.ctre.phoenix6.hardware.TalonFX(10, can2Bus)
            val rightMasterFX = com.ctre.phoenix6.hardware.TalonFX(11, can2Bus)
            val rightFollowerFX = com.ctre.phoenix6.hardware.TalonFX(12, can2Bus)
            val cowlFX = com.ctre.phoenix6.hardware.TalonFX(13, can2Bus)
            val pivotFX = com.ctre.phoenix6.hardware.TalonFX(14, can2Bus)
            val rollerFX = com.ctre.phoenix6.hardware.TalonFX(15, can2Bus)
            val floorFX = com.ctre.phoenix6.hardware.TalonFX(16, can2Bus)
            val climberFX = com.ctre.phoenix6.hardware.TalonFX(19, can2Bus)
            val feederFX = com.ctre.phoenix6.hardware.TalonFX(20, can2Bus)

            val defaultOffsets = frc.robot.generated.TunerConstants.getDefaultOffsets()
            val activeOffsets = com.areslib.drivetrain.SwerveOffsetManager.loadOffsets(
                defaultOffsets = defaultOffsets,
                typedTuningOffsets = com.areslib.frc.config.CanonicalDrivebaseConfig.profiledOffsets()
            )

            val ctreDrivetrain = frc.robot.generated.TunerConstants.TunerSwerveDrivetrain(
                frc.robot.generated.TunerConstants.DrivetrainConstants,
                0.0,
                VecBuilder.fill(0.1, 0.1, 0.1),
                VecBuilder.fill(0.9, 0.9, 0.9),
                frc.robot.generated.TunerConstants.createFrontLeft(edu.wpi.first.units.Units.Rotations.of(activeOffsets.frontLeft)),
                frc.robot.generated.TunerConstants.createFrontRight(edu.wpi.first.units.Units.Rotations.of(activeOffsets.frontRight)),
                frc.robot.generated.TunerConstants.createBackLeft(edu.wpi.first.units.Units.Rotations.of(activeOffsets.backLeft)),
                frc.robot.generated.TunerConstants.createBackRight(edu.wpi.first.units.Units.Rotations.of(activeOffsets.backRight))
            )
            swerveIO = FRCSwerveHardwareIO(ctreDrivetrain)

            // Each Limelight retains its independently surveyed robot-space transform from
            // its own web UI. Passing no pose is intentional: never overwrite either camera
            // with a shared placeholder extrinsic.
            val validTagIds = fieldContract?.config?.apriltags
                ?.sortedBy(com.areslib.state.RobotFieldAprilTag::id)
                ?.map(com.areslib.state.RobotFieldAprilTag::id)
                ?.toIntArray()
                ?: IntArray(0)
            visionIO = if (validTagIds.isNotEmpty()) {
                val limelightShooter = FrcLimelightIO(
                    tableName = "limelight-shooter",
                    validFiducialIds = validTagIds,
                )
                val limelightBack = FrcLimelightIO(
                    tableName = "limelight-back",
                    validFiducialIds = validTagIds,
                )
                com.areslib.hardware.vision.CompositeVisionIO(listOf(limelightShooter, limelightBack))
            } else {
                null
            }

            flywheelIO = FRCFlywheelHardwareIO(leftMasterFX, leftFollowerFX, rightMasterFX, rightFollowerFX)
            cowlIO = FRCCowlHardwareIO(cowlFX)
            intakeIO = FRCIntakeHardwareIO(pivotFX, rollerFX)
            feederIO = FRCFeederHardwareIO(feederFX)
            floorIO = FRCFloorHardwareIO(floorFX)
            climberIO = FRCClimberHardwareIO(climberFX)
        } else {
            // Simulation IOs
            val simInstance = fieldContract?.config?.let { config ->
                Dyn4jSimulation(config = config, seed = 42L)
            } ?: Dyn4jSimulation(seed = 42L)
            sim = simInstance
            // Desktop control is intentionally constructed only in simulation. A dashboard frame
            // can never replace physical driver input on a real RoboRIO.
            dashboardDriveInput = FrcDashboardDriveInput()
            swerveIO = null
            visionIO = null
            flywheelIO = simInstance.flywheelIO
            cowlIO = simInstance.cowlIO
            intakeIO = simInstance.intakeIO
            feederIO = simInstance.feederIO
            floorIO = simInstance.floorIO
            climberIO = simInstance.climberIO
        }

        mechanismConfigurationDevices = listOf(
            flywheelIO, cowlIO, intakeIO, feederIO, floorIO, climberIO
        ).mapNotNull { it as? com.areslib.frc.hardware.FrcMechanismConfigurationStatus }
            .toTypedArray()
        mechanismConfigurationContractComplete = !isReal ||
            mechanismConfigurationDevices.size == EXPECTED_REAL_MECHANISM_COUNT
        mechanismConfigurationValid = mechanismConfigurationContractComplete &&
            mechanismsConfigured(*mechanismConfigurationDevices)
        mechanismHomingDevices = if (isReal) {
            arrayOf(
                cowlIO as com.areslib.frc.hardware.FrcMechanismHomingStatus,
                intakeIO as com.areslib.frc.hardware.FrcMechanismHomingStatus,
                climberIO as com.areslib.frc.hardware.FrcMechanismHomingStatus
            )
        } else {
            emptyArray()
        }
        mechanismHomingValid = mechanismsHomed(*mechanismHomingDevices)
        flywheelTuningStatus = flywheelIO as? com.areslib.frc.hardware.FrcFlywheelTuningStatus

        // Register each logical mechanism once for lifecycle/telemetry and attach its physical CAN
        // identity for dashboard topology discovery. Multi-motor mechanisms retain every member ID
        // in metadata while canId identifies the primary controller.
        com.areslib.hardware.HardwareRegistry.registerDevice(
            "Flywheel", flywheelIO, marvinCanTopology("Flywheel", 9, 9, 10, 11, 12)
        )
        com.areslib.hardware.HardwareRegistry.registerDevice(
            "Cowl", cowlIO, marvinCanTopology("Cowl", 13, 13)
        )
        com.areslib.hardware.HardwareRegistry.registerDevice(
            "Intake", intakeIO, marvinCanTopology("Intake", 14, 14, 15)
        )
        com.areslib.hardware.HardwareRegistry.registerDevice(
            "Feeder", feederIO, marvinCanTopology("Feeder", 20, 20)
        )
        com.areslib.hardware.HardwareRegistry.registerDevice(
            "Floor", floorIO, marvinCanTopology("Floor", 16, 16)
        )
        com.areslib.hardware.HardwareRegistry.registerDevice(
            "Climber", climberIO, marvinCanTopology("Climber", 19, 19)
        )

        // 2. Compose the root reducer with the Marvin reducer
        fun composedReducer(state: RobotState, action: RobotAction): RobotState {
            return MarvinReducer.reduce(state, action)
        }

        // 3. Create the initial state containing the MarvinState
        val initialState = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState()
            ),
            vision = VisionState(
                filterConfig = com.areslib.hardware.vision.VisionFilterConfig.frcDefaults()
            ),
            tuning = com.areslib.frc.config.CanonicalDrivebaseConfig.initialTuningState(),
        )

        // 4. Instantiate FrcSwerveRobot
        robot = FrcSwerveRobot(
            swerveIO = swerveIO,
            visionIO = visionIO,
            isSimulation = !isReal,
            initialState = initialState,
            reducer = ::composedReducer
        )

        val tuningRuntime = com.areslib.frc.generated.drivebase.GeneratedAresTuningConfig.createRuntime()
        robot.tuningManager = com.areslib.tuning.TuningManager(
            runtime = tuningRuntime,
            telemetry = robot.telemetryManager.dataLoggingTelemetry,
            contextProvider = {
                // Marvin has no dedicated armed live-tuning mode yet. Metadata remains visible,
                // while all mutation fails closed until that explicit operator workflow exists.
                com.areslib.tuning.TuningApplyContext(
                    sessionArmed = false,
                    robotDisabled = DriverStation.isDisabled(),
                )
            },
            onApplied = { parameterUid, _ ->
                if (com.areslib.frc.config.CanonicalDrivebaseConfig.supportsRuntimeParameter(parameterUid)) {
                    robot.store.dispatch(
                        RobotAction.UpdateTuningState(
                            com.areslib.frc.config.CanonicalDrivebaseConfig.withRuntimeValues(
                                robot.store.state.tuning,
                                tuningRuntime,
                            )
                        )
                    )
                    true
                } else {
                    false
                }
            },
        )

        if (swerveIO != null) {
            com.areslib.hardware.HardwareRegistry.registerDevice(
                "Swerve",
                swerveIO,
                com.areslib.hardware.TopologyNode(
                    id = "Swerve",
                    type = com.areslib.hardware.TopologyNodeType.CANIVORE,
                    displayName = "CTRE Swerve (${com.areslib.frc.generated.drivebase.GeneratedAresDrivebaseConfig.CTRE_CAN_BUS})",
                    canBus = com.areslib.frc.generated.drivebase.GeneratedAresDrivebaseConfig.CTRE_CAN_BUS,
                    connectionType = "CAN-FD",
                    metadata = mapOf(
                        "driveMotorCanIds" to "8,2,6,4",
                        "steerMotorCanIds" to "7,1,5,3",
                        "encoderCanIds" to "14,11,13,12",
                        "pigeonCanId" to "9"
                    )
                )
            )
        }
        if (visionIO != null) {
            com.areslib.hardware.HardwareRegistry.registerDevice(
                "Vision",
                visionIO,
                com.areslib.hardware.TopologyNode(
                    id = "Vision",
                    type = com.areslib.hardware.TopologyNodeType.CAMERA,
                    displayName = "Dual Limelight",
                    connectionType = "NetworkTables",
                    metadata = mapOf("sources" to "limelight-shooter,limelight-back")
                )
            )
        }

        robot.store.actionListener = { action ->
            if (action is RobotAction.CalibrateSwerveOffsets && swerveIO != null) {
                val nowMs = com.areslib.util.RobotClock.currentTimeMillis()
                if (swerveCalibrationSamples.copyFresh(nowMs, calibrationEncoderPositions)) {
                    val defaultOffsets = frc.robot.generated.TunerConstants.getDefaultOffsets()
                    val activeOffsets = com.areslib.drivetrain.SwerveOffsetManager.loadOffsets(defaultOffsets)
                    val newOffsets = com.areslib.drivetrain.SwerveOffsetData(
                        frontLeft = activeOffsets.frontLeft - calibrationEncoderPositions[0],
                        frontRight = activeOffsets.frontRight - calibrationEncoderPositions[1],
                        backLeft = activeOffsets.backLeft - calibrationEncoderPositions[2],
                        backRight = activeOffsets.backRight - calibrationEncoderPositions[3]
                    )
                    com.areslib.drivetrain.SwerveOffsetManager.saveRuntimeOffsets(
                        newOffsets,
                        robot.telemetryManager.dataLoggingTelemetry
                    )
                } else {
                    val message = "Swerve offset calibration rejected: four fresh, finite, plausible " +
                        "absolute-encoder readings are required"
                    robot.telemetry.putString("Calibration/Swerve/Error", message)
                    DriverStation.reportError(message, false)
                }
            }
        }

        applyMechanismSafetyPolicy("initialization")

        // Generated subsystem DSL participates in the same lifecycle as handwritten mechanisms.
        GeneratedSubsystemRegistry.createAll(isReal).forEach(robot::registerSubsystem)
        // Generated superstructures coordinate only generated Redux targets and must run after
        // those generated subsystem lifecycles have been installed.
        GeneratedSuperstructureRegistry.createAll().forEach(robot::registerSubsystem)

        // 5. Create and register the MarvinSuperstructure subsystem
        val superstructureSubsystem = MarvinSuperstructure(
            flywheelIO = flywheelIO,
            cowlIO = cowlIO,
            intakeIO = intakeIO,
            feederIO = feederIO,
            floorIO = floorIO,
            climberIO = climberIO
        )
        robot.registerSubsystem(superstructureSubsystem)

        // 6. Instantiate the facades
        marvinShooter = MarvinShooterSubsystem(robot.store)

        // 7. Register a custom telemetry publisher for Marvin state
        robot.telemetryManager.customPublishers.add { state, telemetry ->
            val marvin = state.superstructure.marvin
            // Log Marvin state
            superstructureTelemetry[0] = marvin.flywheel.velocityRpm
            superstructureTelemetry[1] = marvin.flywheel.targetVelocityRpm
            superstructureTelemetry[2] = marvin.cowl.angleRotations
            superstructureTelemetry[3] = marvin.cowl.targetAngleRotations
            superstructureTelemetry[4] = marvin.intake.pivotAngleDegrees
            superstructureTelemetry[5] = marvin.intake.targetAngleDegrees
            superstructureTelemetry[6] = if (marvin.intake.isDeployed) 1.0 else 0.0
            superstructureTelemetry[7] = marvin.intake.rollerVelocityRps
            superstructureTelemetry[8] = marvin.feeder.velocityRps
            superstructureTelemetry[9] = if (marvin.feeder.gamePieceDetected) 1.0 else 0.0
            superstructureTelemetry[10] = marvin.floor.velocityRps
            superstructureTelemetry[11] = marvin.climber.positionRotations
            superstructureTelemetry[12] = marvin.climber.targetVoltage
            superstructureTelemetry[13] = if (marvin.slamtakeActive) 1.0 else 0.0
            telemetry.putDoubleArray("Superstructure/PackedState", superstructureTelemetry)
            telemetry.putBoolean("Safety/MechanismFaultLatched", marvin.mechanismSafetyFaultLatched)
            telemetry.putString("Safety/MechanismFaultReason", marvin.mechanismSafetyFaultReason)
            if (edu.wpi.first.wpilibj.RobotBase.isReal()) {
                val loopCounter = (state.timestampMs / 20) // 50Hz
                if (loopCounter % 25L == 0L) { // 2Hz
                    telemetry.putNumber(
                        "${com.areslib.frc.generated.drivebase.GeneratedAresDrivebaseConfig.CTRE_CAN_BUS}/BusUtilization",
                        can2Bus.status.BusUtilization.toDouble(),
                    )
                }
            }
        }

        lastSimTime = com.areslib.util.RobotClock.currentTimeMillis() / 1000.0

        // Wire brownout guard to read live battery voltage from roboRIO
        robot.batteryVoltageSupplier = java.util.function.DoubleSupplier {
            try {
                edu.wpi.first.wpilibj.RobotController.getBatteryVoltage()
            } catch (_: Exception) {
                0.0 // Unknown voltage must fail closed; simulation supplies a valid value.
            }
        }
        if (isReal) {
            robot.totalCurrentSupplier = java.util.function.DoubleSupplier {
                val reading = try {
                    powerDistribution?.totalCurrent ?: Double.NaN
                } catch (_: Exception) {
                    Double.NaN
                }
                validatedPdhCurrent(reading, DriverStation.isEnabled())
            }
            robot.brownedOutSupplier = java.util.function.BooleanSupplier {
                edu.wpi.first.wpilibj.RobotController.isBrownedOut()
            }
        }

        teleOpController = FRCTeleOpDriveController(
            robot, marvinShooter,
            controller, coPilotController, controllerState, coPilotControllerState
        )
        sysIdController = FrcSysIdController(robot.telemetryManager.dataLoggingTelemetry, flywheelIO)
        applyAlliance(DriverStation.getAlliance().orElse(DriverStation.Alliance.Blue))
        FrcAutoCapabilities.register()
        teleopGeneratedCapabilities = com.areslib.frc.generatedruntime.FrcGeneratedRoutineCapabilities(robot)
        generatedControlsRuntime = FrcGeneratedControlsRuntime(
            stateProvider = { robot.store.state },
            dispatch = robot.store::dispatch,
            capabilities = teleopGeneratedCapabilities,
            // The hand controller runs first; while one of its assists (copilot X-lock or
            // speaker/shuttle aiming) owns the frame, scheme stick drive must stay silent.
            driveEmissionGate = { !teleOpController.drivetrainAssistActive },
        )
        autoOrchestrator = FRCAutoOrchestrator(robot, sim)
        autoOrchestrator.publishCatalog()
        robot.telemetry.putString("ARES/Controls/Source", generatedControlsRuntime.controlsSource)
        robot.publishHardwareTopology(MARVIN_ROBOT_ID)

        addPeriodic({
            try {
                robot.update(controllerState, coPilotControllerState)
                if (mechanismHomingValid) {
                    for (device in mechanismHomingDevices) {
                        if (!device.homed) {
                            mechanismHomingValid = false
                            throw IllegalStateException(
                                "Relative mechanism reference was lost; Disabled safe-zero recovery is required"
                            )
                        }
                    }
                }
                if (swerveIO != null) {
                    swerveCalibrationSamples.record(
                        swerveIO,
                        com.areslib.util.RobotClock.currentTimeMillis()
                    )
                }
                val tuningEnabled = DriverStation.isTestEnabled()
                robot.isLiveTuningEnabled = tuningEnabled
                if (flywheelTuningStatus?.lastTuningApplySuccessful == false) {
                    throw IllegalStateException("Flywheel live tuning did not reach every motor")
                }
                mechanismConfigurationValid = mechanismConfigurationContractComplete &&
                    mechanismsConfigured(*mechanismConfigurationDevices)
                if (!mechanismConfigurationValid) {
                    throw IllegalStateException(
                        "A mechanism Talon reset or lost its verified configuration; restart and re-home"
                    )
                }
                sysIdController.update(
                    timestampMs = com.areslib.util.RobotClock.currentTimeMillis(),
                    state = robot.store.state,
                    enabledForTuning = tuningEnabled,
                    hardwareSafetyPermitted = isMechanismHardwarePermitted(),
                    powerScale = robot.powerManager.powerScale,
                    brownedOut = robot.powerManager.isBrownedOut
                )
            } catch (failure: Throwable) {
                DriverStation.reportError(
                    "Periodic loop exception: ${failure.message ?: failure::class.java.simpleName}",
                    false
                )
                latchMechanismFault(
                    "Periodic loop exception: ${failure.message ?: failure::class.java.simpleName}"
                )
            }
        }, 0.02, 0.005)
    }

    private var allianceCheckCounter = 0

    /** Refreshes cached controller snapshots and polls alliance changes while disabled. */
    override fun robotPeriodic() {
        if (DriverStation.isDisabled() && allianceCheckCounter++ % 50 == 0) {
            val allianceOpt = DriverStation.getAlliance()
            if (allianceOpt.isPresent) {
                val alliance = allianceOpt.get()
                if (alliance != cachedAlliance) applyAlliance(alliance)
            }
        }
        controller.updateState(controllerState)
        coPilotController.updateState(coPilotControllerState)
        dashboardDriveInput?.poll()?.let { command ->
            command.applyTo(controllerState)
            val requestedAlliance = if (command.isRedAlliance) {
                DriverStation.Alliance.Red
            } else {
                DriverStation.Alliance.Blue
            }
            if (requestedAlliance != cachedAlliance) applyAlliance(requestedAlliance)
        }
    }

    private fun applyAlliance(alliance: DriverStation.Alliance) {
        cachedAlliance = alliance
        robot.store.dispatch(
            RobotAction.SetAlliance(
                if (alliance == DriverStation.Alliance.Red) {
                    com.areslib.state.Alliance.RED
                } else {
                    com.areslib.state.Alliance.BLUE
                }
            )
        )
        teleOpController.cachedAlliance = alliance
        teleOpController.speakerTranslation = if (alliance == DriverStation.Alliance.Red) RED_SPEAKER else BLUE_SPEAKER
    }

    override fun disabledInit() {
        cancelGeneratedControls("FRC disabled")
        if (::autoOrchestrator.isInitialized) autoOrchestrator.stop()
        if (::sysIdController.isInitialized) sysIdController.stop()
        if (::robot.isInitialized) stopMechanismsForDisable()
        controller.setRumble(edu.wpi.first.wpilibj.GenericHID.RumbleType.kBothRumble, 0.0)
        coPilotController.setRumble(edu.wpi.first.wpilibj.GenericHID.RumbleType.kBothRumble, 0.0)
    }

    override fun disabledPeriodic() {
        handleMechanismHomingRequest()
    }

    // ── Teleop ──

    override fun teleopInit() {
        cancelGeneratedControls("FRC TeleOp initialized")
        autoOrchestrator.stop()
        sysIdController.stop()
        applyAlliance(DriverStation.getAlliance().orElse(cachedAlliance))
        teleOpController.teleopInit()
        applyMechanismSafetyPolicy("teleop initialization")
    }

    override fun teleopPeriodic() {
        try {
            teleOpController.teleopPeriodic()
            generatedControlsRuntime.update()
        } catch (error: Throwable) {
            DriverStation.reportError(
                "Teleop control exception: ${error.message ?: error::class.java.simpleName}",
                false
            )
            latchMechanismFault(
                "Teleop control exception: ${error.message ?: error::class.java.simpleName}"
            )
        }
    }

    // ── Autonomous ──

    override fun autonomousInit() {
        cancelGeneratedControls("FRC autonomous initialized")
        sysIdController.stop()
        applyMechanismSafetyPolicy("autonomous initialization")
        applyAlliance(DriverStation.getAlliance().orElse(cachedAlliance))
        autoOrchestrator.autonomousInit()
    }

    override fun autonomousPeriodic() {
        autoOrchestrator.autonomousPeriodic()
    }

    override fun autonomousExit() {
        autoOrchestrator.stop()
    }

    // ── Localization calibration (Driver Station Test mode) ──

    override fun testInit() {
        cancelGeneratedControls("FRC test initialized")
        autoOrchestrator.stop()
        sysIdController.stop()
        applyAlliance(DriverStation.getAlliance().orElse(cachedAlliance))
        applyMechanismSafetyPolicy("test initialization")
        robot.swerveDrive.brake()
        localizationCalibration?.close()
        val tracker = robot.visionTracker as? FrcVisionTracker
        localizationVisionTracker = tracker
        localizationCalibration = FrcLocalizationCalibrationSession(
            store = robot.store,
            swerveIO = robot.swerveDrivetrainIO,
            measurementsProvider = { tracker?.visionInputs?.measurements ?: emptyList() }
        )
        calibrationControls = FrcLocalizationCalibrationControls(
            session = localizationCalibration!!,
            timestampMs = { com.areslib.util.RobotClock.currentTimeMillis() },
        ).also { it.reset() }
    }

    override fun testPeriodic() {
        val homingComboPressed = handleMechanismHomingRequest()
        val calibration = localizationCalibration ?: return
        teleOpController.drivePeriodic()
        calibrationControls?.update(
            controller = controller,
            tracker = localizationVisionTracker,
            telemetry = robot.telemetry,
            homingComboPressed = homingComboPressed,
        )
    }

    override fun testExit() {
        sysIdController.stop()
        localizationVisionTracker?.fusionEnabled = true
        localizationVisionTracker = null
        localizationCalibration?.close()
        localizationCalibration = null
    }

    private fun applyMechanismSafetyPolicy(source: String) {
        mechanismConfigurationValid = mechanismConfigurationContractComplete &&
            mechanismsConfigured(*mechanismConfigurationDevices)
        val configurationHealthy = mechanismConfigurationValid &&
            (flywheelTuningStatus?.lastTuningApplySuccessful != false)
        mechanismHomingValid = mechanismsHomed(*mechanismHomingDevices)
        val healthy = mechanismSafetyHealthy(
            configurationHealthy,
            mechanismHomingValid,
            robot.fatalUpdateFailure
        )
        if (healthy) {
            // The reducer refuses this temporary clear while a distinct fault remains latched.
            robot.store.dispatch(SetMechanismSafetyInhibit(false))
        } else {
            robot.store.dispatch(
                LatchMechanismSafetyFault(
                    "Mechanism safety validation failed during $source " +
                        "(configured=$configurationHealthy, homed=$mechanismHomingValid, " +
                        "fatalUpdate=${robot.fatalUpdateFailure != null})"
                )
            )
        }
        val persistentFault = robot.store.state.superstructure.marvin
        robot.telemetry.putBoolean("Safety/MechanismConfigurationValid", configurationHealthy)
        robot.telemetry.putBoolean("Safety/MechanismsHomed", mechanismHomingValid)
        robot.telemetry.putBoolean("Safety/MechanismFaultLatched", persistentFault.mechanismSafetyFaultLatched)
        robot.telemetry.putString("Safety/MechanismFaultReason", persistentFault.mechanismSafetyFaultReason)
        if (!healthy) {
            DriverStation.reportError(
                "ARES: mechanism safety validation failed during $source " +
                    "(configured=$configurationHealthy, homed=$mechanismHomingValid, " +
                    "fatalUpdate=${robot.fatalUpdateFailure != null}); " +
                    "outputs remain inhibited",
                false
            )
            robot.safeHardware()
        } else if (persistentFault.mechanismSafetyFaultLatched) {
            DriverStation.reportError(
                "ARES: mechanism fault remains latched during $source; Disabled dual-operator " +
                    "recovery is required (${persistentFault.mechanismSafetyFaultReason})",
                false
            )
            robot.safeHardware()
        }
    }

    private fun isMechanismHardwarePermitted(): Boolean {
        val configurationHealthy = mechanismConfigurationValid &&
            (flywheelTuningStatus?.lastTuningApplySuccessful != false)
        return mechanismSafetyHealthy(
            configurationHealthy,
            mechanismHomingValid,
            robot.fatalUpdateFailure
        ) && !robot.store.state.superstructure.marvin.mechanismSafetyInhibited &&
            !robot.store.state.superstructure.marvin.mechanismSafetyFaultLatched
    }

    /**
     * Both operators must hold Back+Start while Disabled after physically placing
     * the cowl, intake pivot, and climber at their documented zero stops.
     */
    private fun handleMechanismHomingRequest(): Boolean {
        val comboPressed = mechanismHomingComboPressed(
            controller.backButton,
            controller.startButton,
            coPilotController.backButton,
            coPilotController.startButton
        )
        if (comboPressed && !homingComboWasPressed) {
            if (mechanismHomingRequestAllowed(DriverStation.isDisabled(), DriverStation.isTestEnabled())) {
                robot.safeHardware()
                var allSucceeded = true
                for (device in mechanismHomingDevices) {
                    if (!device.homeAtKnownZero()) allSucceeded = false
                }
                mechanismHomingValid = allSucceeded && mechanismsHomed(*mechanismHomingDevices)
                mechanismConfigurationValid = mechanismConfigurationContractComplete &&
                    mechanismsConfigured(*mechanismConfigurationDevices)
                val recoveryHealthy = mechanismSafetyHealthy(
                    mechanismConfigurationValid &&
                        (flywheelTuningStatus?.lastTuningApplySuccessful != false),
                    mechanismHomingValid,
                    robot.fatalUpdateFailure
                )
                if (recoveryHealthy) {
                    robot.store.dispatch(
                        ClearMechanismSafetyFault("Dual-operator Disabled safe-zero recovery")
                    )
                }
                applyMechanismSafetyPolicy("operator-confirmed safe-zero homing")
                if (mechanismHomingValid) {
                    DriverStation.reportWarning("ARES: cowl, intake pivot, and climber safe zeros accepted", false)
                }
            } else {
                DriverStation.reportError("ARES: mechanism recovery rejected outside Disabled", false)
            }
        }
        homingComboWasPressed = comboPressed
        return comboPressed
    }

    private fun stopMechanismsForDisable() {
        runCatching { robot.store.dispatch(SetMechanismSafetyInhibit(true)) }
        robot.safeHardware()
    }

    private fun latchMechanismFault(reason: String) {
        runCatching { robot.store.dispatch(LatchMechanismSafetyFault(reason)) }
        robot.safeHardware()
    }

    private fun cancelGeneratedControls(reason: String) {
        if (!::generatedControlsRuntime.isInitialized) return
        try {
            generatedControlsRuntime.cancelAll(reason)
        } catch (error: Throwable) {
            DriverStation.reportError(
                "Generated controller cleanup failed: ${error.message ?: error::class.java.simpleName}",
                false
            )
            if (::robot.isInitialized) {
                latchMechanismFault(
                    "Generated controller cleanup failed: ${error.message ?: error::class.java.simpleName}"
                )
            }
        }
    }

    override fun close() {
        var failure: Throwable? = null
        fun capture(error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            cancelGeneratedControls("FRC robot closing")
        } catch (error: Throwable) {
            capture(error)
        }
        try {
            if (::autoOrchestrator.isInitialized) autoOrchestrator.stop()
        } catch (error: Throwable) {
            capture(error)
        }
        try {
            if (::sysIdController.isInitialized) sysIdController.stop()
        } catch (error: Throwable) {
            capture(error)
        }
        try {
            localizationVisionTracker?.fusionEnabled = true
            localizationCalibration?.close()
        } catch (error: Throwable) {
            capture(error)
        } finally {
            localizationVisionTracker = null
            localizationCalibration = null
        }
        try {
            if (::robot.isInitialized) {
                robot.close()
            } else {
                com.areslib.hardware.HardwareRegistry.closeAll()
            }
        } catch (error: Throwable) {
            capture(error)
        }
        try {
            dashboardDriveInput?.close()
        } catch (error: Throwable) {
            capture(error)
        } finally {
            dashboardDriveInput = null
        }
        try {
            sim?.close()
        } catch (error: Throwable) {
            capture(error)
        } finally {
            sim = null
        }
        try {
            powerDistribution?.close()
        } catch (error: Throwable) {
            capture(error)
        } finally {
            powerDistribution = null
        }
        try {
            super.close()
        } catch (error: Throwable) {
            capture(error)
        }
        failure?.let { throw it }
    }

    // ── Simulation ──

    /** Advances Dyn4j, dispatches ideal simulated odometry/events, and publishes distinct truth. */
    override fun simulationPeriodic() {
        if (!RobotBase.isSimulation()) return
        val simInstance = sim ?: return

        val now = com.areslib.util.RobotClock.currentTimeMillis() / 1000.0
        val dt = (now - lastSimTime).coerceIn(0.0, 0.05)
        lastSimTime = now

        // Step physics and dispatch any resulting actions (ball intake/shoot)
        val actions = simInstance.step(robot.store.state, dt)
        for (action in actions) {
            robot.store.dispatch(action)
        }

        // Feed the ideal simulated odometry observation through Redux. Dyn4j truth is published
        // separately by publishVisualization; it never overwrites estimator telemetry topics.
        val poseUpdate = simInstance.getPoseUpdate()
        robot.store.dispatch(poseUpdate)

        // Publish 3D visualization
        simInstance.publishVisualization(robot.store.state, robot.telemetry)
    }

    private companion object {
        const val EXPECTED_REAL_MECHANISM_COUNT = 6
        const val MARVIN_ROBOT_ID = "Marvin-XIX"
    }
}

internal fun marvinCanTopology(
    displayName: String,
    primaryCanId: Int,
    vararg memberCanIds: Int
): com.areslib.hardware.TopologyNode = com.areslib.hardware.TopologyNode(
    id = displayName,
    type = com.areslib.hardware.TopologyNodeType.CAN_MOTOR_CONTROLLER,
    displayName = displayName,
    canId = primaryCanId,
    canBus = com.areslib.frc.generated.drivebase.GeneratedAresDrivebaseConfig.CTRE_CAN_BUS,
    busPosition = primaryCanId,
    connectionType = "CAN-FD",
    metadata = mapOf(
        "canIds" to memberCanIds.joinToString(","),
        "controllerModel" to "TalonFX"
    )
)
