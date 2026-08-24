package org.aresfirst.starter.frc

import com.areslib.telemetry.ITelemetry
import com.areslib.telemetry.TelemetryTopicNormalizer
import edu.wpi.first.networktables.NetworkTableEntry
import edu.wpi.first.networktables.NetworkTableInstance
import edu.wpi.first.wpilibj.DataLogManager

/**
 * Publishes ARES topics through WPILib's process-owned NetworkTables instance.
 *
 * WPILib owns the NT4 server on an FRC robot and in desktop simulation. This adapter deliberately
 * does not create ARESLib's standalone FTC/simulator NT4 server, so there is one transport owner and
 * one port-5810 lifecycle.
 */
internal class StarterFrcTelemetry(
    private val instance: NetworkTableInstance = NetworkTableInstance.getDefault(),
    startDataLog: Boolean = true,
) : ITelemetry {
    private val entries = HashMap<String, NetworkTableEntry>()

    init {
        if (startDataLog) {
            DataLogManager.start()
            DataLogManager.log("ARES FRC Starter telemetry initialized")
        }
    }

    override fun putNumber(key: String, value: Double) {
        entry(key).setDouble(value)
    }

    override fun putBoolean(key: String, value: Boolean) {
        entry(key).setBoolean(value)
    }

    override fun putString(key: String, value: String) {
        entry(key).setString(value)
    }

    override fun putDoubleArray(key: String, value: DoubleArray) {
        entry(key).setDoubleArray(value)
    }

    override fun getNumber(key: String, defaultValue: Double): Double =
        entry(key).getDouble(defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        entry(key).getBoolean(defaultValue)

    override fun getString(key: String, defaultValue: String): String =
        entry(key).getString(defaultValue)

    override fun update() {
        instance.flushLocal()
    }

    override fun close() {
        entries.values.forEach(NetworkTableEntry::close)
        entries.clear()
    }

    private fun entry(key: String): NetworkTableEntry {
        val canonical = canonicalStarterFrcTelemetryTopic(key)
        return entries.getOrPut(canonical) { instance.getEntry(canonical) }
    }
}

internal fun canonicalStarterFrcTelemetryTopic(key: String): String =
    TelemetryTopicNormalizer.normalizeTopic(key)
