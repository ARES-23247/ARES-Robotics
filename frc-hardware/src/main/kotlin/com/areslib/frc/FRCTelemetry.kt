package com.areslib.frc

import com.areslib.telemetry.ITelemetry
import com.areslib.telemetry.TelemetryTopicNormalizer
import edu.wpi.first.wpilibj.DataLogManager
import edu.wpi.first.networktables.NetworkTableEntry
import edu.wpi.first.networktables.NetworkTableInstance

/**
 * FRC-specific implementation of the telemetry interface ([ITelemetry]).
 *
 * Routes canonical slash-free ARES topic keys directly through WPILib NetworkTables 4.
 * By initializing WPILib's [DataLogManager], all NT4 key updates, joystick inputs, and console outputs are automatically saved to `.wpilog` files on the RoboRIO.
 *
 * @see ITelemetry
 * @see DataLogManager
 */
class FRCTelemetry internal constructor(
    private val instance: NetworkTableInstance = NetworkTableInstance.getDefault(),
    startDataLog: Boolean = true,
) : ITelemetry {
    private val entries = HashMap<String, NetworkTableEntry>()

    /** Creates the production adapter over WPILib's process-owned NT4 instance. */
    constructor() : this(NetworkTableInstance.getDefault(), true)

    init {
        // Starts the deterministic logger (logs all NT4 values, joystick inputs, and console output to a .wpilog)
        if (startDataLog) {
            DataLogManager.start()
            DataLogManager.log("ARESLib: FRC Telemetry Initialized")
        }
    }

    /**
     * Publishes a numeric telemetry value to SmartDashboard / NT4.
     *
     * @param key Telemetry entry key.
     * @param value Double scalar value.
     */
    override fun putNumber(key: String, value: Double) {
        entry(key).setDouble(value)
    }

    /**
     * Publishes a boolean flag to SmartDashboard / NT4.
     *
     * @param key Telemetry entry key.
     * @param value Boolean flag value.
     */
    override fun putBoolean(key: String, value: Boolean) {
        entry(key).setBoolean(value)
    }

    /**
     * Publishes a string entry to SmartDashboard / NT4.
     *
     * @param key Telemetry entry key.
     * @param value String message.
     */
    override fun putString(key: String, value: String) {
        entry(key).setString(value)
    }

    /**
     * Publishes a double array entry to SmartDashboard / NT4.
     *
     * @param key Telemetry entry key.
     * @param value Array of doubles.
     */
    override fun putDoubleArray(key: String, value: DoubleArray) {
        entry(key).setDoubleArray(value)
    }

    /**
     * Reads a numeric value from SmartDashboard / NT4, falling back to [defaultValue].
     *
     * @param key Telemetry entry key.
     * @param defaultValue Fallback value if key is not present.
     * @return Double scalar value.
     */
    override fun getNumber(key: String, defaultValue: Double): Double {
        return entry(key).getDouble(defaultValue)
    }

    /**
     * Reads a boolean flag from SmartDashboard / NT4, falling back to [defaultValue].
     *
     * @param key Telemetry entry key.
     * @param defaultValue Fallback value if key is not present.
     * @return Boolean flag value.
     */
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return entry(key).getBoolean(defaultValue)
    }

    /**
     * Reads a string entry from SmartDashboard / NT4, falling back to [defaultValue].
     *
     * @param key Telemetry entry key.
     * @param defaultValue Fallback value if key is not present.
     * @return String message.
     */
    override fun getString(key: String, defaultValue: String): String {
        return entry(key).getString(defaultValue)
    }

    override fun update() {
        instance.flushLocal()
    }

    override fun close() {
        entries.values.forEach(NetworkTableEntry::close)
        entries.clear()
    }

    private fun entry(key: String): NetworkTableEntry {
        val canonical = TelemetryTopicNormalizer.normalizeTopic(key)
        return entries.getOrPut(canonical) { instance.getEntry(canonical) }
    }
}
