package org.firstinspires.ftc.teamcode

import com.areslib.ftc.FtcMecanumRobot
import com.areslib.ftc.update
import com.areslib.hardware.actuator.FlywheelIO
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.qualcomm.robotcore.eventloop.opmode.TeleOp

/** LinearOpMode fixture that exercises the shared calibration controller in the desktop simulator. */
@TeleOp(name = "ARES: Calibration Contract Test", group = "ARES Test")
class CalibrationTestOpMode : LinearOpMode() {
    override fun runOpMode() {
        val flywheel = SimulatedFlywheelIO()
        val robot = FtcMecanumRobot(hardwareMap, pinpointName = "pinpoint", limelightName = "limelight")
        try {
            robot.sysIdFlywheelIO = flywheel
            robot.isLiveTuningEnabled = true
            waitForStart()
            if (isStopRequested || Thread.currentThread().isInterrupted) return
            // Match the physical tuning OpMode: calibration ownership does not exist during INIT.
            robot.enableCalibrationMode()
            while (opModeIsActive()) {
                flywheel.refresh()
                robot.update()
                Thread.sleep(20L)
            }
        } finally {
            runCatching { robot.disableCalibrationMode() }
            robot.sysIdFlywheelIO = null
            robot.isLiveTuningEnabled = false
            runCatching { flywheel.safe() }
            robot.close()
        }
    }

    /** Deterministic first-order plant used to verify flywheel SysId without season hardware. */
    private class SimulatedFlywheelIO : FlywheelIO {
        private var appliedVoltage = 0.0
        private var cachedVelocityRpm = 0.0
        private var cachedCurrentAmps = 0.0

        override fun refresh() {
            val targetRpm = appliedVoltage * RPM_PER_VOLT
            cachedVelocityRpm += (targetRpm - cachedVelocityRpm) * RESPONSE_PER_LOOP
            // Synthetic winding-current magnitude from voltage minus back EMF; no hardware reading.
            cachedCurrentAmps = kotlin.math.abs(appliedVoltage - cachedVelocityRpm / RPM_PER_VOLT) / WINDING_RESISTANCE_OHMS
        }

        override fun setVelocityRpm(rpm: Double, maxEffortScale: Double) {
            val limit = if (maxEffortScale.isFinite()) 12.0 * maxEffortScale.coerceIn(0.0, 1.0) else 0.0
            appliedVoltage = if (rpm.isFinite()) (rpm / RPM_PER_VOLT).coerceIn(-limit, limit) else 0.0
        }

        override fun setAppliedVoltage(volts: Double) {
            appliedVoltage = if (volts.isFinite()) volts.coerceIn(-12.0, 12.0) else 0.0
        }

        override val velocityRpm: Double
            get() = cachedVelocityRpm

        override val velocityValid: Boolean
            get() = cachedVelocityRpm.isFinite()

        override val currentAmps: Double
            get() = cachedCurrentAmps

        companion object {
            private const val RPM_PER_VOLT = 420.0
            private const val RESPONSE_PER_LOOP = 0.08
            private const val WINDING_RESISTANCE_OHMS = 0.5
        }
    }
}
