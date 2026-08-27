package org.aresfirst.starter.frc

import com.areslib.telemetry.ITelemetry
import com.areslib.tuning.TuningApplyContext
import com.areslib.tuning.TuningTopics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StarterFrcGuidedTuningTest {
    @Test
    fun `generic starter explicitly advertises no live SysId motion capability`() {
        val telemetry = MemoryTelemetry()
        val robot = StarterRobotRuntime(telemetry = telemetry)

        assertEquals("", telemetry.getString("SysId/SupportedMechanisms", "missing"))
        robot.close()
    }

    @Test
    fun `canonical generated tuning initializes the consumed Redux value`() {
        val robot = StarterRobotRuntime(
            telemetry = MemoryTelemetry(),
            tuningContextProvider = { TuningApplyContext(sessionArmed = true, robotDisabled = true) },
        )

        assertEquals(0.5, robot.store.state.tuning.drive.pathVelocityScale, 0.0)
        assertEquals(
            true,
            (robot.telemetry as MemoryTelemetry).getBoolean(
                "${TuningTopics.ROOT}/Parameters/frc.starter.path.velocity-scale/ConsumerSupported",
                false,
            ),
        )
        robot.close()
    }

    @Test
    fun `local simulation request is acknowledged only after Redux consumer commits value`() {
        val telemetry = MemoryTelemetry()
        val robot = StarterRobotRuntime(
            telemetry = telemetry,
            tuningContextProvider = { TuningApplyContext(sessionArmed = true, robotDisabled = false) },
        )
        val uid = "frc.starter.path.velocity-scale"
        val root = "${TuningTopics.ROOT}/Parameters/$uid"
        telemetry.putNumber("$root/Requested", 0.65)
        telemetry.putNumber("$root/RequestNonce", 7.0)

        robot.updateTuningForTest(500L)

        assertEquals(0.65, robot.store.state.tuning.drive.pathVelocityScale, 0.0)
        assertEquals(0.65, telemetry.getNumber("$root/Current", -1.0), 0.0)
        assertEquals("APPLIED", telemetry.getString("$root/LastResult", ""))
        assertEquals(7.0, telemetry.getNumber("$root/ProcessedNonce", -1.0), 0.0)
        robot.close()
    }

    @Test
    fun `unarmed physical-style context rejects request without changing consumer`() {
        val telemetry = MemoryTelemetry()
        val robot = StarterRobotRuntime(
            telemetry = telemetry,
            tuningContextProvider = { TuningApplyContext(sessionArmed = false, robotDisabled = true) },
        )
        val uid = "frc.starter.path.velocity-scale"
        val root = "${TuningTopics.ROOT}/Parameters/$uid"
        telemetry.putNumber("$root/Requested", 0.65)
        telemetry.putNumber("$root/RequestNonce", 8.0)

        robot.updateTuningForTest(500L)

        assertEquals(0.5, robot.store.state.tuning.drive.pathVelocityScale, 0.0)
        assertEquals(0.5, telemetry.getNumber("$root/Current", -1.0), 0.0)
        assertEquals("SESSION_NOT_ARMED", telemetry.getString("$root/LastResult", ""))
        assertEquals(8.0, telemetry.getNumber("$root/ProcessedNonce", -1.0), 0.0)
        robot.close()
    }

    private class MemoryTelemetry : ITelemetry {
        private val values = HashMap<String, Any>()

        override fun putNumber(key: String, value: Double) { values[key] = value }
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun putString(key: String, value: String) { values[key] = value }
        override fun putDoubleArray(key: String, value: DoubleArray) { values[key] = value.copyOf() }
        override fun getNumber(key: String, defaultValue: Double): Double = values[key] as? Double ?: defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
        override fun getString(key: String, defaultValue: String): String = values[key] as? String ?: defaultValue
        override fun update() = Unit
        override fun close() = Unit
    }
}
