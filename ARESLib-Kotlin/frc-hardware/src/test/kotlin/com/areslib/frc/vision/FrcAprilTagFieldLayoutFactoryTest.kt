package com.areslib.frc.vision

import com.areslib.state.FieldType
import com.areslib.state.RobotFieldAprilTag
import com.areslib.state.RobotFieldConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FrcAprilTagFieldLayoutFactoryTest {
    @Test
    fun `canonical FRC map becomes a WPILib layout without losing pose`() {
        val layout = FrcAprilTagFieldLayoutFactory.create(
            RobotFieldConfig(
                id = "test",
                name = "Test",
                fieldType = FieldType.FRC,
                widthMeters = 16.541,
                heightMeters = 8.211,
                apriltags = listOf(
                    RobotFieldAprilTag(
                        id = 7,
                        x = 1.2,
                        y = 2.3,
                        z = 1.4,
                        roll = 10.0,
                        pitch = -20.0,
                        yaw = 135.0,
                    )
                ),
            )
        )

        val pose = layout.getTagPose(7).orElseThrow()
        assertEquals(16.541, layout.fieldLength, 1e-9)
        assertEquals(8.211, layout.fieldWidth, 1e-9)
        assertEquals(1.2, pose.x, 1e-9)
        assertEquals(2.3, pose.y, 1e-9)
        assertEquals(1.4, pose.z, 1e-9)
        assertEquals(Math.toRadians(10.0), pose.rotation.x, 1e-9)
        assertEquals(Math.toRadians(-20.0), pose.rotation.y, 1e-9)
        assertEquals(Math.toRadians(135.0), pose.rotation.z, 1e-9)
    }

    @Test
    fun `FTC documents cannot silently enter the WPILib boundary`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrcAprilTagFieldLayoutFactory.create(
                RobotFieldConfig(
                    id = "ftc",
                    name = "FTC",
                    fieldType = FieldType.FTC,
                    apriltags = listOf(RobotFieldAprilTag(id = 1)),
                )
            )
        }
    }
}
