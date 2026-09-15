package org.firstinspires.ftc.teamcode.opmodes

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.qualcomm.robotcore.eventloop.opmode.TeleOp

/**
 * Hardware-free diagnostic that never constructs the ARES robot facade.
 * If this mode initializes while a normal mode hangs, investigate configured hardware/I2C startup
 * rather than the Driver Station lifecycle. It commands no actuator.
 */
@TeleOp(name = "AAA Blank Null OpMode", group = "Diagnostics")
class NullOpMode : LinearOpMode() {

    override fun runOpMode() {
        telemetry.addData("Status", "Initialized Successfully!")
        telemetry.addData("Diagnosis", "Hardware-free initialization succeeded. If another mode hangs, investigate its startup and configured devices.")
        telemetry.update()

        waitForStart()

        var lastTelemetryMs = 0L
        var hasTelemetryTime = false
        while (opModeIsActive()) {
            val nowMs = com.areslib.util.RobotClock.currentTimeMillis()
            val elapsed = nowMs - lastTelemetryMs
            if (!hasTelemetryTime || nowMs < lastTelemetryMs || elapsed < 0L || elapsed >= TELEMETRY_PERIOD_MS) {
                lastTelemetryMs = nowMs
                hasTelemetryTime = true
                telemetry.addData("Status", "Running...")
                telemetry.update()
            }
            idle()
        }
    }

    private companion object {
        const val TELEMETRY_PERIOD_MS = 250L
    }
}
