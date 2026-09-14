package org.aresfirst.starter.frc

import com.areslib.state.DriveState
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldObstacle
import com.areslib.state.RobotFieldPoint
import com.areslib.state.RobotState
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class StarterDriveLoopAllocationTest {
    @Test
    fun `configured chassis loop has no sustained per-frame allocation`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean?.isThreadAllocatedMemorySupported == true, "Requires JVM thread allocation accounting")
        bean!!
        val wasEnabled = bean.isThreadAllocatedMemoryEnabled
        bean.isThreadAllocatedMemoryEnabled = true
        try {
            val simulation = StarterDriveSimulation(startX = 2.0, startY = 2.0)
            simulation.configureField(RobotFieldConfig(fieldType = FieldType.FRC, widthMeters = 20.0, heightMeters = 20.0,
                obstacles = listOf(
                    RobotFieldObstacle(id = "box", x = 4.0, y = 4.0, rotation = 30.0),
                    RobotFieldObstacle(id = "disc", shape = "Circle", x = 8.0, y = 4.0),
                    RobotFieldObstacle(id = "polygon", shape = "polygon", points = listOf(
                        RobotFieldPoint(10.0, 4.0), RobotFieldPoint(11.0, 4.0), RobotFieldPoint(10.0, 5.0))),
                )))
            val command = RobotState(drive = DriveState(xVelocityMetersPerSecond = 0.01,
                angularVelocityRadiansPerSecond = 0.2, isFieldCentric = false))
            repeat(50_000) { simulation.step(command, 0.02, it * 20L) }
            val threadId = Thread.currentThread().id
            val before = bean.getThreadAllocatedBytes(threadId)
            val started = System.nanoTime()
            repeat(50_000) { simulation.step(command, 0.02, (it + 50_000) * 20L) }
            val elapsed = System.nanoTime() - started
            val allocated = bean.getThreadAllocatedBytes(threadId) - before
            println("Starter simulation probe: 50000 frames, $allocated allocated bytes, $elapsed elapsed nanoseconds")
            // Allow fixed JVM bookkeeping; a single ordinary object per tick exceeds this budget.
            assertTrue(allocated in 0..1024, "Unexpected sustained allocation: $allocated bytes over 50000 frames")
        } finally {
            bean.isThreadAllocatedMemoryEnabled = wasEnabled
        }
    }
}
