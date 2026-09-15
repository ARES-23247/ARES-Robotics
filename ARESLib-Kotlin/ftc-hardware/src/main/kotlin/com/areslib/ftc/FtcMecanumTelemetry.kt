package com.areslib.ftc

import com.areslib.ftc.drivetrain.MecanumHardwareIO
import com.areslib.ftc.power.FtcPowerManager
import com.areslib.ftc.telemetry.FtcTelemetryManager
import com.areslib.telemetry.logDriveMotor

/** Robot-owned motor diagnostics and the Driver Station presentation throttle. */
internal class FtcMecanumTelemetry {
    private var lastLocalTelemetryUpdateMs = 0L

    fun publish(timestamp: Long, telemetryManager: FtcTelemetryManager,
                powerManager: FtcPowerManager, mecanumIO: MecanumHardwareIO) {
        if (timestamp - lastLocalTelemetryUpdateMs >= 100L) {
            telemetryManager.customDriverStationText["Motor Powers"] = String.format("FL:%.2f | FR:%.2f | RL:%.2f | RR:%.2f",
                mecanumIO.flIO.power * mecanumIO.flIO.powerScale, mecanumIO.frIO.power * mecanumIO.frIO.powerScale,
                mecanumIO.rlIO.power * mecanumIO.rlIO.powerScale, mecanumIO.rrIO.power * mecanumIO.rrIO.powerScale
            )
            telemetryManager.customDriverStationText["Current Draw"] = if (powerManager.floodgate != null) {
                String.format("%.1f A (Physical)", powerManager.floodgate.current)
            } else {
                String.format("%.1f A (Estimated)", powerManager.currentAmps)
            }
            telemetryManager.customDriverStationText["Drive Output Safety"] = if (mecanumIO.outputFaultLatched) {
                "FAULT LATCHED — release controls and run Recover drive after a fault"
            } else {
                "Ready — motor outputs permitted"
            }
            lastLocalTelemetryUpdateMs = timestamp
        }

        telemetryManager.dataLoggingTelemetry.putBoolean("Drive/OutputFaultLatched", mecanumIO.outputFaultLatched)

        telemetryManager.dataLoggingTelemetry.logDriveMotor("fl", mecanumIO.flIO)
        telemetryManager.dataLoggingTelemetry.logDriveMotor("fr", mecanumIO.frIO)
        telemetryManager.dataLoggingTelemetry.logDriveMotor("rl", mecanumIO.rlIO)
        telemetryManager.dataLoggingTelemetry.logDriveMotor("rr", mecanumIO.rrIO)

    }
}
