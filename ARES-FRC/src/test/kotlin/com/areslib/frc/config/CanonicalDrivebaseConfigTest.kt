package com.areslib.frc.config

import com.areslib.frc.generated.drivebase.GeneratedAresDrivebaseConfig
import com.areslib.frc.generated.drivebase.GeneratedAresTuningConfig
import com.areslib.frc.sim.Dyn4jPhysicsWorld
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

class CanonicalDrivebaseConfigTest {
    @Test
    fun `runtime updates reject vendor metadata and unknown parameters`() {
        assertTrue(CanonicalDrivebaseConfig.supportsRuntimeParameter("frc.ares.path.velocity-scale"))
        assertFalse(CanonicalDrivebaseConfig.supportsRuntimeParameter("frc.vendor.drive-kp"))
        assertFalse(CanonicalDrivebaseConfig.supportsRuntimeParameter("future.unmapped.parameter"))
    }

    @Test
    fun `vendor source remains read only and hash pinned`() {
        val source = projectFile(GeneratedAresDrivebaseConfig.CTRE_VENDOR_SOURCE)
        val canonicalSource = source.readText().replace("\r\n", "\n")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(canonicalSource.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        assertEquals("frc.robot.generated.TunerConstants", GeneratedAresDrivebaseConfig.CTRE_CONSTANTS_CLASS)
        assertEquals("CAN2", GeneratedAresDrivebaseConfig.CTRE_CAN_BUS)
        assertEquals(GeneratedAresDrivebaseConfig.CTRE_VENDOR_SOURCE_SHA256, hash)
        assertEquals(5.2734375, GeneratedAresDrivebaseConfig.DRIVE_GEAR_RATIO, 0.0)
        assertEquals(26.09090909090909, GeneratedAresDrivebaseConfig.STEER_GEAR_RATIO, 0.0)
    }

    @Test
    fun `reviewed offset profile exactly matches deploy overlay`() {
        val text = projectFile("src/main/deploy/swerve_offsets.json").readText()
        val offsets = CanonicalDrivebaseConfig.profiledOffsets()

        assertEquals(readNumber(text, "frontLeft"), offsets.frontLeft, 0.0)
        assertEquals(readNumber(text, "frontRight"), offsets.frontRight, 0.0)
        assertEquals(readNumber(text, "backLeft"), offsets.backLeft, 0.0)
        assertEquals(readNumber(text, "backRight"), offsets.backRight, 0.0)
    }

    @Test
    fun `simulation consumes canonical dimensions and gains`() {
        val world = Dyn4jPhysicsWorld(
            CanonicalDrivebaseConfig.simulationRobotLengthMeters,
            CanonicalDrivebaseConfig.simulationRobotWidthMeters,
        )
        assertEquals(GeneratedAresTuningConfig.Parameters.SIMULATION_ROBOTLENGTHMETERS, world.robotLengthMeters, 0.0)
        assertEquals(GeneratedAresTuningConfig.Parameters.SIMULATION_ROBOTWIDTHMETERS, world.robotWidthMeters, 0.0)
        assertTrue(CanonicalDrivebaseConfig.simulationLinearKp > 0.0)
        assertTrue(CanonicalDrivebaseConfig.simulationAngularKp > 0.0)
    }

    private fun projectFile(relative: String): File = listOf(File(relative), File("../ARES-FRC/$relative"))
        .firstOrNull(File::isFile) ?: error("Missing project file $relative")

    private fun readNumber(json: String, key: String): Double =
        requireNotNull(Regex("\\\"$key\\\"\\s*:\\s*(-?[0-9.]+)").find(json)).groupValues[1].toDouble()
}
