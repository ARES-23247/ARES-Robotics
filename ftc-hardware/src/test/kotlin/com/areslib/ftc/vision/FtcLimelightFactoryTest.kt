package com.areslib.ftc.vision

import com.areslib.ftc.config.RobotConfig
import com.areslib.ftc.hardware.FtcHardwareMapInitializer
import com.qualcomm.hardware.limelightvision.Limelight3A
import com.qualcomm.robotcore.hardware.HardwareMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FtcLimelightFactoryTest {
    @Test
    fun `hardware map initializer stops earlier camera when later lookup fails`() {
        val front = RecordingLimelight()
        val hardwareMap = LimelightHardwareMap(mapOf("front" to front))

        val result = FtcHardwareMapInitializer.initLimelight(hardwareMap, "front, missing")

        assertNull(result)
        assertEquals(1, front.startCount)
        assertEquals(1, front.stopCount)
    }

    @Test
    fun `RobotConfig stops earlier camera and propagates later lookup failure`() {
        val front = RecordingLimelight()
        val hardwareMap = LimelightHardwareMap(mapOf("front" to front))

        assertThrows(IllegalArgumentException::class.java) {
            RobotConfig(hardwareMap).getLimelight("front, missing")
        }

        assertEquals(1, front.startCount)
        assertEquals(1, front.stopCount)
    }

    private class LimelightHardwareMap(
        private val cameras: Map<String, Limelight3A>
    ) : HardwareMap() {
        override fun <T> get(classOrType: Class<out T>, deviceName: String): T {
            if (classOrType != Limelight3A::class.java) {
                throw IllegalArgumentException("Unsupported hardware type ${classOrType.name}")
            }
            val camera = cameras[deviceName]
                ?: throw IllegalArgumentException("Missing Limelight '$deviceName'")
            @Suppress("UNCHECKED_CAST")
            return camera as T
        }
    }

    private class RecordingLimelight : Limelight3A() {
        var startCount = 0
        var stopCount = 0
        override fun start() { startCount++ }
        override fun stop() { stopCount++ }
    }
}
