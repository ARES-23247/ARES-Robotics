package com.areslib.ftc.core

import com.qualcomm.robotcore.hardware.HardwareMap

/**
 * Subsystem controller managing FTC OpMode lifecycle transitions, web server initialization, and loop rate throttling.
 *
 * Tracks OpMode state transitions (`"Init"`, `"TeleOp"`, `"Autonomous"`), manages local HTTP telemetry/log server startup
 * ([com.areslib.telemetry.RobotWebServer] on port 5001, [com.areslib.logging.LogManagerServer] on port 5002), and enforces
 * desktop simulation loop timing rate limiting ($50\text{Hz} = 20\text{ms}$).
 *
 * ### Physical Units & Timing:
 * - Target loop rate: 50Hz ($20\text{ms}$ delta time step $\Delta t$).
 * - Wall-clock timestamps: Milliseconds ($ms$).
 *
 * @see com.areslib.telemetry.RobotStatusTracker
 * @see com.areslib.telemetry.RobotWebServer
 * @see com.areslib.logging.LogManagerServer
 */
class FtcOpModeLifecycleController {

    /**
     * Initializes hardware performance managers, starts HTTP web telemetry and log servers, and sets active OpMode status to `"Init"`.
     *
     * @param hardwareMap Qualcomm FTC SDK hardware map reference.
     */
    fun init(hardwareMap: HardwareMap) {
        com.areslib.ftc.hardware.FtcPerformanceManager.initialize(hardwareMap)
        com.areslib.telemetry.RobotWebServer.start()
        com.areslib.logging.LogManagerServer.startServer()
        com.areslib.telemetry.RobotStatusTracker.isEnabled = false
        com.areslib.telemetry.RobotStatusTracker.activeOpMode = "Init"
    }

    /**
     * Monitors active OpMode state transitions and toggles HTTP telemetry web servers based on driver station status.
     */
    fun update() {
        if (!com.areslib.telemetry.RobotStatusTracker.isEnabled && com.areslib.telemetry.RobotStatusTracker.activeOpMode != "Init") {
            com.areslib.telemetry.RobotWebServer.start()
        } else if (!com.areslib.telemetry.RobotStatusTracker.isEnabled) {
            com.areslib.telemetry.RobotWebServer.stop()
        }

        if (com.areslib.telemetry.RobotStatusTracker.activeOpMode != "Init") {
            com.areslib.telemetry.RobotStatusTracker.isEnabled = true
        }
    }

    /**
     * Throttles loop execution timing on desktop simulation environments to enforce a target 50Hz ($20\text{ms}$) loop frequency.
     *
     * @param lastUpdateTime Wall-clock timestamp of previous iteration start in milliseconds ($ms$).
     * @param isAndroid `true` when running on physical Android hardware (Control Hub / Driver Station); `false` on desktop simulation.
     */
    fun sleepForTargetDt(lastUpdateTime: Long, isAndroid: Boolean) {
        if (!isAndroid && lastUpdateTime != 0L) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastUpdateTime
            if (elapsed < 20) {
                try {
                    Thread.sleep(20 - elapsed)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    /**
     * Pauses the current execution thread on desktop simulation runs for the remaining duration of a 20ms frame cycle.
     *
     * @param timestamp Loop iteration start timestamp in milliseconds ($ms$).
     * @param isAndroid `true` when running on physical Android hardware (Control Hub / Driver Station); `false` on desktop simulation.
     */
    fun sleepRemaining(timestamp: Long, isAndroid: Boolean) {
        if (!isAndroid) {
            val elapsed = System.currentTimeMillis() - timestamp
            val sleepTime = 20L - elapsed
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    /**
     * Halts background web servers and resets robot status tracking state.
     */
    fun close() {
        com.areslib.telemetry.RobotStatusTracker.isEnabled = false
        com.areslib.telemetry.RobotWebServer.stop()
    }
}

