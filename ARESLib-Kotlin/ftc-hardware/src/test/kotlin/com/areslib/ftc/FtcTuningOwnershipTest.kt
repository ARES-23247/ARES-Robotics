package com.areslib.ftc

import com.areslib.telemetry.ITelemetry
import com.areslib.tuning.*
import com.qualcomm.robotcore.hardware.HardwareMap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FtcTuningOwnershipTest {
    private object Wire : ITelemetry {
        override fun putNumber(key: String, value: Double) = Unit
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putString(key: String, value: String) = Unit
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
    }
    private fun manager() = TuningManager(
        TypedTuningRuntime(emptyList(), emptyMap(),
            TuningMetadataSnapshot("project.test", null, "profile.base", emptyList(), listOf("profile.base"))),
        Wire, { TuningApplyContext(true, true) }, { _, _ -> true }, { true },
    )

    private class ProbeRobot(val motors: Array<MockDcMotorEx> = Array(4) { MockDcMotorEx() }) : FtcMecanumRobot(
        object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T =
                motors[listOf("fl", "fr", "rl", "rr").indexOf(deviceName)] as T
            override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
        }, imuName = null,
    ) {
        var safetyCalls = 0
        var safetyFailure: Throwable? = null
        override fun safeHardware() {
            safetyCalls++
            tuningManager?.publishMetadataAndValues() // Disk teardown must come after neutralization.
            super.safeHardware()
            safetyFailure?.let { throw it }
        }
    }

    @Test fun `replacement and shutdown close exactly the owned tuning manager`() {
        val robot = ProbeRobot(); val first = manager(); val second = manager()
        try {
            robot.tuningManager = first
            robot.tuningManager = first
            first.publishMetadataAndValues()
            robot.tuningManager = second
            assertThrows(IllegalStateException::class.java) { first.publishMetadataAndValues() }
            second.publishMetadataAndValues()
        } finally { robot.close() }
        assertThrows(IllegalStateException::class.java) { second.publishMetadataAndValues() }
        assertThrows(IllegalStateException::class.java) { robot.tuningManager = null }
        robot.close()
        assertEquals(1, robot.safetyCalls)
    }

    @Test fun `failed safety still releases hardware and closes tuning afterward`() {
        val robot = ProbeRobot(); val tuning = manager(); val failure = IllegalStateException("safety failed")
        robot.tuningManager = tuning
        robot.mecanumIO.setMotorPowers(0.6, 0.6, 0.6, 0.6)
        assertTrue(robot.motors.all { it.power != 0.0 })
        var hardwareClosed = false
        robot.hardwareRegistry.registerCloseable(AutoCloseable {
            tuning.publishMetadataAndValues()
            hardwareClosed = true
        })
        robot.safetyFailure = failure
        assertSame(failure, assertThrows(IllegalStateException::class.java) { robot.close() })
        assertTrue(hardwareClosed)
        assertTrue(robot.motors.all { it.power == 0.0 })
        assertThrows(IllegalStateException::class.java) { tuning.publishMetadataAndValues() }
        robot.close()
        assertEquals(1, robot.safetyCalls)
    }
}
