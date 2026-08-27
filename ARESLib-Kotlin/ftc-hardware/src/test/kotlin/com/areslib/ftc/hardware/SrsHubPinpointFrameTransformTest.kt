package com.areslib.ftc.hardware

import com.areslib.hardware.drive.OdometryInputs
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SrsHubPinpointFrameTransformTest {
    @Test
    fun `seeded pose transforms native position heading and velocity`() {
        val transform = SrsHubPinpointFrameTransform()
        transform.initialize(
            Pose2d(3.0, 4.0, Rotation2d(Math.PI / 2.0)),
            rawX = 1.0,
            rawY = 2.0,
            rawHeading = 0.0
        )
        val inputs = OdometryInputs()

        transform.apply(
            rawXMeters = 2.0,
            rawYMeters = 2.0,
            rawHeadingRadians = 0.25,
            rawVelocityXMps = 1.0,
            rawVelocityYMps = 0.0,
            rawHeadingVelocity = 0.5,
            inputs = inputs
        )

        assertEquals(3.0, inputs.posX, 1e-9)
        assertEquals(5.0, inputs.posY, 1e-9)
        assertEquals(Math.PI / 2.0 + 0.25, inputs.heading, 1e-9)
        assertEquals(0.0, inputs.velX, 1e-9)
        assertEquals(1.0, inputs.velY, 1e-9)
        assertEquals(0.5, inputs.headingVelocity, 1e-9)
    }

    @Test
    fun `nonfinite seed is rejected`() {
        val transform = SrsHubPinpointFrameTransform()
        assertFailsWith<IllegalArgumentException> {
            transform.initialize(Pose2d(), Double.NaN, 0.0, 0.0)
        }
    }
}
