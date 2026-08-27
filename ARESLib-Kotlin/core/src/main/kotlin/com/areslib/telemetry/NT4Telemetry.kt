package com.areslib.telemetry

import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server

/**
 * Modern Kotlin NT4 telemetry implementation.
 * Streams telemetry and state data directly to ARES-Analytics.
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
        val ntKey = if (key.startsWith("/")) key.substring(1) else key
        try { NT4Server.publishTopic(ntKey, value) } catch (e: Exception) { /* swallow */ }
    }

    override fun putBoolean(key: String, value: Boolean) {
        val ntKey = if (key.startsWith("/")) key.substring(1) else key
        try { NT4Server.publishTopic(ntKey, value) } catch (e: Exception) { /* swallow */ }
    }

    override fun putString(key: String, value: String) {
        val ntKey = if (key.startsWith("/")) key.substring(1) else key
        try { NT4Server.publishTopic(ntKey, value) } catch (e: Exception) { /* swallow */ }
    }

    override fun putDoubleArray(key: String, value: DoubleArray) {
        val ntKey = if (key.startsWith("/")) key.substring(1) else key
        try { NT4Server.publishTopic(ntKey, value) } catch (e: Exception) { /* swallow */ }
    }

    override fun getNumber(key: String, defaultValue: Double): Double {
        val ntKey = if (key.startsWith("/")) key.substring(1) else key
        return try { NT4Server.getDouble(ntKey, defaultValue) } catch (e: Exception) { defaultValue }
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val ntKey = if (key.startsWith("/")) key.substring(1) else key
        return try { NT4Server.getBoolean(ntKey, defaultValue) } catch (e: Exception) { defaultValue }
    }

    override fun getString(key: String, defaultValue: String): String {
        val ntKey = if (key.startsWith("/")) key.substring(1) else key
        return try { NT4Server.getString(ntKey, defaultValue) } catch (e: Exception) { defaultValue }
    }

    fun putPose2d(key: String, xMeters: Double, yMeters: Double, rotationRadians: Double) {
        putDoubleArray(key, doubleArrayOf(xMeters, yMeters, rotationRadians))
    }

    override fun update() {
        try { NT4Server.flushServer() } catch (e: Exception) { /* swallow */ }
    }

    override fun close() {
        // Keep server alive across OpModes
    }
}
