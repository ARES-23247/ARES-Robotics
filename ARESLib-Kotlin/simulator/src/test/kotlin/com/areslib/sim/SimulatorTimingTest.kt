package com.areslib.sim

import org.dyn4j.dynamics.Body
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.geometry.Vector2
import org.dyn4j.world.World
import org.junit.Assert.assertEquals
import org.junit.Test

class SimulatorTimingTest {
    @Test
    fun `fifty fixed simulation frames integrate exactly one second`() {
        val world = World<Body>()
        world.gravity = Vector2(0.0, 0.0)
        val body = Body().apply {
            addFixture(Geometry.createCircle(0.1))
            setMass(MassType.NORMAL)
            linearDamping = 0.0
            linearVelocity = Vector2(1.0, 0.0)
        }
        world.addBody(body)

        repeat(50) { world.step(1, DesktopSimLauncher.SIM_TIMESTEP_SECONDS) }

        assertEquals(1.0, body.transform.translationX, 1e-6)
        assertEquals(0.0, body.transform.translationY, 1e-9)
    }

    @Test
    fun `runner is the sole owner of one twenty millisecond pace per frame`() {
        var hostNanos = 0L
        val pacer = SimFramePacer(SimHostClock { hostNanos }, SimHostWait { hostNanos += it })
        com.areslib.util.RobotClock.useMockTime(0L)
        try {
            repeat(20) {
                hostNanos += 3_000_000L // Representative work must consume the frame budget.
                DesktopSimLauncher.paceFrame(pacer)
            }

            assertEquals(400_000_000L, hostNanos)
            assertEquals(400L, com.areslib.util.RobotClock.currentTimeMillis())
        } finally {
            com.areslib.util.RobotClock.useSystemTime()
        }
    }
}
