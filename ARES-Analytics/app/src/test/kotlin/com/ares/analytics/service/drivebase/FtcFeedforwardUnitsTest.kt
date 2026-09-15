package com.ares.analytics.service.drivebase

import com.ares.analytics.shared.models.League
import kotlin.test.Test
import kotlin.test.assertEquals

class FtcFeedforwardUnitsTest {
    @Test
    fun `new FTC model uses normalized gains consistent with declared speed`() {
        val document = defaultDrivebase("units-test", DrivebaseKind.FTC_MECANUM, League.FTC).toCanonicalDrivebase()
        val parameters = document.parameters.associateBy { it.key }
        val kv = parameters.getValue("drive.feedforwardKv")
        assertEquals(document.geometry.maxLinearSpeedMetersPerSecond, 1.0 / kv.defaultValue.doubleValue!!, 1e-12)
        assertEquals("normalized/(m/s)", kv.unit)
        assertEquals("normalized/(m/s^2)", parameters.getValue("drive.feedforwardKa").unit)
        val ks = parameters.getValue("drive.feedforwardKs")
        assertEquals("normalized", ks.unit)
        assertEquals(1.0, ks.maximum)
    }
}
