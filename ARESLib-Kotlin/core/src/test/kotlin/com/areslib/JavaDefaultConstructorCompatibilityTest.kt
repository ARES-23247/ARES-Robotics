package com.areslib

import com.areslib.kinematics.SwerveModuleState
import com.areslib.math.geometry.Pose2d
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercise the actual public JVM no-arg entry points, rather than Kotlin default-argument calls. */
class JavaDefaultConstructorCompatibilityTest {
    @Test
    fun `Java pose constructor retains neutral defaults`() {
        val pose = Pose2d::class.java.getConstructor().newInstance()
        assertEquals(0.0, pose.x)
        assertEquals(0.0, pose.y)
        assertEquals(0.0, pose.heading.radians)
    }

    @Test
    fun `Java module constructor retains neutral defaults`() {
        val module = SwerveModuleState::class.java.getConstructor().newInstance()
        assertEquals(0.0, module.speedMetersPerSecond)
        assertEquals(0.0, module.angle.radians)
    }
}
