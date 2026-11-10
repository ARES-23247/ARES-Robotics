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
        assertEquals(0.1651/0.0254, tag.tagsize, 1e-9)
        assertEquals(org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.INCH, tag.distanceUnit)
        assertEquals((-1.4/0.0254).toFloat(), tag.fieldPosition[0], 1e-5f)
        assertEquals((-1.3/0.0254).toFloat(), tag.fieldPosition[1], 1e-5f)
        assertEquals((0.75/0.0254).toFloat(), tag.fieldPosition[2], 1e-5f)
        assertEquals(AprilTagProcessor.TagFamily.TAG_36h11, processorBuilder.tagFamily)
    }

    @Test
    fun `canonical outward tag normal maps to the SDK optical inward Z axis`() {
        val metadata = FtcAprilTagLibraryFactory.create(RobotFieldConfig(
            apriltags=listOf(RobotFieldAprilTag(id=1, family="36h11",sizeMeters=0.1651)),
        ),FtcTagPoseConvention.WPILIB_NWU).lookupTag(1)!!
        val q = metadata.fieldOrientation
        assertEquals(-1.0, (2*(q.x*q.z+q.w*q.y)).toDouble(), 1e-6)
        assertEquals(0.0, (2*(q.y*q.z-q.w*q.x)).toDouble(), 1e-6)
        assertEquals(0.0, (1-2*(q.x*q.x+q.y*q.y)).toDouble(), 1e-6)
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
