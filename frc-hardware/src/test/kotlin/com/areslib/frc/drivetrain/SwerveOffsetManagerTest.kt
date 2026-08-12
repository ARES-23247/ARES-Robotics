package com.areslib.frc.drivetrain

import com.areslib.drivetrain.SwerveOffsetData
import com.areslib.drivetrain.SwerveOffsetManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SwerveOffsetManagerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun testJsonSerializationAndDeserialization() {
        val original = SwerveOffsetData(
            frontLeft = -0.3549804,
            frontRight = -0.2229003,
            backLeft = 0.3632812,
            backRight = 0.4428710
        )

        val json = original.toJsonString()
        val parsed = SwerveOffsetData.fromJsonString(json)

        assertEquals(original.frontLeft, parsed.frontLeft, 1e-4)
        assertEquals(original.frontRight, parsed.frontRight, 1e-4)
        assertEquals(original.backLeft, parsed.backLeft, 1e-4)
        assertEquals(original.backRight, parsed.backRight, 1e-4)
    }

    @Test
    fun testSaveAndLoadRuntimeOffsets() {
        val testData = SwerveOffsetData(
            frontLeft = 0.1234,
            frontRight = 0.5678,
            backLeft = -0.4321,
            backRight = -0.8765
        )

        System.setProperty(SwerveOffsetManager.STORAGE_ROOT_PROPERTY, tempDir.toString())
        try {
            SwerveOffsetManager.saveRuntimeOffsets(testData)
            val loaded = SwerveOffsetManager.loadOffsets()

            assertEquals(testData.frontLeft, loaded.frontLeft, 1e-4)
            assertEquals(testData.frontRight, loaded.frontRight, 1e-4)
            assertEquals(testData.backLeft, loaded.backLeft, 1e-4)
            assertEquals(testData.backRight, loaded.backRight, 1e-4)
            assertTrue(SwerveOffsetManager.runtimeFile.isFile)
            assertTrue(SwerveOffsetManager.backupsDir.listFiles().orEmpty().isNotEmpty())
            assertFalse(tempDir.toFile().walkTopDown().any { it.name.endsWith(".tmp") })
        } finally {
            System.clearProperty(SwerveOffsetManager.STORAGE_ROOT_PROPERTY)
        }
    }

    @Test
    fun `strict parser rejects missing extra duplicate nonnumeric and nonfinite fields`() {
        val invalid = listOf(
            """{"frontLeft":0,"frontRight":0,"backLeft":0}""",
            """{"frontLeft":0,"frontRight":0,"backLeft":0,"backRight":0,"legacy":0}""",
            """{"frontLeft":0,"frontLeft":1,"frontRight":0,"backLeft":0,"backRight":0}""",
            """{"frontLeft":"0","frontRight":0,"backLeft":0,"backRight":0}""",
            """{"frontLeft":NaN,"frontRight":0,"backLeft":0,"backRight":0}"""
        )
        invalid.forEach { json ->
            assertThrows(IllegalArgumentException::class.java) {
                SwerveOffsetData.fromJsonString(json)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SwerveOffsetData(frontLeft = Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `corrupt runtime falls through to strict deployed offsets`() {
        val deployed = SwerveOffsetData(0.1, 0.2, 0.3, 0.4)
        System.setProperty(SwerveOffsetManager.STORAGE_ROOT_PROPERTY, tempDir.toString())
        try {
            SwerveOffsetManager.runtimeFile.writeText("""{"frontLeft":0}""")
            SwerveOffsetManager.deployFile.parentFile.mkdirs()
            SwerveOffsetManager.deployFile.writeText(deployed.toJsonString())
            assertEquals(deployed, SwerveOffsetManager.loadOffsets())
        } finally {
            System.clearProperty(SwerveOffsetManager.STORAGE_ROOT_PROPERTY)
        }
    }
}
