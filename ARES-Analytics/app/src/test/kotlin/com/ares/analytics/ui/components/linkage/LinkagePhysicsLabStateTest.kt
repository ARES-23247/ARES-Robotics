package com.ares.analytics.ui.components.linkage

import com.areslib.math.kinematics.TwoDofLinkageParameters
import com.areslib.math.kinematics.TwoDofLinkagePlant
import com.areslib.math.kinematics.TwoDofLinkagePlantParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkagePhysicsLabStateTest {
    private fun lab(torquePerVolt: Double = 1.0) = LinkagePhysicsLabState(TwoDofLinkagePlant(
        TwoDofLinkagePlantParameters(TwoDofLinkageParameters(0.4, 0.3, 1.0, 0.5), torquePerVolt, 1.0),
    ))

    @Test
    fun `preview advances only while running and reset neutralizes its controls`() {
        val lab = lab()
        lab.voltage1 = 5.0
        lab.advance()
        assertEquals(0.0, lab.theta1)
        lab.toggleRunning()
        lab.advance()
        assertTrue(lab.theta1 != 0.0)
        lab.toggleRunning()
        val paused = lab.theta1
        lab.advance()
        assertEquals(paused, lab.theta1)
        lab.reset()
        assertFalse(lab.running)
        assertEquals(0.0, lab.voltage1)
        assertEquals(0.0, lab.voltage2)
        assertEquals(0.0, lab.theta1)
        assertEquals(0.0, lab.theta2)
    }

    @Test
    fun `numerical failure stops neutralizes and requires explicit reset`() {
        val lab = lab(Double.MAX_VALUE)
        lab.voltage1 = 12.0
        lab.voltage2 = 2.0
        lab.toggleRunning()
        lab.advance()
        assertFalse(lab.running)
        assertNotNull(lab.fault)
        assertEquals(0.0, lab.voltage1)
        assertEquals(0.0, lab.voltage2)
        assertEquals(0.0, lab.theta1)
        assertEquals(0.0, lab.theta2)
        lab.toggleRunning()
        assertFalse(lab.running)
        lab.reset()
        assertNull(lab.fault)
        lab.toggleRunning()
        lab.advance() // Zero motor volts after reset; gravity remains representable.
        assertTrue(lab.running)
        assertNull(lab.fault)
    }
}
