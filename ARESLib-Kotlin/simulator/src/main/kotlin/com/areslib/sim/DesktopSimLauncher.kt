package com.areslib.sim

import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server
import com.areslib.action.RobotAction
import com.areslib.ftc.FtcMecanumRobot
import com.areslib.ftc.FtcTeleopDriveFrame
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.sim.cli.SimCliParser
import com.areslib.sim.field.FieldElementLoader
import com.areslib.sim.field.MecanumInteractionModel
import com.areslib.sim.field.SimGamePieceTelemetryFrame
import com.areslib.sim.infra.VirtualDriverStation
import com.areslib.sim.infra.SimGamepadManager
import com.areslib.sim.model.MecanumRobotDouble
import com.areslib.sim.network.NT4FieldPublisher
import com.areslib.sim.network.TelemetryPublisher
import com.areslib.sim.opmode.SimOpModeRunner
import com.areslib.sim.opmode.SimOpModeKind
import com.areslib.sim.opmode.SimOpModeLifecycle
import com.areslib.sim.opmode.SimOpModeLifecycleSlot
import com.areslib.sim.opmode.SimOpModeState
import com.areslib.sim.physics.SimPhysicsWorld
import com.areslib.state.RobotFieldManager
import com.areslib.util.RobotClock
import java.io.File

/**
 * Object implementation for Desktop Sim Launcher.
 *
 * Robotics framework control component.
 */
object DesktopSimLauncher {
    internal const val SIM_TIMESTEP_SECONDS = 0.02
    internal const val SIM_TIMESTEP_MS = 20L
    internal const val ACTIVE_OP_MODE_STATE_TOPIC = "ARES/DriverStation/ActiveOpModeState"
    internal const val ACTIVE_OP_MODE_CLASS_TOPIC = "ARES/DriverStation/ActiveOpModeClass"
    internal const val ACTIVE_OP_MODE_DISPLAY_NAME_TOPIC = "ARES/DriverStation/ActiveOpModeDisplayName"
    internal const val SUMMARY_OUTPUT_PROPERTY = "ares.sim.summary.path"


    @Volatile private var sumSqErrorX = 0.0
    @Volatile private var sumSqErrorY = 0.0
    @Volatile private var sumSqErrorHeading = 0.0
    @Volatile private var maxCurrent = 0.0
    @Volatile private var sampleCount = 0L

