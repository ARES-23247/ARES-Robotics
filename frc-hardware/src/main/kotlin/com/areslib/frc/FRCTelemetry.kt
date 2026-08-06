package com.areslib.frc

import com.areslib.telemetry.ITelemetry
import edu.wpi.first.wpilibj.DataLogManager
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard
import edu.wpi.first.networktables.NetworkTableInstance

/**
 * FRC-specific implementation of the telemetry interface ([ITelemetry]).
 *
 * Routes all published telemetry key-value pairs to WPILib's SmartDashboard (NetworkTables 4).
 * By initializing WPILib's [DataLogManager], all NT4 key updates, joystick inputs, and console outputs are automatically saved to `.wpilog` files on the RoboRIO.
 *
 * @see ITelemetry
 * @see SmartDashboard
 * @see DataLogManager
 */
class FRCTelemetry : ITelemetry {

    init {
        // Starts the deterministic logger (logs all NT4 values, joystick inputs, and console output to a .wpilog)
        DataLogManager.start()
        
        // Explicitly start NT4 Server for AdvantageScope to connect to
        NetworkTableInstance.getDefault().startServer()
        
        // Log custom strings or events if necessary
        DataLogManager.log("ARESLib: FRC Telemetry Initialized")
    }

    /**
     * Publishes a numeric telemetry value to SmartDashboard / NT4.
     *
     * @param key Telemetry entry key.
     * @param value Double scalar value.
     */
    override fun putNumber(key: String, value: Double) {
        SmartDashboard.putNumber(key, value)
    }

    /**
     * Publishes a boolean flag to SmartDashboard / NT4.
     *
     * @param key Telemetry entry key.
     * @param value Boolean flag value.
     */
    override fun putBoolean(key: String, value: Boolean) {
        SmartDashboard.putBoolean(key, value)
    }

    /**
     * Publishes a string entry to SmartDashboard / NT4.
     *
     * @param key Telemetry entry key.
     * @param value String message.
     */
    override fun putString(key: String, value: String) {
        SmartDashboard.putString(key, value)
    }

    /**
     * Publishes a double array entry to SmartDashboard / NT4.
     *
     * @param key Telemetry entry key.
     * @param value Array of doubles.
     */
    override fun putDoubleArray(key: String, value: DoubleArray) {
        SmartDashboard.putNumberArray(key, value)
    }

    /**
     * Reads a numeric value from SmartDashboard / NT4, falling back to [defaultValue].
     *
     * @param key Telemetry entry key.
     * @param defaultValue Fallback value if key is not present.
     * @return Double scalar value.
     */
    override fun getNumber(key: String, defaultValue: Double): Double {
        return SmartDashboard.getNumber(key, defaultValue)
    }

    /**
     * Reads a boolean flag from SmartDashboard / NT4, falling back to [defaultValue].
     *
     * @param key Telemetry entry key.
     * @param defaultValue Fallback value if key is not present.
     * @return Boolean flag value.
     */
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return SmartDashboard.getBoolean(key, defaultValue)
    }

    /**
     * Reads a string entry from SmartDashboard / NT4, falling back to [defaultValue].
     *
     * @param key Telemetry entry key.
     * @param defaultValue Fallback value if key is not present.
     * @return String message.
     */
    override fun getString(key: String, defaultValue: String): String {
        return SmartDashboard.getString(key, defaultValue)
    }
}

