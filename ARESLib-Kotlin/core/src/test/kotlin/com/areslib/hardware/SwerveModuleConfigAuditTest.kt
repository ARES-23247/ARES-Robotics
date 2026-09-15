package com.areslib.hardware

import com.areslib.drivetrain.SwerveModuleConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SwerveModuleConfigAuditTest {
    private fun config(id: String = "drive") = SwerveModuleConfig("front", id, "steer", "encoder", 0.2, -0.3)

    @Test
    fun `malformed CAN identities never alias device zero`() {
        for (id in listOf("drive", "1.0", "2147483648", "-1")) {
            assertThrows(IllegalArgumentException::class.java) { config(id).driveCanId }
        }
    }

    @Test
    fun `nonfinite configuration is rejected at construction and copy`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { config().copy(positionXMeters = bad) }
            assertThrows(IllegalArgumentException::class.java) { config().copy(positionYMeters = bad) }
            assertThrows(IllegalArgumentException::class.java) { config().copy(offsetRotations = bad) }
        }
    }

    @Test
    fun `blank identities and negative integer identifiers reject before hardware setup`() {
        for (blank in listOf("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException::class.java) { config().copy(name = blank) }
            assertThrows(IllegalArgumentException::class.java) { config().copy(driveId = blank) }
            assertThrows(IllegalArgumentException::class.java) { config().copy(steerId = blank) }
            assertThrows(IllegalArgumentException::class.java) { config().copy(encoderId = blank) }
        }
        for (index in 0 until 3) {
            val ids = intArrayOf(0, 1, 2); ids[index] = -1
            assertThrows(IllegalArgumentException::class.java) {
                SwerveModuleConfig("front", ids[0], ids[1], ids[2], 0.0, 0.0)
            }
        }
    }

    @Test
    fun `hardware names are verbatim while explicit numeric zero remains valid`() {
        val named = config("Drive Front Left")
        assertEquals("Drive Front Left", named.driveId)
        assertThrows(IllegalArgumentException::class.java) { named.steerCanId }
        assertThrows(IllegalArgumentException::class.java) { named.encoderCanId }
        val numeric = SwerveModuleConfig("front", 0, 12, 31, 0.2, -0.3, true, false, true, -0.25)
        assertEquals(0, numeric.driveCanId)
        assertEquals(12, numeric.steerCanId)
        assertEquals(31, numeric.encoderCanId)
        assertEquals(numeric, numeric.copy())
        assertTrue(numeric.driveInverted && numeric.encoderInverted && !numeric.steerInverted)
        assertEquals(-0.25, numeric.offsetRotations)
        assertEquals(42, numeric.copy(driveId = "42").driveCanId)
    }
}