    /** Completes exactly one runner-owned simulator frame. */
    internal fun paceFrame(sleep: (Long) -> Unit = Thread::sleep) {
        if (RobotClock.isMocked) {
            RobotClock.useMockTime(RobotClock.currentTimeMillis() + SIM_TIMESTEP_MS)
        }
        sleep(SIM_TIMESTEP_MS)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val interactionModel = com.areslib.sim.field.DescriptorSimInteractionLoader
                .loadFrom(java.nio.file.Path.of(System.getProperty("user.dir")))
                ?: MecanumInteractionModel()
            launch(args, interactionModel)
        } catch (t: Throwable) {
            System.err.println("FATAL CRASH IN SIMULATOR:")
            t.printStackTrace()
            System.err.flush()
            throw t
        }
    }

    @Volatile
    var isSimRunning = true

    fun launch(
        args: Array<String>,
        interactionModel: SimInteractionModel,
        opModeArg: Any? = null
    ) {
        isSimRunning = true
        println("Starting ARESLib Desktop Simulation...")
        RobotClock.useMockTime(0L)
        com.areslib.telemetry.SimInputBridge.reset()
        com.areslib.simulation.SimAppliedOutputRegistry.reset()
        interactionModel.reset()

        sumSqErrorX = 0.0
        sumSqErrorY = 0.0
        sumSqErrorHeading = 0.0
        maxCurrent = 0.0
        sampleCount = 0L

        Runtime.getRuntime().addShutdownHook(Thread {
            RobotClock.useSystemTime()
            val count = sampleCount
            if (count > 0) {
                val rmseX = kotlin.math.sqrt(sumSqErrorX / count)
                val rmseY = kotlin.math.sqrt(sumSqErrorY / count)
                val rmseHeading = kotlin.math.sqrt(sumSqErrorHeading / count)
                val summaryJson = String.format(
                    "{\n  \"rmseX\": %.5f,\n  \"rmseY\": %.5f,\n  \"rmseHeading\": %.5f,\n  \"maxCurrentAmps\": %.3f,\n  \"sampleCount\": %d\n}",
                    rmseX, rmseY, rmseHeading, maxCurrent, count
                )
                try {
                    val summaryFile = File(
                        System.getProperty(SUMMARY_OUTPUT_PROPERTY, "ares_run_summary.json")
                    )
                    summaryFile.writeText(summaryJson)
                    println("[Simulator] Wrote run summary to ${summaryFile.absolutePath}")
                } catch (e: Exception) {
                    System.err.println("Failed to write simulation run summary: ${e.message}")
                }
            }
        })

        // 1. CLI Parsing
        val cliArgs = SimCliParser.parseArgs(args)
        val activeConfig = SimCliParser.loadFieldConfig(cliArgs.fieldConfigArg)
        // 2. Telemetry & Web Server Initialization
        println("Initializing Telemetry (NT4 & DataLog)...")
        try {
            if (com.areslib.networktables.NT4Instance.defaultInstance.defaultServer == null) {
                com.areslib.networktables.NT4Instance.defaultInstance.startServer("0.0.0.0", 5810)
                println("[Simulator] NT4 Server started on port 5810 for ARES-Analytics")
            }
        } catch (e: Exception) {
            println("[Simulator] Warning starting NT4 Server: ${e.message}")
        }
        TelemetryPublisher.javaClass
        // Wire up ARESNetworkStatePublisher so telemetry (loop times, odometry,
        // vision, indicator lights, etc.) is published to connected clients.
        val nt4Telemetry = com.areslib.telemetry.NT4Telemetry()
        val networkStatePublisher = com.areslib.telemetry.ARESNetworkStatePublisher(nt4Telemetry)
        TelemetryPublisher.init(nt4Telemetry, networkStatePublisher)
        com.areslib.logging.LogManagerServer.startServer()

        val serverMode = opModeArg == null && cliArgs.opModeClassName == null
        val driverStation = SimGamepadManager()
        val driverStationWindow = if (!cliArgs.headless && !serverMode) {
            VirtualDriverStation(driverStation) { isSimRunning = false }.also { it.isVisible = true }
        } else {
            null
        }

        var lifecycleStop: (() -> Unit)? = null
        try {

        if (serverMode) {
            println("[Simulator] Running in Driver Station Server Mode")
            SimOpModeRunner.scanAndPublishOpModes()
            publishActiveOpModeIdentity(null)
        }

        // 3. Dyn4j Physics World Initialization
        val physicsWorld = SimPhysicsWorld()
        physicsWorld.setupSpawnPose(driverStation.effectiveIsRedAlliance)
        physicsWorld.loadFieldElements(activeConfig)

        // 4. Mecanum Robot Double & OpMode Execution
        val robotDouble = MecanumRobotDouble()
        physicsWorld.loadedFieldConfig?.let(robotDouble::configureField)
        val primeRobotSensorsFromPhysics = {
            val robotInstance = com.areslib.ftc.FtcBaseRobot.activeInstance
            val isPinpointCcwPositive = robotInstance?.pinpointIsCcwPositive ?: true
            robotDouble.updateSensors(
                dt = SIM_TIMESTEP_SECONDS,
                actualVx = 0.0,
                actualVy = 0.0,
                actualOmega = 0.0,
                trueX = physicsWorld.robotBody.transform.translationX,
                trueY = physicsWorld.robotBody.transform.translationY,
                trueHeadingRad = physicsWorld.robotBody.transform.rotationAngle,
                isPinpointCcwPositive = isPinpointCcwPositive,
            )
            Unit
        }
        val synchronizeDriverStationState = {
            com.areslib.ftc.FtcBaseRobot.activeInstance?.let { robotInstance ->
                val alliance = if (driverStation.effectiveIsRedAlliance) {
                    com.areslib.state.Alliance.RED
                } else {
                    com.areslib.state.Alliance.BLUE
                }
                if (robotInstance.store.state.drive.alliance != alliance) {
                    robotInstance.store.dispatch(RobotAction.SetAlliance(alliance))
                }
                (robotInstance as? FtcMecanumRobot)?.let { mecanumRobot ->
                    mecanumRobot.teleopDriveFrame = if (driverStation.effectiveIsFieldCentric) {
                        FtcTeleopDriveFrame.FIELD_RELATIVE
                    } else {
                        FtcTeleopDriveFrame.ROBOT_RELATIVE
                    }
                    driverStation.recordAppliedDriveFrame(
                        mecanumRobot.teleopDriveFrame == FtcTeleopDriveFrame.FIELD_RELATIVE
                    )
                }
            }
            Unit
        }
        val syncRobotPoseToPhysics = { lifecycle: SimOpModeLifecycle ->
            com.areslib.ftc.FtcBaseRobot.activeInstance?.let { robotInstance ->
                val physicsPose = Pose2d(
                    physicsWorld.robotBody.transform.translationX,
                    physicsWorld.robotBody.transform.translationY,
                    Rotation2d(physicsWorld.robotBody.transform.rotationAngle),
                )
                val opModePose = robotInstance.store.state.drive.poseEstimator.estimatedPose
                val synchronizedPose = synchronizeSimulatorStartPose(
                    modeKind = lifecycle.modeKind,
                    opModePose = opModePose,
                    physicsPose = physicsPose,
                    applyPhysicsPose = { pose ->
                        physicsWorld.robotBody.transform.setTranslation(pose.x, pose.y)
                        physicsWorld.robotBody.transform.setRotation(pose.heading.radians)
                        physicsWorld.robotBody.linearVelocity = org.dyn4j.geometry.Vector2(0.0, 0.0)
                        physicsWorld.robotBody.angularVelocity = 0.0
                    },
                    initializePinpoint = { pose ->
                        robotInstance.pinpointIO?.initialize(pose, resetHardware = true)
                    },
                    resetReduxPose = { pose ->
                        robotInstance.store.dispatch(
                            RobotAction.PoseUpdate(
                                xMeters = pose.x,
                                yMeters = pose.y,
                                headingRadians = pose.heading.radians,
                                timestampMs = RobotClock.currentTimeMillis(),
                                isReset = true,
                            )
                        )
                    },
                )
                println("[Simulator] Synchronized ${lifecycle.modeKind} start pose: $synchronizedPose")
            }
            Unit
        }

        val opModeSlot = SimOpModeLifecycleSlot(
            SimOpModeRunner.createOpModeInstance(opModeArg, cliArgs.opModeClassName)
        )

        val stopActiveOpMode = {
            opModeSlot.stopActive()
            Unit
        }
        lifecycleStop = {
            opModeSlot.stopActiveForShutdown()
        }

        opModeSlot.activeMode?.let { initialMode ->
            publishActiveOpModeIdentity(initialMode)
            driverStation.resetInjectionState()
            initialMode.initialize(robotDouble.hardwareMap)
            // The Pinpoint mock captures its native origin during robot construction. Prime it
            // with the alliance spawn before the first OpMode observation and explicit pose reset;
            // otherwise a non-zero spawn becomes a permanent truth/odometry offset.
            primeRobotSensorsFromPhysics()

            // Give INIT one effective alliance/frame sample before choosing pose ownership. An
            // autonomous may intentionally seed (0, 0, 0), which is data rather than a sentinel.
            driverStation.writeEffectiveGamepads(initialMode.gamepad1, initialMode.gamepad2)
            synchronizeDriverStationState()
            initialMode.tick()
            syncRobotPoseToPhysics(initialMode)

            val initStartTime = RobotClock.currentTimeMillis()
            while (RobotClock.currentTimeMillis() - initStartTime < 500) {
                driverStation.writeEffectiveGamepads(initialMode.gamepad1, initialMode.gamepad2)
                synchronizeDriverStationState()
                initialMode.tick()
                val ccwPos = com.areslib.ftc.FtcBaseRobot.activeInstance?.pinpointIsCcwPositive ?: true
                robotDouble.updateSensors(
                    SIM_TIMESTEP_SECONDS,
                    0.0,
                    0.0,
                    0.0,
                    physicsWorld.robotBody.transform.translationX,
                    physicsWorld.robotBody.transform.translationY,
                    physicsWorld.robotBody.transform.rotationAngle,
                    ccwPos,
                )
                paceFrame()
            }

            println("[Simulator] Driver clicked PLAY! Activating telemetry & drivetrain controls.")
            synchronizeDriverStationState()
            syncRobotPoseToPhysics(initialMode)
            initialMode.start()
        }

        println("Simulation Running at 50Hz. Press Ctrl+C to stop.")

        val ntInst = NT4Instance.defaultInstance
        var lastDsCommand = ""
        var lastSelectedOpMode = ""
        var inventoryCount = 0
        var gamePieceTelemetryBuffer = DoubleArray(SimGamePieceTelemetryFrame.requiredSize(0))
        val periodicTraceEnabled = java.lang.Boolean.getBoolean("ares.sim.trace")

        while (isSimRunning) {
          try {
            TelemetryPublisher.pollWebInputs(driverStation)?.let { obstaclesJson ->
                physicsWorld.replaceObstaclesFromAnalyticsJson(obstaclesJson)
            }
            TelemetryPublisher.pollWebFieldConfig()?.let { fieldConfigJson ->
                if (physicsWorld.replaceFieldDocumentJson(fieldConfigJson)) {
                    physicsWorld.loadedFieldConfig?.let(robotDouble::configureField)
                }
            }
            // Check for Driver Station UI commands from ARES-Analytics dashboard or in-process NT4Server
            val dsCommand = NT4Server.getString("ARES/DriverStation/Command", "").trim()
            val selectedOpMode = NT4Server.getString("ARES/DriverStation/SelectedOpMode", "").trim()

            if (selectedOpMode.isNotEmpty() && selectedOpMode != lastSelectedOpMode) {
                lastSelectedOpMode = selectedOpMode
                println("[Simulator] Driver Station selected OpMode: $selectedOpMode")
            }

            if (dsCommand.isNotEmpty() && dsCommand != lastDsCommand) {
                lastDsCommand = dsCommand
                println("[Simulator] Driver Station command received: $dsCommand")
                when (dsCommand) {
                    "INIT" -> {
                        var candidate: com.areslib.sim.opmode.SimOpModeLifecycle? = null
                        try {
                            stopActiveOpMode()
                            interactionModel.reset()
                            val targetOpMode = if (selectedOpMode.isNotEmpty()) selectedOpMode else lastSelectedOpMode
                            val newOpMode = SimOpModeRunner.createOpModeInstance(null, targetOpMode)
                                ?: throw IllegalArgumentException("Unknown or unsupported OpMode: '$targetOpMode'")
                            candidate = newOpMode
                            physicsWorld.setupSpawnPose(driverStation.effectiveIsRedAlliance)
                            driverStation.resetInjectionState()
                            newOpMode.initialize(robotDouble.hardwareMap)
                            primeRobotSensorsFromPhysics()
                            driverStation.writeEffectiveGamepads(newOpMode.gamepad1, newOpMode.gamepad2)
                            synchronizeDriverStationState()
                            // The final INIT callback synchronizes an unlocked auto selector and
                            // constructs its alliance-specific runtime before pose ownership runs.
                            newOpMode.tick()
                            syncRobotPoseToPhysics(newOpMode)
                            opModeSlot.install(newOpMode)
                            publishActiveOpModeIdentity(newOpMode)
                            println("[Simulator] Successfully INITED OpMode: ${newOpMode.displayName} (Alliance=${if (driverStation.effectiveIsRedAlliance) "RED" else "BLUE"})")
                        } catch (e: Exception) {
                            try {
                                opModeSlot.stopCandidate(candidate)
                            } catch (cleanupFailure: Throwable) {
                                if (cleanupFailure !== e) e.addSuppressed(cleanupFailure)
                            }
                            System.err.println("[Simulator] Failed to INIT OpMode: ${e.message}")
                            publishActiveOpModeIdentity(null)
                            if (opModeSlot.isTerminal) throw e
                        }
                    }
                    "START" -> {
                        opModeSlot.activeMode?.let { mode ->
                            synchronizeDriverStationState()
                            // Accept a drive-frame alliance received on the START frame before the
                            // autonomous freezes its selector for the run.
                            if (!mode.isStarted) mode.tick()
                            syncRobotPoseToPhysics(mode)
                            mode.start()
                            println("[Simulator] OpMode STARTED.")
                        }
                    }
                    "STOP" -> {
                        stopActiveOpMode()
                        publishActiveOpModeIdentity(null)
                        driverStation.resetInjectionState()
                        robotDouble.fl.power = 0.0
                        robotDouble.fr.power = 0.0
                        robotDouble.rl.power = 0.0
                        robotDouble.rr.power = 0.0
                        println("[Simulator] OpMode STOPPED.")
                    }
                }
            }

            opModeSlot.activeMode?.let { mode ->
                driverStation.writeEffectiveGamepads(mode.gamepad1, mode.gamepad2)
                synchronizeDriverStationState()
                // Linear OpModes run on their SDK-style worker thread. Iterative OpModes receive
                // exactly one INIT_LOOP or LOOP callback on each 50 Hz simulator frame.
                mode.tick()
            }

            val ccwPos = com.areslib.ftc.FtcBaseRobot.activeInstance?.pinpointIsCcwPositive ?: true
            val currentPhysX = physicsWorld.robotBody.transform.translationX
            val currentPhysY = physicsWorld.robotBody.transform.translationY
            val currentPhysHeading = physicsWorld.robotBody.transform.rotationAngle

            // Drive Dyn4j physics body from simulated motor powers
            val flP = robotDouble.fl.power
            val frP = robotDouble.fr.power
            val rlP = robotDouble.rl.power
            val rrP = robotDouble.rr.power
            val isNoInput = kotlin.math.abs(flP) < 1e-3 && kotlin.math.abs(frP) < 1e-3 && 
                            kotlin.math.abs(rlP) < 1e-3 && kotlin.math.abs(rrP) < 1e-3

            if (isNoInput) {
                physicsWorld.robotBody.linearVelocity = org.dyn4j.geometry.Vector2(0.0, 0.0)
                physicsWorld.robotBody.angularVelocity = 0.0
            } else {
                val rawVx = (flP + frP + rlP + rrP) / 4.0 * 2.6
                val rawVy = (-flP + frP + rlP - rrP) / 4.0 * 2.6
                val rawOmega = (-flP + frP - rlP + rrP) / 4.0 * 3.5

                if (periodicTraceEnabled && sampleCount % 250L == 0L) {
                    println(
                        ("[SimPhysics] flP=%.2f, frP=%.2f, rlP=%.2f, rrP=%.2f, " +
                            "rawVx=%.2f, rawVy=%.2f, rawOmega=%.2f, physY=%.3f").format(
                                flP, frP, rlP, rrP, rawVx, rawVy, rawOmega, currentPhysY,
                            )
                    )
                }

                val heading = currentPhysHeading
                val cosH = kotlin.math.cos(heading)
                val sinH = kotlin.math.sin(heading)

                val fieldVx = rawVx * cosH - rawVy * sinH
                val fieldVy = rawVx * sinH + rawVy * cosH

                physicsWorld.robotBody.setAtRest(false)
                physicsWorld.robotBody.linearVelocity = org.dyn4j.geometry.Vector2(fieldVx, fieldVy)
                physicsWorld.robotBody.angularVelocity = rawOmega
            }

            physicsWorld.world.step(1, SIM_TIMESTEP_SECONDS)

            val fieldVx = physicsWorld.robotBody.linearVelocity.x
            val fieldVy = physicsWorld.robotBody.linearVelocity.y
            val omega = physicsWorld.robotBody.angularVelocity
            val postStepX = physicsWorld.robotBody.transform.translationX
            val postStepY = physicsWorld.robotBody.transform.translationY
            val postStepHeading = physicsWorld.robotBody.transform.rotationAngle

            // Transform field-frame velocities back to robot-frame velocities for encoder simulation
            val heading = postStepHeading
            val cosH = kotlin.math.cos(heading)
            val sinH = kotlin.math.sin(heading)
            val robotVx = fieldVx * cosH + fieldVy * sinH
            val robotVy = -fieldVx * sinH + fieldVy * cosH

            robotDouble.updateSensors(SIM_TIMESTEP_SECONDS, robotVx, robotVy, omega, postStepX, postStepY, postStepHeading, ccwPos)

            val appliedOutputs = com.areslib.ftc.FtcBaseRobot.activeInstance?.simMechanismOutputProvider
            val intakeApplied: Boolean
            val flywheelApplied: Boolean
            val transferApplied: Boolean
            try {
                intakeApplied = appliedOutputs?.intakeApplied == true
                flywheelApplied = appliedOutputs?.flywheelApplied == true
                transferApplied = appliedOutputs?.transferApplied == true
            } catch (failure: Throwable) {
                throw IllegalStateException("Season mechanism output snapshot failed", failure)
            }
            val acceptedOutputs = appliedOutputs as? com.areslib.ftc.sim.FtcSimMechanismStateProvider
            driverStation.observeAcceptedMechanismState(
                intakeAccepted = acceptedOutputs?.intakeAccepted ?: intakeApplied,
                flywheelAccepted = acceptedOutputs?.flywheelAccepted ?: flywheelApplied,
            )
            inventoryCount = interactionModel.update(
                world = physicsWorld.world,
                robotBody = physicsWorld.robotBody,
                gamePieces = physicsWorld.gamePieces,
                intakeApplied = intakeApplied,
                flywheelApplied = flywheelApplied,
                transferApplied = transferApplied,
                currentInventoryCount = inventoryCount,
                robotHeading = postStepHeading,
                robotX = postStepX,
                robotY = postStepY
            )
            NT4Server.publishTopic("Superstructure/SimInventoryCount", inventoryCount.toLong())

            // Stream dynamic game piece positions to NT4 for live visual rendering
            val pieces = physicsWorld.gamePieces
            val requiredGamePieceDoubles = SimGamePieceTelemetryFrame.requiredSize(pieces.size)
            if (gamePieceTelemetryBuffer.size != requiredGamePieceDoubles) {
                // Population changes are infrequent; reuse the exact-sized buffer on every stable
                // 50 Hz frame. The atomic frame retains a header and sequence even at zero count.
                gamePieceTelemetryBuffer = DoubleArray(requiredGamePieceDoubles)
            }

            if (pieces.isNotEmpty()) {
                for (i in pieces.indices) {
                    SimGamePieceTelemetryFrame.writeBody(gamePieceTelemetryBuffer, i, pieces[i])
                }
            }
            TelemetryPublisher.publishGamePieceFrame(gamePieceTelemetryBuffer, pieces.size)

            // The OpMode tick above consumed the sensor sample written after the previous physics
            // step. Publish that same observation pose alongside the Redux odometry/EKF state.
            // Publishing the newly advanced physics pose here would make truth one frame newer than
            // both estimators and create a visible, artificial trailing overlay while moving.
            TelemetryPublisher.publishTruePose(currentPhysX, currentPhysY, currentPhysHeading)

            // Extract OpMode display lines from MockTelemetry and publish to NT4 for ARES-Analytics
            val mockTelemetry = opModeSlot.activeMode?.telemetry as? org.firstinspires.ftc.robotcore.external.MockTelemetry
            val displayLines = mockTelemetry?.displayLines ?: emptyList()
            for (i in displayLines.indices) {
                NT4Server.publishTopic("ARES/DriverStation/Telemetry/$i", displayLines[i])
            }

            val activeInstance = com.areslib.ftc.FtcBaseRobot.activeInstance
            if (activeInstance != null) {
                val state = activeInstance.store.state
                TelemetryPublisher.publish(
                    state = state,
                    hardwareRegistry = activeInstance.hardwareRegistry,
                    dtSeconds = SIM_TIMESTEP_SECONDS,
                )
                TelemetryPublisher.publishSimulatorPoseFrame(
                    trueX = currentPhysX,
                    trueY = currentPhysY,
                    trueHeading = currentPhysHeading,
                    state = state,
                )
                activeInstance.profiler.publishSensorsProfiling(activeInstance.telemetryManager)
                
                if (periodicTraceEnabled && sampleCount % 250L == 0L) {
                    println("[SimTelemetry] observedTruthY=%.3f".format(currentPhysY))
                }
                // TelemetryPublisher.publish(state) is the sole owner of Drive/Pose_* and
                // ARES/EstimatedPose/*. Do not overwrite the real Redux EKF with Dyn4j truth.
                TelemetryPublisher.publishTargetPose(postStepX, postStepY, postStepHeading)
            }

            // Always publish the canonical FTC motor names on every 50 Hz physics tick. `rl`/`rr`
            // are the hardware-map contract; duplicate `bl`/`br` aliases would add hot-path work
            // and cannot provide an atomic cross-topic snapshot to independent readers.
            val flPower = robotDouble.fl.power
            val frPower = robotDouble.fr.power
            val rlPower = robotDouble.rl.power
            val rrPower = robotDouble.rr.power
            val flVelocity = robotDouble.fl.velocity
            val frVelocity = robotDouble.fr.velocity
            val rlVelocity = robotDouble.rl.velocity
            val rrVelocity = robotDouble.rr.velocity
            NT4Server.publishTopic("Hardware/Motors/fl/Power", flPower)
            NT4Server.publishTopic("Hardware/Motors/fr/Power", frPower)
            NT4Server.publishTopic("Hardware/Motors/rl/Power", rlPower)
            NT4Server.publishTopic("Hardware/Motors/rr/Power", rrPower)
            NT4Server.publishTopic("Hardware/Motors/fl/Velocity", flVelocity)
            NT4Server.publishTopic("Hardware/Motors/fr/Velocity", frVelocity)
            NT4Server.publishTopic("Hardware/Motors/rl/Velocity", rlVelocity)
            NT4Server.publishTopic("Hardware/Motors/rr/Velocity", rrVelocity)

            val flCurrent = robotDouble.fl.getCurrent(org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit.AMPS)
            val frCurrent = robotDouble.fr.getCurrent(org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit.AMPS)
            val rlCurrent = robotDouble.rl.getCurrent(org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit.AMPS)
            val rrCurrent = robotDouble.rr.getCurrent(org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit.AMPS)
            NT4Server.publishTopic("Hardware/Motors/fl/CurrentAmps", flCurrent)
            NT4Server.publishTopic("Hardware/Motors/fr/CurrentAmps", frCurrent)
            NT4Server.publishTopic("Hardware/Motors/rl/CurrentAmps", rlCurrent)
            NT4Server.publishTopic("Hardware/Motors/rr/CurrentAmps", rrCurrent)
            TelemetryPublisher.publishMecanumMotorFrame(
                flPower = flPower,
                frPower = frPower,
                rlPower = rlPower,
                rrPower = rrPower,
                flVelocity = flVelocity,
                frVelocity = frVelocity,
                rlVelocity = rlVelocity,
                rrVelocity = rrVelocity,
                flCurrentAmps = flCurrent,
                frCurrentAmps = frCurrent,
                rlCurrentAmps = rlCurrent,
                rrCurrentAmps = rrCurrent,
            )

            val activeState = opModeSlot.activeMode?.publishedState ?: SimOpModeState.DISABLED
            // MatchState is dashboard-owned match sequencing (AUTO_INIT/TRANSITION/etc.). The
            // simulator publishes its observed robot lifecycle on a distinct topic so neither
            // publisher can overwrite the other's state machine.
            NT4Server.publishTopic(ACTIVE_OP_MODE_STATE_TOPIC, activeState.name)
            TelemetryPublisher.publishDriveMode(
                fieldCentric = driverStation.appliedIsFieldCentric,
                teleopMode = opModeSlot.activeMode?.modeKind == SimOpModeKind.TELEOP && opModeSlot.activeMode?.isStarted == true,
                redAlliance = driverStation.effectiveIsRedAlliance,
            )

            sampleCount++

            // Always flush NT4 updates to clients on every loop frame (50Hz)
            ntInst.defaultServer?.flush()

            try {
                paceFrame()
            } catch (_: InterruptedException) {
                break
            }
          } catch (e: Exception) {
              System.err.println("[Simulator] CRASH in main loop iteration $sampleCount:")
              e.printStackTrace()
              throw e
              // Continue running — one bad frame shouldn't kill the sim
          }
        }
        } finally {
            try {
                lifecycleStop?.invoke()
            } catch (error: Throwable) {
                System.err.println("[Simulator] Failed to stop OpMode during shutdown: ${error.message}")
            }
            try {
                driverStationWindow?.dispose()
            } catch (_: Throwable) {
            }
            try {
                driverStation.close()
            } catch (_: Throwable) {
            }
            try {
                com.areslib.logging.LogManagerServer.stop()
            } catch (_: Throwable) {
            }
            try {
                TelemetryPublisher.stop()
            } catch (_: Throwable) {
            }
            try {
                com.areslib.networktables.NT4Instance.defaultInstance.closeServer()
            } catch (_: Throwable) {
            }
            com.areslib.telemetry.SimInputBridge.reset()
            isSimRunning = false
            RobotClock.useSystemTime()
        }
    }

    /** Publishes an explicit lifecycle acknowledgement so a dashboard cannot infer it from a command echo. */
    private fun publishActiveOpModeIdentity(mode: SimOpModeLifecycle?) {
        NT4Server.publishTopic(ACTIVE_OP_MODE_CLASS_TOPIC, mode?.rawOpMode?.javaClass?.name.orEmpty())
        NT4Server.publishTopic(ACTIVE_OP_MODE_DISPLAY_NAME_TOPIC, mode?.displayName.orEmpty())
    }
}

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
