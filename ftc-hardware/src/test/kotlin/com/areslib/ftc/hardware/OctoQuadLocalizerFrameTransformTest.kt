package com.areslib.ftc.hardware

import com.areslib.hardware.drive.OdometryInputs
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import kotlin.test.Test
import kotlin.test.assertEquals

class OctoQuadLocalizerFrameTransformTest {
    @Test
    fun `seeded pose transforms native position heading and velocity`() {
        val transform = OctoQuadLocalizerFrameTransform()
        transform.initialize(
            Pose2d(3.0, 4.0, Rotation2d(Math.PI / 2.0)),
            block(xMm = 1000, yMm = 2000, heading = 0.0f)
        )
        val inputs = OdometryInputs()

        transform.apply(
            block(xMm = 2000, yMm = 2000, heading = 0.25f, vxMmS = 1000, vyMmS = 0),
            inputs
        )

        assertEquals(3.0, inputs.posX, 1e-6)
        assertEquals(5.0, inputs.posY, 1e-6)
        assertEquals(Math.PI / 2.0 + 0.25, inputs.heading, 1e-6)
        assertEquals(0.0, inputs.velX, 1e-6)
        assertEquals(1.0, inputs.velY, 1e-6)
    }

    private fun block(
        xMm: Int,
        yMm: Int,
        heading: Float,
        vxMmS: Int = 0,
        vyMmS: Int = 0
    ) = OctoQuadFWv3.LocalizerDataBlock(
        posX_mm = xMm.toShort(),
        posY_mm = yMm.toShort(),
        heading_rad = heading,
        velX_mmS = vxMmS.toShort(),
        velY_mmS = vyMmS.toShort()
    )
}
