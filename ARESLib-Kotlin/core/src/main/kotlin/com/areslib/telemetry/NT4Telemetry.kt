package com.areslib.telemetry

import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server
import com.areslib.networktables.NT4Value

/**
 * Typed telemetry adapter over the process-wide NT4 server.
 * Numeric getters accept published double, float and integer values; other getters require their
 * corresponding scalar type. Absent values, placeholders and incompatible types use the default.
 * Construction starts the shared server if absent; close leaves that server owned by the process.
 */
class NT4Telemetry : ITelemetry {
    private val inst = NT4Instance.defaultInstance

    init {
        try {
            if (inst.defaultServer == null) {
                inst.startServer("0.0.0.0", 5810)
                println("NT4Telemetry: Started NT4 server on port 5810 for ARES-Analytics")
            }
        } catch (e: Exception) {
            System.err.println("NT4Telemetry: Failed to start NT4 Server! ${e.message}")
        }
    }

    override fun putNumber(key: String, value: Double) {
        try { NT4Server.publishTopic(key, value) } catch (e: Exception) { /* swallow */ }
    }

    override fun putBoolean(key: String, value: Boolean) {
        try { NT4Server.publishTopic(key, value) } catch (e: Exception) { /* swallow */ }
    }

    override fun putString(key: String, value: String) {
        try { NT4Server.publishTopic(key, value) } catch (e: Exception) { /* swallow */ }
    }

    override fun putDoubleArray(key: String, value: DoubleArray) {
        try { NT4Server.publishTopic(key, value) } catch (e: Exception) { /* swallow */ }
    }

    override fun getNumber(key: String, defaultValue: Double): Double {
        return when (val value = publishedValue(key)) {
            is NT4Value.DoubleVal -> value.value
            is NT4Value.FloatVal -> value.value.toDouble()
            is NT4Value.LongVal -> value.value.toDouble()
            else -> defaultValue
        }
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return (publishedValue(key) as? NT4Value.BooleanVal)?.value ?: defaultValue
    }

    override fun getString(key: String, defaultValue: String): String {
        return (publishedValue(key) as? NT4Value.StringVal)?.value ?: defaultValue
    }

    // Canonical lookup occurs once at the registry boundary. Reading the typed immutable value
    // avoids boxing numeric results or snapshotting arrays that are incompatible with a getter.
    private fun publishedValue(key: String): NT4Value? =
        inst.defaultServer?.getTopicEntry(key)?.takeIf { it.hasValue }?.value

    fun putPose2d(key: String, xMeters: Double, yMeters: Double, rotationRadians: Double) {
        logPoseArray2d(key, xMeters, yMeters, rotationRadians)
    }

    override fun update() {
        try { NT4Server.flushServer() } catch (e: Exception) { /* swallow */ }
    }

    override fun close() {
        // Keep server alive across OpModes
    }
}
