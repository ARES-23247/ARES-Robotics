package com.ares.analytics.ui.components.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MecanumVisualizerTest {
    @Test
    fun `detail labels distinguish commanded output encoder velocity and current`() {
        assertEquals(
            "FL: out +0.50 | 1234 ticks/s | 1.26 A",
            mecanumDetail("FL", power = 0.5, velocityTicksPerSecond = 1_234.4, currentAmps = 1.256),
        )
    }

    @Test
    fun `straight forward points toward top of robot card`() {
        val vector = mecanumNetScreenVector(fl = 1.0, fr = 1.0, rl = 1.0, rr = 1.0)

        assertClose(0.0, vector.x)
        assertClose(-1.0, vector.y)
    }

    @Test
    fun `straight backward points toward bottom of robot card`() {
        val vector = mecanumNetScreenVector(fl = -1.0, fr = -1.0, rl = -1.0, rr = -1.0)

        assertClose(0.0, vector.x)
        assertClose(1.0, vector.y)
    }

    @Test
    fun `left and right strafe match the robot-relative card`() {
        val left = mecanumNetScreenVector(fl = -1.0, fr = 1.0, rl = 1.0, rr = -1.0)
        val right = mecanumNetScreenVector(fl = 1.0, fr = -1.0, rl = -1.0, rr = 1.0)

        assertClose(-1.0, left.x)
        assertClose(0.0, left.y)
        assertClose(1.0, right.x)
        assertClose(0.0, right.y)
    }

    @Test
    fun `pure rotation has no translational net vector`() {
        val vector = mecanumNetScreenVector(fl = -1.0, fr = 1.0, rl = -1.0, rr = 1.0)

        assertClose(0.0, vector.x)
        assertClose(0.0, vector.y)
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue(kotlin.math.abs(expected - actual) < 1e-9, "Expected $expected but was $actual")
    }
}
