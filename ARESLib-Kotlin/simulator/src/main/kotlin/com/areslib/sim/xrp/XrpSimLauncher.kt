package com.areslib.sim.xrp

import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server
import com.areslib.sim.cli.SimCliParser
import com.areslib.sim.network.TelemetryPublisher
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldManager
import com.areslib.telemetry.TelemetryTopicConstants
import com.areslib.util.RobotClock
import java.util.concurrent.atomic.AtomicBoolean

/** Single-owner desktop XRP simulation loop, paced nominally at 50 Hz. */
object XrpSimLauncher {
    @Volatile var isRunning = true
    private val runActive = AtomicBoolean(false)

    @JvmStatic
    fun main(args: Array<String>) {
        check(runActive.compareAndSet(false, true)) { "An XRP simulation loop is already running" }
        val previousConfig = RobotFieldManager.activeConfig
        var ownedConfig = previousConfig
        var ownedServer: NT4Server? = null
        var engine: XrpSimulationEngine? = null
        var failure: Throwable? = null
        val shutdownHook = Thread { isRunning = false }
        var hookRegistered = false
        try {
            isRunning = true
            val mecanum = args.any { it.equals("--mecanum", ignoreCase = true) }
            val cliArgs = SimCliParser.parseArgs(args.filterNot { it.equals("--mecanum", ignoreCase = true) }.toTypedArray())
            require(cliArgs.opModeClassName == null) { "XRP simulation does not run FTC OpModes" }
            val fieldConfig = SimCliParser.loadFieldConfig(cliArgs.fieldConfigArg)
            ownedConfig = RobotFieldManager.activeConfig
            if (NT4Instance.defaultInstance.defaultServer == null) {
                ownedServer = NT4Instance.defaultInstance.startServer("127.0.0.1", 5810)
            }
            val telemetry = com.areslib.telemetry.NT4Telemetry()
            TelemetryPublisher.init(telemetry, com.areslib.telemetry.ARESNetworkStatePublisher(telemetry))
            val simulation = XrpSimulationEngine(
                drivetrainType = if (mecanum) XrpDrivetrainType.MECANUM else XrpDrivetrainType.DIFFERENTIAL,
                activeConfig = fieldConfig
            )
            engine = simulation
            ownedConfig = RobotFieldManager.activeConfig
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            hookRegistered = true
            val driveFrame = DoubleArray(8)
            val malformedFrame = DoubleArray(0)
            println("[XRP Simulator] ${simulation.drivetrainType} simulation awaiting neutral v2 handshake.")
            while (isRunning && !Thread.currentThread().isInterrupted) {
                val started = RobotClock.nanoTime()
                val count = NT4Server.copyDoubleArray(TelemetryTopicConstants.DRIVE_INPUT_FRAME, driveFrame)
                if (count != -1) simulation.processDriveFrame(if (count == 8) driveFrame else malformedFrame)
                TelemetryPublisher.pollWebFieldConfig()?.let { json ->
                    val document = RobotFieldDocument.decode(json)
                    simulation.physicsWorld.loadFieldElements(document)
                    ownedConfig = RobotFieldManager.activeConfig
                }
                simulation.step(0.02)
                simulation.publishTelemetry()
                val remainingNanos = 20_000_000L - (RobotClock.nanoTime() - started).coerceAtLeast(0L)
                if (remainingNanos > 0L) {
                    try { Thread.sleep(remainingNanos / 1_000_000L, (remainingNanos % 1_000_000L).toInt()) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            isRunning = false
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (caught: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = caught else if (caught !== first) first.addSuppressed(caught)
                }
            }
            cleanup { engine?.stop() }
            cleanup { engine?.publishTelemetry() }
            if (hookRegistered) cleanup {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook) } catch (_: IllegalStateException) { /* JVM shutdown */ }
            }
            cleanup {
                val server = ownedServer
                if (server != null && NT4Server.getInstance() === server) {
                    server.stop()
                    NT4Server.resetSharedState()
                }
            }
            cleanup { if (RobotFieldManager.activeConfig === ownedConfig) RobotFieldManager.setActiveConfig(previousConfig) }
            runActive.set(false)
            val cleanupError = cleanupFailure
            if (cleanupError != null) {
                val primary = failure
                if (primary == null) throw cleanupError
                if (cleanupError !== primary) primary.addSuppressed(cleanupError)
            }
        }
    }
}
