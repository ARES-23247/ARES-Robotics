package org.aresfirst.starter.frc

import com.areslib.telemetry.ITelemetry
import edu.wpi.first.wpilibj.DataLogManager
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard

/**
 * Publishes ARES topics through WPILib's process-owned NetworkTables instance.
 *
 * WPILib owns the NT4 server on an FRC robot and in desktop simulation. This adapter deliberately
 * does not create ARESLib's standalone FTC/simulator NT4 server, so there is one transport owner and
 * one port-5810 lifecycle.
 */
internal class StarterFrcTelemetry : ITelemetry {
    init {
        DataLogManager.start()
        DataLogManager.log("ARES FRC Starter telemetry initialized")
    }

    override fun putNumber(key: String, value: Double) {
        SmartDashboard.putNumber(key, value)
    }

    override fun putBoolean(key: String, value: Boolean) {
        SmartDashboard.putBoolean(key, value)
    }

    override fun putString(key: String, value: String) {
        SmartDashboard.putString(key, value)
    }

    override fun putDoubleArray(key: String, value: DoubleArray) {
        SmartDashboard.putNumberArray(key, value)
    }

    override fun getNumber(key: String, defaultValue: Double): Double =
        SmartDashboard.getNumber(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        SmartDashboard.getBoolean(key, defaultValue)

    override fun getString(key: String, defaultValue: String): String =
        SmartDashboard.getString(key, defaultValue)
}
