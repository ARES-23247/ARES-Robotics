package com.areslib.sim

import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.sim.cli.SimCliParser
import com.areslib.sim.field.FieldElementLoader
import com.areslib.sim.field.MecanumInteractionModel
import com.areslib.sim.infra.VirtualDriverStation
import com.areslib.sim.model.MecanumRobotDouble
import com.areslib.sim.network.NT4FieldPublisher
import com.areslib.sim.network.TelemetryPublisher
import com.areslib.sim.opmode.SimOpModeRunner
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
    private const val TIMESTEP_SEC = 0.01


    @Volatile private var sumSqErrorX = 0.0
    @Volatile private var sumSqErrorY = 0.0
    @Volatile private var sumSqErrorHeading = 0.0
    @Volatile private var maxCurrent = 0.0
    @Volatile private var sampleCount = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            launch(args, MecanumInteractionModel())
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
        opModeArg: com.qualcomm.robotcore.eventloop.opmode.LinearOpMode? = null
    ) {
        isSimRunning = true
        println("Starting ARESLib Desktop Simulation...")
        RobotClock.useMockTime(0L)

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
                    val summaryFile = File("ares_run_summary.json")
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
        val driverStation = VirtualDriverStation()
        if (!cliArgs.headless && !serverMode) {
            driverStation.isVisible = true
        }

        if (serverMode) {
            println("[Simulator] Running in Driver Station Server Mode")
            SimOpModeRunner.scanAndPublishOpModes()
        }

        // 3. Dyn4j Physics World Initialization
        val physicsWorld = SimPhysicsWorld()
        val startPose = physicsWorld.setupSpawnPose(driverStation.isRedAlliance)
        physicsWorld.loadFieldElements(activeConfig)

        // 4. Mecanum Robot Double & OpMode Execution
        val robotDouble = MecanumRobotDouble()
        val syncRobotPoseToPhysics = {
            com.areslib.ftc.FtcBaseRobot.activeInstance?.let { robotInstance ->
                val allianceEnum = if (driverStation.isRedAlliance) com.areslib.state.Alliance.RED else com.areslib.state.Alliance.BLUE
                robotInstance.store.dispatch(com.areslib.action.RobotAction.SetAlliance(allianceEnum))

                val ekfPose = robotInstance.store.state.drive.poseEstimator.estimatedPose
                val now = RobotClock.currentTimeMillis()
                // Only sync physics body from OpMode if OpMode explicitly set a custom non-zero X/Y position (e.g. from Auto starting waypoint)
                if (kotlin.math.abs(ekfPose.x) > 0.01 || kotlin.math.abs(ekfPose.y) > 0.01) {
                    physicsWorld.robotBody.transform.setTranslation(ekfPose.x, ekfPose.y)
                    physicsWorld.robotBody.transform.setRotation(ekfPose.heading.radians)
                    physicsWorld.robotBody.linearVelocity = org.dyn4j.geometry.Vector2(0.0, 0.0)
                    physicsWorld.robotBody.angularVelocity = 0.0
                    robotInstance.pinpointIO?.initialize(ekfPose, resetHardware = true)
                    println("[Simulator] Synced physics body to OpMode starting pose: $ekfPose")
                } else {
                    val currentPhysPose = Pose2d(
                        physicsWorld.robotBody.transform.translationX,
                        physicsWorld.robotBody.transform.translationY,
                        Rotation2d(physicsWorld.robotBody.transform.rotationAngle)
                    )
                    robotInstance.pinpointIO?.initialize(
                        Pose2d(currentPhysPose.x, currentPhysPose.y, Rotation2d(currentPhysPose.heading.radians)),
                        resetHardware = true
                    )
                    robotInstance.store.dispatch(
                        com.areslib.action.RobotAction.PoseUpdate(
                            xMeters = currentPhysPose.x,
                            yMeters = currentPhysPose.y,
                            headingRadians = currentPhysPose.heading.radians,
                            timestampMs = now,
                            isReset = true
                        )
                    )
                    println("[Simulator] Automatically synced robot EKF and Pinpoint to physics starting pose: $currentPhysPose")
                }
            }
            Unit
        }

        var activeOpMode = SimOpModeRunner.createOpModeInstance(opModeArg, cliArgs.opModeClassName)

        val startOpMode: (com.qualcomm.robotcore.eventloop.opmode.LinearOpMode) -> Thread = { opModeToRun ->
            opModeToRun.hardwareMap = robotDouble.hardwareMap
            Thread {
                try {
                    opModeToRun.runOpMode()
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    System.err.println("OpMode Thread terminated: ${e.message}")
                    e.printStackTrace()
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
        var activeOpModeThread: Thread? = null

        val stopActiveOpMode = {
            activeOpMode?.isStopRequested = true
            activeOpModeThread?.let { thread ->
                thread.join(500)
                if (thread.isAlive) {
                    thread.interrupt()
                    thread.join(500)
                }
                check(!thread.isAlive) { "Previous OpMode did not terminate after stop request" }
            }
            activeOpModeThread = null
        }

        if (activeOpMode != null) {
            activeOpModeThread = startOpMode(activeOpMode)

            val initStartTime = RobotClock.currentTimeMillis()
            while (RobotClock.currentTimeMillis() - initStartTime < 500) {
                val ccwPos = com.areslib.ftc.FtcBaseRobot.activeInstance?.pinpointIsCcwPositive ?: true
                robotDouble.updateSensors(TIMESTEP_SEC, 0.0, 0.0, 0.0, startPose.x, startPose.y, startPose.heading.radians, ccwPos)
                if (RobotClock.isMocked) {
                    RobotClock.useMockTime(RobotClock.currentTimeMillis() + 20)
                }
                Thread.sleep(20)
            }

            println("[Simulator] Driver clicked PLAY! Activating telemetry & drivetrain controls.")
            syncRobotPoseToPhysics()
            activeOpMode.isStarted = true
        }

        println("Simulation Running at 50Hz. Press Ctrl+C to stop.")

        val ntInst = NT4Instance.defaultInstance
        var lastDsCommand = ""
        var lastSelectedOpMode = ""
        var inventoryCount = 0
        var gamePieceTelemetryBuffer = DoubleArray(0)

        while (isSimRunning) {
          try {
            TelemetryPublisher.pollWebInputs(driverStation)?.let { obstaclesJson ->
                physicsWorld.replaceObstaclesFromAnalyticsJson(obstaclesJson)
            }
            TelemetryPublisher.pollWebFieldConfig()?.let { fieldConfigJson ->
                physicsWorld.replaceFieldDocumentJson(fieldConfigJson)
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
                        try {
                            stopActiveOpMode()
                            val targetOpMode = if (selectedOpMode.isNotEmpty()) selectedOpMode else lastSelectedOpMode
                            val newOpMode = SimOpModeRunner.createOpModeInstance(null, targetOpMode)
                                ?: com.areslib.ftc.hardware.AresHardwareTestOpMode()
                            activeOpMode = newOpMode
                            physicsWorld.setupSpawnPose(driverStation.isRedAlliance)
                            activeOpModeThread = startOpMode(newOpMode)
                            Thread.sleep(150)
                            syncRobotPoseToPhysics()
                            println("[Simulator] Successfully INITED OpMode: ${newOpMode.javaClass.simpleName} (Alliance=${if (driverStation.isRedAlliance) "RED" else "BLUE"})")
                        } catch (e: Exception) {
                            System.err.println("[Simulator] Failed to INIT OpMode: ${e.message}")
                        }
                    }
                    "START" -> {
                        activeOpMode?.let { mode ->
                            syncRobotPoseToPhysics()
                            mode.isStarted = true
                            println("[Simulator] OpMode STARTED.")
                        }
                    }
                    "STOP" -> {
                        stopActiveOpMode()
                        robotDouble.fl.power = 0.0
                        robotDouble.fr.power = 0.0
                        robotDouble.rl.power = 0.0
                        robotDouble.rr.power = 0.0
                        println("[Simulator] OpMode STOPPED.")
                    }
                }
            }

            val ccwPos = com.areslib.ftc.FtcBaseRobot.activeInstance?.pinpointIsCcwPositive ?: true
            val currentPhysPose = Pose2d(
                physicsWorld.robotBody.transform.translationX,
                physicsWorld.robotBody.transform.translationY,
                Rotation2d(physicsWorld.robotBody.transform.rotationAngle)
            )

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

                if (sampleCount % 250L == 0L) {
                    println("[SimPhysics] flP=%.2f, frP=%.2f, rawVx=%.2f, rawVy=%.2f, physY=%.3f".format(flP, frP, rawVx, rawVy, currentPhysPose.y))
                }

                val heading = currentPhysPose.heading.radians
                val cosH = kotlin.math.cos(heading)
                val sinH = kotlin.math.sin(heading)

                val fieldVx = rawVx * cosH - rawVy * sinH
                val fieldVy = rawVx * sinH + rawVy * cosH

                physicsWorld.robotBody.setAtRest(false)
                physicsWorld.robotBody.linearVelocity = org.dyn4j.geometry.Vector2(fieldVx, fieldVy)
                physicsWorld.robotBody.angularVelocity = rawOmega
            }

            physicsWorld.world.step(1)

            val fieldVx = physicsWorld.robotBody.linearVelocity.x
            val fieldVy = physicsWorld.robotBody.linearVelocity.y
            val omega = physicsWorld.robotBody.angularVelocity
            val postStepPose = Pose2d(
                physicsWorld.robotBody.transform.translationX,
                physicsWorld.robotBody.transform.translationY,
                Rotation2d(physicsWorld.robotBody.transform.rotationAngle)
            )

            // Transform field-frame velocities back to robot-frame velocities for encoder simulation
            val heading = postStepPose.heading.radians
            val cosH = kotlin.math.cos(heading)
            val sinH = kotlin.math.sin(heading)
            val robotVx = fieldVx * cosH + fieldVy * sinH
            val robotVy = -fieldVx * sinH + fieldVy * cosH

            robotDouble.updateSensors(TIMESTEP_SEC, robotVx, robotVy, omega, postStepPose.x, postStepPose.y, postStepPose.heading.radians, ccwPos)

            inventoryCount = interactionModel.update(
                world = physicsWorld.world,
                robotBody = physicsWorld.robotBody,
                gamePieces = physicsWorld.gamePieces,
                driverStation = driverStation,
                currentInventoryCount = inventoryCount,
                robotHeading = postStepPose.heading.radians,
                robotX = postStepPose.x,
                robotY = postStepPose.y
            )
            NT4Server.publishTopic("Superstructure/SimInventoryCount", inventoryCount.toLong())

            // Stream dynamic game piece positions to NT4 for live visual rendering
            val pieces = physicsWorld.gamePieces
            val requiredGamePieceDoubles = pieces.size * TelemetryPublisher.GAME_PIECE_RECORD_WIDTH
            if (gamePieceTelemetryBuffer.size != requiredGamePieceDoubles) {
                // Population changes are infrequent; reuse the exact-sized buffer on every stable
                // 50 Hz frame and the singleton empty array after the final removal.
                gamePieceTelemetryBuffer = if (requiredGamePieceDoubles == 0) {
                    DoubleArray(0)
                } else {
                    DoubleArray(requiredGamePieceDoubles)
                }
            }
            if (pieces.isNotEmpty()) {
                for (i in pieces.indices) {
                    val p = pieces[i]
                    val base = i * TelemetryPublisher.GAME_PIECE_RECORD_WIDTH
                    gamePieceTelemetryBuffer[base + 0] = p.transform.translationX
                    gamePieceTelemetryBuffer[base + 1] = p.transform.translationY
                    gamePieceTelemetryBuffer[base + 2] = p.transform.rotationAngle
                    gamePieceTelemetryBuffer[base + 3] = 0.15
                    gamePieceTelemetryBuffer[base + 4] = 0.15
                    gamePieceTelemetryBuffer[base + 5] = 0.0
                    gamePieceTelemetryBuffer[base + 6] = 0.0
                }
            }
            TelemetryPublisher.publishGamePieces(gamePieceTelemetryBuffer, pieces.size)

            TelemetryPublisher.publishTruePose(currentPhysPose)

            // Extract OpMode display lines from MockTelemetry and publish to NT4 for ARES-Analytics
            val mockTelemetry = activeOpMode?.telemetry as? org.firstinspires.ftc.robotcore.external.MockTelemetry
            val displayLines = mockTelemetry?.displayLines ?: emptyList()
            for (i in displayLines.indices) {
                NT4Server.publishTopic("ARES/DriverStation/Telemetry/$i", displayLines[i])
            }

            val activeInstance = com.areslib.ftc.FtcBaseRobot.activeInstance
            if (activeInstance != null) {
                val state = activeInstance.store.state
                TelemetryPublisher.publish(state, dtSeconds = TIMESTEP_SEC)
                activeInstance.profiler.publishSensorsProfiling(activeInstance.telemetryManager)
                
                val ekfPose = currentPhysPose
                if (sampleCount % 250L == 0L) {
                    println("[SimTelemetry] ekfY=%.3f, physY=%.3f".format(ekfPose.y, postStepPose.y))
                }
                TelemetryPublisher.publishEstimatedPose(ekfPose)
                TelemetryPublisher.publishTargetPose(ekfPose)
            }

            // Always publish motor powers, velocities, and current draws on every 50Hz physics tick
            // The OpMode thread can change requested outputs concurrently. Snapshot once so the
            // rear/back compatibility aliases describe the same simulator frame.
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
            NT4Server.publishTopic("Hardware/Motors/bl/Power", rlPower)
            NT4Server.publishTopic("Hardware/Motors/br/Power", rrPower)
            NT4Server.publishTopic("Hardware/Motors/fl/Velocity", flVelocity)
            NT4Server.publishTopic("Hardware/Motors/fr/Velocity", frVelocity)
            NT4Server.publishTopic("Hardware/Motors/rl/Velocity", rlVelocity)
            NT4Server.publishTopic("Hardware/Motors/rr/Velocity", rrVelocity)
            NT4Server.publishTopic("Hardware/Motors/bl/Velocity", rlVelocity)
            NT4Server.publishTopic("Hardware/Motors/br/Velocity", rrVelocity)

            val flCurrent = robotDouble.fl.getCurrent(org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit.AMPS)
            val frCurrent = robotDouble.fr.getCurrent(org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit.AMPS)
            val rlCurrent = robotDouble.rl.getCurrent(org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit.AMPS)
            val rrCurrent = robotDouble.rr.getCurrent(org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit.AMPS)
            NT4Server.publishTopic("Hardware/Motors/fl/CurrentAmps", flCurrent)
            NT4Server.publishTopic("Hardware/Motors/fr/CurrentAmps", frCurrent)
            NT4Server.publishTopic("Hardware/Motors/rl/CurrentAmps", rlCurrent)
            NT4Server.publishTopic("Hardware/Motors/rr/CurrentAmps", rrCurrent)
            NT4Server.publishTopic("Hardware/Motors/bl/CurrentAmps", rlCurrent)
            NT4Server.publishTopic("Hardware/Motors/br/CurrentAmps", rrCurrent)

            if (activeOpMode?.isStarted == true) {
                NT4Server.publishTopic("ARES/DriverStation/MatchState", "TELEOP")
            } else {
                NT4Server.publishTopic("ARES/DriverStation/MatchState", "DISABLED")
            }

            sampleCount++

            // Always flush NT4 updates to clients on every loop frame (50Hz)
            ntInst.defaultServer?.flush()

            if (RobotClock.isMocked) {
                RobotClock.useMockTime(RobotClock.currentTimeMillis() + 20)
            }
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                break
            }
          } catch (e: Exception) {
              System.err.println("[Simulator] CRASH in main loop iteration $sampleCount:")
              e.printStackTrace()
              // Continue running — one bad frame shouldn't kill the sim
          }
        }
        stopActiveOpMode()
        RobotClock.useSystemTime()
    }
}
