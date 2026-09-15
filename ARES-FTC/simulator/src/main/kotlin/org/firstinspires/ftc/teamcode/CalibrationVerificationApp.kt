package org.firstinspires.ftc.teamcode

import edu.wpi.first.networktables.NetworkTableInstance
import kotlin.concurrent.thread
import com.areslib.sim.DesktopSimLauncher
import com.areslib.sim.NoOpInteractionModel

/**
 * Headless end-to-end smoke runner for FTC calibration and SysId command contracts.
 *
 * It launches the calibration contract OpMode against FTC mocks, drives its Driver Station lifecycle
 * over local NT4, then verifies each calibration publishes the expected status and a minimum amount
 * of data. Monotonic elapsed time bounds this supervisor independently of wall-clock changes;
 * robot control and replay code continue to use `RobotClock`.
 */
fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
    println("=================================================================")
    println("STARTING PROGRAMMATIC CALIBRATION ROUTINES VERIFICATION")
    println("=================================================================")

    // Launch the simulator first; its NT4 server is the system under test.
    thread(isDaemon = true, name = "ARES-Calibration-Simulator") {
        try {
            DesktopSimLauncher.launch(
                args = arrayOf("--opmode", "org.firstinspires.ftc.teamcode.CalibrationTestOpMode", "--headless"),
                interactionModel = NoOpInteractionModel()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    var succeeded = false
    try {
        CalibrationVerificationResources().use { resources ->
            // Use an independent WPILib client so this validates the wire contract, not an in-process API.
            val ntInst = resources.own(NetworkTableInstance.create())
            val supervisionStart = kotlin.time.TimeSource.Monotonic.markNow()
            fun nowMs() = supervisionStart.elapsedNow().inWholeMilliseconds
            ntInst.startClient4("CalibrationVerificationClient")
            ntInst.setServer("127.0.0.1")

            // Bound startup wait so CI cannot hang indefinitely when the simulator fails early.
            var connected = false
            val startConnectTime = nowMs()
            while (nowMs() - startConnectTime < 10000) {
                if (ntInst.isConnected) {
                    connected = true
                    break
                }
                Thread.sleep(100)
            }

            if (!connected) {
                error("Verification Failed: Could not connect to simulator NT4 server!")
            }
            println("Connected to simulator NT4 server.")

            // Driver Station and dedicated calibration topics use canonical names without leading slashes.
            val cmdPub = resources.own(ntInst.getStringTopic("ARES/DriverStation/Command").publish())
            resources.beforeClose { cmdPub.set("STOP") }
            val selectPub = resources.own(ntInst.getStringTopic("ARES/DriverStation/SelectedOpMode").publish())
            val calCmdPub = resources.own(ntInst.getStringTopic("SysId/Command").publish())
            resources.beforeClose { calCmdPub.set("STOP") }
            resources.beforeClose { ntInst.flush() }
            val enableTokenPub = resources.own(ntInst.getStringTopic("SysId/EnableToken").publish())
            val enableLeasePub = resources.own(ntInst.getDoubleTopic("SysId/EnableLease").publish())
            val armedSub = resources.own(ntInst.getBooleanTopic(
                com.areslib.telemetry.TelemetryTopicNormalizer.toWireTopic("SysId/Armed")
            ).subscribe(false))
            calCmdPub.set("STOP")

            // Follow the same INIT then START lifecycle as the desktop Driver Station.
            selectPub.set("org.firstinspires.ftc.teamcode.CalibrationTestOpMode")
            cmdPub.set("INIT")
            println("Sent INIT command.")
            Thread.sleep(3000)

            // Calibration mode is intentionally absent throughout INIT. START first, allow the OpMode to
            // snapshot retained input as stale, and only then publish the fresh neutral enable token.
            cmdPub.set("START")
            println("Sent START command.")
            ntInst.flush()
            Thread.sleep(250L)
            enableTokenPub.set("calibration-verification-${System.nanoTime()}")
            var enableLeaseSequence = 1L
            enableLeasePub.set(enableLeaseSequence.toDouble())
            ntInst.flush()
            val armDeadline = nowMs() + 3000L
            var lastLeaseRefreshMs = nowMs()
            while (!armedSub.get() && nowMs() < armDeadline) {
                Thread.sleep(25L)
                val nowMs = nowMs()
                if (nowMs - lastLeaseRefreshMs >= 200L) {
                    enableLeasePub.set((++enableLeaseSequence).toDouble())
                    ntInst.flush()
                    lastLeaseRefreshMs = nowMs
                }
            }
            val calibrationArmed = armedSub.get()

            // Calibration command/status/data contract shared with ARES Analytics.
            // WPILib's client-facing topic namespace includes the root slash used in server announcements.
            // Robot and dashboard code still normalize stored/published keys to no leading slash.
            val calStatusSub = resources.own(ntInst.getStringTopic(
                com.areslib.telemetry.TelemetryTopicNormalizer.toWireTopic("SysId/Status")
            ).subscribe("NONE"))
            val calDataSub = resources.own(ntInst.getDoubleArrayTopic(
                com.areslib.telemetry.TelemetryTopicNormalizer.toWireTopic("SysId/Data")
            ).subscribe(doubleArrayOf()))

            fun runCalibrationTest(command: String, expectedStatus: String) {
                println("\n--- Testing: $command (Expecting Status: $expectedStatus) ---")

                check(armedSub.get()) { "Calibration controller disarmed before $command" }
                // Trigger and allow for NT4 topic announcement plus the robot's next control loop.
                calCmdPub.set(command)
                ntInst.flush()
                val statusDeadline = nowMs() + 2500L
                var currentStatus = calStatusSub.get()
                while (currentStatus != expectedStatus && nowMs() < statusDeadline) {
                    Thread.sleep(50L)
                    val nowMs = nowMs()
                    if (nowMs - lastLeaseRefreshMs >= 200L) {
                        enableLeasePub.set((++enableLeaseSequence).toDouble())
                        ntInst.flush()
                        lastLeaseRefreshMs = nowMs
                    }
                    currentStatus = calStatusSub.get()
                }
                println("Current Status: $currentStatus")
                if (currentStatus != expectedStatus) {
                    val serverStatus = com.areslib.networktables.NT4Server.getString("SysId/Status", "MISSING")
                    error("Expected status $expectedStatus, but client got $currentStatus (server has $serverStatus)")
                }

                // Observe bounded progress; repeated arrays count as streamed samples for this smoke test.
                val startWait = nowMs()
                var pointsCount = 0
                var wentBackToNone = false
                var lastDataChange = calDataSub.lastChange

                while (nowMs() - startWait < 8000) {
                    val nowMs = nowMs()
                    if (nowMs - lastLeaseRefreshMs >= 200L) {
                        enableLeasePub.set((++enableLeaseSequence).toDouble())
                        ntInst.flush()
                        lastLeaseRefreshMs = nowMs
                    }
                    val status = calStatusSub.get()
                    val data = calDataSub.get()

                    val dataChange = calDataSub.lastChange
                    if (data.isNotEmpty() && dataChange != 0L && dataChange != lastDataChange) {
                        pointsCount++
                        lastDataChange = dataChange
                    }
                    if (status == "NONE") {
                        wentBackToNone = true
                        break
                    }
                    Thread.sleep(100)
                }

                println("Finished $command. Points collected: $pointsCount, returned to NONE: $wentBackToNone")

                val failureMessage = when {
                    !wentBackToNone ->
                        "Calibration routine $command did not return to NONE before the completion deadline"
                    pointsCount < 5 ->
                        "Insufficient fresh calibration samples: received $pointsCount"
                    else -> null
                }

                // Reset command state before the next routine, including on a recorded verification failure.
                calCmdPub.set("STOP")
                ntInst.flush()
                Thread.sleep(300)
                if (failureMessage != null) error(failureMessage)
            }

            // Hardware-affecting routines are deliberately serialized.
            check(calibrationArmed) { "Verification Failed: dedicated calibration OpMode did not arm" }
            // Check vision from the initial pose before motion routines change the camera's view.
            runCalibrationTest("START_VISION_CALIBRATION", "VISION_CALIBRATION")
            runCalibrationTest("START_PINPOINT_SPIN", "PINPOINT_SPIN")
            runCalibrationTest("START_TRACK_WIDTH_SPIN", "TRACK_WIDTH_SPIN")
            runCalibrationTest("START_LINEAR_DRIVE", "LINEAR_DRIVE")

            // Exercise quasistatic and dynamic modes for linear, angular, and flywheel characterization.
            runCalibrationTest("START_LINEAR_QUASISTATIC", "QUASISTATIC")
            runCalibrationTest("START_LINEAR_DYNAMIC", "DYNAMIC")

            runCalibrationTest("START_ANGULAR_QUASISTATIC", "QUASISTATIC")
            runCalibrationTest("START_ANGULAR_DYNAMIC", "DYNAMIC")

            runCalibrationTest("START_FLYWHEEL_QUASISTATIC", "QUASISTATIC")
            runCalibrationTest("START_FLYWHEEL_DYNAMIC", "DYNAMIC")

            println("\n=================================================================")
            println("ALL CALIBRATION AND SYSID ROUTINES PASSED HEADLESSLY!")
            println("=================================================================")
        }
        succeeded = true
    } catch (e: Exception) {
        e.printStackTrace()
    }
    kotlin.system.exitProcess(if (succeeded) 0 else 1)
}
