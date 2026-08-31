package org.aresfirst.marvin.robot

import com.areslib.math.coordinate.AllianceMirroring
import com.areslib.math.coordinate.CoordinateTransformers
import com.areslib.math.coordinate.FieldSymmetry
import com.areslib.math.coordinate.FieldOrigin
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.wrapAngle
import com.areslib.pathing.Path
import com.areslib.pathing.PathEvent
import com.areslib.pathing.PathPoint
import com.areslib.state.Alliance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FRCAutoAllianceMirroringContractTest {

    @Test
    fun `red autonomous path reflects across alliance wall axis`() {
        val source = Path(
            points = listOf(
                PathPoint(
                    pose = Pose2d(2.0, 3.0, Rotation2d(0.4)),
                    velocityMps = 2.5,
                    distanceMeters = 1.25,
                    curvature = 0.20,
                    tangentRadians = 0.60
                )
            ),
            events = listOf(PathEvent("shooter.feedWhenReady", 1.25))
        )

        val mirrored = AllianceMirroring.mirror(
            source,
            Alliance.RED,
            FieldSymmetry.MIRRORED,
            fieldLength = CoordinateTransformers.FRC_FIELD_LENGTH,
            fieldWidth = CoordinateTransformers.FRC_FIELD_WIDTH,
            fieldOrigin = FieldOrigin.CORNER
        )
        val point = mirrored.points.single()

        assertEquals(CoordinateTransformers.FRC_FIELD_LENGTH - 2.0, point.pose.x, 1e-9)
        assertEquals(3.0, point.pose.y, 1e-9)
        assertEquals(wrapAngle(Math.PI - 0.4), point.pose.heading.radians, 1e-9)
        assertEquals(wrapAngle(Math.PI - 0.60), point.tangentRadians, 1e-9)
        assertEquals(-0.20, point.curvature, 1e-9)
        assertEquals(source.events, mirrored.events)
    }
}
