package com.areslib.ftc.core

import com.qualcomm.robotcore.hardware.HardwareMap

/**
 * Subsystem controller managing FTC OpMode lifecycle transitions and loop rate throttling.
 *
 * Tracks OpMode state transitions (`"Init"`, `"TeleOp"`, `"Autonomous"`), manages the local
 * [com.areslib.logging.LogManagerServer] on port 5002, and enforces
 * simulator lifecycle state. On desktop, iterative callbacks are paced by `DesktopSimLauncher`,
 * while SDK-style `LinearOpMode` worker loops retain one robot-owned 20 ms sleep. The simulator
 * marks runner-owned callback frames explicitly so a frame is never slept twice.
 *
 * ### Physical Units & Timing:
 * - Target loop rate: 50Hz ($20\text{ms}$ delta time step $\Delta t$).
 * - Wall-clock timestamps: Milliseconds ($ms$).
 *
 * @see com.areslib.telemetry.RobotStatusTracker
 * @see com.areslib.logging.LogManagerServer
 */
class FtcOpModeLifecycleController {

    companion object {
        private val externallyPacedFrame = ThreadLocal<Boolean>()

        /** Marks one iterative simulator callback as already paced by its outer frame runner. */
        @JvmStatic
        fun beginExternallyPacedFrame(): Boolean {
            val previous = externallyPacedFrame.get() == true
            externallyPacedFrame.set(true)
            return previous
        }

        /** Restores the prior pacing owner after an iterative callback returns or throws. */
        @JvmStatic
        fun endExternallyPacedFrame(previouslyExternallyPaced: Boolean) {
            externallyPacedFrame.set(previouslyExternallyPaced)
        }

        @JvmStatic
        fun isCurrentFrameExternallyPaced(): Boolean = externallyPacedFrame.get() == true
    }

    /**
     * Initializes hardware performance managers, starts the log server, and sets active OpMode status to `"Init"`.
     *
     * @param hardwareMap Qualcomm FTC SDK hardware map reference.
     */
    fun init(hardwareMap: HardwareMap) {
        com.areslib.ftc.hardware.FtcPerformanceManager.initialize(hardwareMap)
        com.areslib.logging.LogManagerServer.startServer()
        com.areslib.telemetry.RobotStatusTracker.isEnabled = false
        com.areslib.telemetry.RobotStatusTracker.activeOpMode = "Init"
    }

    /**
     * Monitors active OpMode state transitions.
     */
    fun update() {
        if (com.areslib.telemetry.RobotStatusTracker.activeOpMode != "Init") {
            com.areslib.telemetry.RobotStatusTracker.isEnabled = true
        }
    }

    /**
     * Resets robot status tracking state.
     */
    fun close() {
        com.areslib.telemetry.RobotStatusTracker.isEnabled = false
    }

    /** Sleeps an unpaced desktop worker for the remainder of its 20 ms control frame. */
    fun sleepRemaining(timestamp: Long, isAndroid: Boolean) {
        if (isAndroid || isCurrentFrameExternallyPaced()) return
        val elapsed = com.areslib.util.RobotClock.currentTimeMillis() - timestamp
        val sleepTime = 20L - elapsed
        if (sleepTime <= 0L) return
        try {
            Thread.sleep(sleepTime)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

