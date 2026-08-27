package org.aresfirst.starter.frc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StarterFieldContractTest {
    @Test
    fun `starter accepts an empty season map and a full WPILib pose`() {
        val empty = loadStarterFieldContract(
            """{"schemaVersion":2,"id":"choose-season","name":"Choose season","fieldType":"frc","widthMeters":16.5,"heightMeters":8.2,"apriltags":[]}""".toByteArray()
        )
        assertNotNull(empty)
        assertEquals(0, empty!!.aprilTagLayout.tags.size)

        val populated = loadStarterFieldContract(
            """{"schemaVersion":2,"id":"season","name":"Season","fieldType":"frc","widthMeters":16.5,"heightMeters":8.2,"apriltags":[{"id":3,"name":"Test","sizeMeters":0.1651,"x":1.0,"y":2.0,"z":1.3,"roll":5.0,"pitch":10.0,"yaw":90.0}]}""".toByteArray()
        )
        val pose = populated!!.aprilTagLayout.getTagPose(3).orElseThrow()
        assertEquals(1.0, pose.x, 1e-9)
        assertEquals(Math.toRadians(90.0), pose.rotation.z, 1e-9)

        val defaultDimensions = loadStarterFieldContract(
            """{"schemaVersion":2,"id":"defaults","name":"Defaults","fieldType":"frc","apriltags":[]}""".toByteArray()
        )
        assertEquals(16.541, defaultDimensions!!.aprilTagLayout.fieldLength, 1e-9)
        assertEquals(8.211, defaultDimensions.aprilTagLayout.fieldWidth, 1e-9)
    }

    @Test
    fun `wrong league fails closed`() {
        val result = loadStarterFieldContract(
            """{"schemaVersion":2,"id":"wrong","name":"Wrong","fieldType":"ftc","widthMeters":3.6,"heightMeters":3.6,"apriltags":[]}""".toByteArray()
        )
        assertNull(result)
        assertEquals("Canonical season field must declare FRC geometry", StarterFieldContractLoader.error)
    }
}
