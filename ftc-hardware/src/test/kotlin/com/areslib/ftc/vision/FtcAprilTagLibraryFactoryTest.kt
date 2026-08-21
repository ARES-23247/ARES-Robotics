package com.areslib.ftc.vision

import com.areslib.state.FieldType
import com.areslib.state.RobotFieldAprilTag
import com.areslib.state.RobotFieldConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor

class FtcAprilTagLibraryFactoryTest {
    @Test
    fun `canonical FTC tag metadata becomes an SDK library`() {
        val field = RobotFieldConfig(
                id = "ftc",
                name = "FTC",
                fieldType = FieldType.FTC,
                widthMeters = 3.6576,
                heightMeters = 3.6576,
                apriltags = listOf(
                    RobotFieldAprilTag(
                        id = 20,
                        name = "Blue target",
                        family = "apriltag3_36h11_classic",
                        sizeMeters = 0.1651,
                        x = -1.4,
                        y = -1.3,
                        z = 0.75,
                        roll = -90.0,
                        yaw = 144.0,
                    )
                ),
            )
        val library = FtcAprilTagLibraryFactory.create(field)
        val processorBuilder = AprilTagProcessor.Builder()
        FtcAprilTagLibraryFactory.configure(processorBuilder, field)

        val tag = requireNotNull(library.lookupTag(20))
        assertEquals("Blue target", tag.name)
        assertEquals(0.1651, tag.tagsize, 1e-9)
        assertEquals(-1.4f, tag.fieldPosition[0], 1e-6f)
        assertEquals(-1.3f, tag.fieldPosition[1], 1e-6f)
        assertEquals(0.75f, tag.fieldPosition[2], 1e-6f)
        assertEquals(AprilTagProcessor.TagFamily.TAG_36h11, processorBuilder.tagFamily)
    }

    @Test
    fun `missing FTC family and size fail before camera initialization`() {
        assertThrows(IllegalArgumentException::class.java) {
            FtcAprilTagLibraryFactory.create(
                RobotFieldConfig(
                    id = "bad",
                    name = "Bad",
                    fieldType = FieldType.FTC,
                    widthMeters = 3.6576,
                    heightMeters = 3.6576,
                    apriltags = listOf(RobotFieldAprilTag(id = 1)),
                )
            )
        }
    }
}
