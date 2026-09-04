package com.areslib.sim.xrp

import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server
import com.areslib.sim.cli.SimCliParser
import com.areslib.sim.network.TelemetryPublisher
import com.areslib.state.RobotFieldDocument
import com.areslib.util.RobotClock
import java.io.File

/**
 * Desktop simulation launcher entry point for XRP robots.
 *
 * Can be executed headlessly or directly from ARES Robotics Studio.
 */
object XrpSimLauncher {
    @Volatile
    var isRunning = true

    @JvmStatic
    fun main(args: Array<String>) {
        println("Starting ARES XRP Desktop Simulation...")
        val cliArgs = SimCliParser.parseArgs(args)
        val fieldConfig = SimCliParser.loadFieldConfig(cliArgs.fieldConfigArg)

        try {
            if (NT4Instance.defaultInstance.defaultServer == null) {
                NT4Instance.defaultInstance.startServer("0.0.0.0", 5810)
                println("[XRP Simulator] NT4 Server started on port 5810")
            }
        } catch (e: Exception) {
            println("[XRP Simulator] Warning starting NT4 server: ${e.message}")
        }

        val nt4Telemetry = com.areslib.telemetry.NT4Telemetry()
        val networkStatePublisher = com.areslib.telemetry.ARESNetworkStatePublisher(nt4Telemetry)
        TelemetryPublisher.init(nt4Telemetry, networkStatePublisher)

        val drivetrainType = if (args.any { it.equals("--mecanum", ignoreCase = true) }) {
            XrpDrivetrainType.MECANUM
        } else {
            XrpDrivetrainType.DIFFERENTIAL
        }

        println("[XRP Simulator] Drivetrain mode: $drivetrainType")
        val engine = XrpSimulationEngine(
            drivetrainType = drivetrainType,
            activeConfig = fieldConfig
        )

        Runtime.getRuntime().addShutdownHook(Thread {
            println("[XRP Simulator] Shutting down simulation...")
            isRunning = false
        })

        println("[XRP Simulator] Simulation running at 50Hz. Awaiting Studio commands.")

        val loopPeriodMs = 20L
        while (isRunning) {
            val startTime = System.currentTimeMillis()

            // 1. Poll incoming leased drive frame double[8]
            val driveFrame = NT4Server.getDoubleArray("ARES/Input/driveFrame", DoubleArray(0))
            if (driveFrame.isNotEmpty()) {
                engine.processDriveFrame(driveFrame)
            }

            // 2. Poll field configuration updates from Studio
            TelemetryPublisher.pollWebFieldConfig()?.let { configJson ->
                try {
                    val updatedDoc = RobotFieldDocument.decode(configJson)
                    engine.physicsWorld.loadFieldElements(updatedDoc)
                } catch (e: Exception) {
                    System.err.println("[XRP Simulator] Error updating field config: ${e.message}")
                }
            }

            // 3. Step physics
            engine.step(0.02)

            // 4. Publish telemetry
            engine.publishTelemetry()

            // Pace 50Hz loop
            val elapsed = System.currentTimeMillis() - startTime
            val sleepTime = loopPeriodMs - elapsed
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }
}
