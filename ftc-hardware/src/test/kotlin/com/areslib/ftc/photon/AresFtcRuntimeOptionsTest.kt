package com.areslib.ftc.photon

import com.qualcomm.robotcore.eventloop.opmode.OpMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AresFtcRuntimeOptionsTest {
    @Test
    fun `unconfigured OpMode uses supported SDK path`() {
        val options = resolveAresFtcRuntimeOptions(PlainOpMode())

        assertEquals(FtcHubCommandTransport.STANDARD_SDK, options.hubCommandTransport)
        assertFalse(options.limelightProxyEnabled)
    }

    @Test
    fun `generated provider explicitly selects Photon and proxy`() {
        val options = resolveAresFtcRuntimeOptions(ConfiguredOpMode())

        assertEquals(FtcHubCommandTransport.ARES_PHOTON, options.hubCommandTransport)
        assertEquals(true, options.limelightProxyEnabled)
    }

    private class PlainOpMode : OpMode() {
        override fun init() = Unit
        override fun loop() = Unit
    }

    private class ConfiguredOpMode : OpMode(), AresFtcRuntimeOptionsProvider {
        override val aresFtcRuntimeOptions = AresFtcRuntimeOptions(
            hubCommandTransport = FtcHubCommandTransport.ARES_PHOTON,
            limelightProxyEnabled = true,
        )

        override fun init() = Unit
        override fun loop() = Unit
    }
}
