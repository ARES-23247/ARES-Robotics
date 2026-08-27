package com.ares.analytics.viewmodel

import com.areslib.control.assist.SysIdMechanism
import kotlin.test.Test
import kotlin.test.assertEquals

class SysIdCapabilityTest {
    @Test
    fun `capability parser accepts known values and ignores unsafe unknown values`() {
        assertEquals(
            setOf(SysIdMechanism.LINEAR, SysIdMechanism.ANGULAR, SysIdMechanism.FLYWHEEL),
            parseSupportedSysIdMechanisms(" linear,ANGULAR;flywheel,arbitrary-kotlin "),
        )
    }

    @Test
    fun `empty advertisement is known no support`() {
        assertEquals(emptySet(), parseSupportedSysIdMechanisms(""))
    }
}
