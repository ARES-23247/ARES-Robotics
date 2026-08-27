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
        val robot = FtcMecanumRobot(hardwareMap, pinpointName = "pinpoint")
        robot.sysIdFlywheelIO = flywheel
        robot.isLiveTuningEnabled = true

        waitForStart()
        // Match the physical tuning OpMode: calibration ownership does not exist during INIT.
        robot.enableCalibrationMode()
        try {
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

        override fun refresh() {
            val targetRpm = appliedVoltage * RPM_PER_VOLT
            cachedVelocityRpm += (targetRpm - cachedVelocityRpm) * RESPONSE_PER_LOOP
        }

        override fun setVelocityRpm(rpm: Double) {
            appliedVoltage = (rpm / RPM_PER_VOLT).coerceIn(-12.0, 12.0)
        }

        override fun setAppliedVoltage(volts: Double) {
            appliedVoltage = volts.coerceIn(-12.0, 12.0)
        }

        override val velocityRpm: Double
            get() = cachedVelocityRpm

        override val velocityValid: Boolean
            get() = cachedVelocityRpm.isFinite()

        companion object {
            private const val RPM_PER_VOLT = 420.0
            private const val RESPONSE_PER_LOOP = 0.08
        }
    }
}
