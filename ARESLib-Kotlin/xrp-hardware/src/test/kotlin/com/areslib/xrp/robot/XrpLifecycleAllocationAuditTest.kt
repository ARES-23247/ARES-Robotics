package com.areslib.xrp.robot

import com.areslib.xrp.hardware.XrpLineSensorDouble
import com.areslib.xrp.hardware.XrpServoDouble
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XrpLifecycleAllocationAuditTest {
    @Test fun `lifecycle refresh and valid double commands allocate no periodic storage`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!; bean.isThreadAllocatedMemoryEnabled = true
        val sensor = XrpLineSensorDouble()
        val robot = XrpBaseRobot(lineSensor = sensor)
        val servo = XrpServoDouble(1)
        var iteration = 0
        var checksum = 0.0
        fun tick() {
            val left = iteration++ % 2 == 0
            robot.onStartTeleop()
            robot.drivetrain.setPowers(if (left) 0.3 else 0.7, 0.5)
            sensor.leftReflectance = if (left) 0.3 else 0.7
            sensor.rightReflectance = if (left) 0.7 else 0.3
            servo.positionNormalized = if (left) 0.2 else 0.8
            servo.update()
            robot.periodic()
            checksum += robot.drivetrain.leftMotor.positionRadians + servo.positionNormalized
            if (sensor.isLeftOnLine || sensor.isRightOnLine) checksum += 1.0
            robot.onStop()
            robot.periodic()
        }
        repeat(50_000) { tick() }
        val thread = Thread.currentThread().id
        var consecutive = 0
        var bytes = -1L
        for (window in 0 until 10) {
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(10_000) { tick() }
            bytes = bean.getThreadAllocatedBytes(thread) - before
            consecutive = if (bytes == 0L) consecutive + 1 else 0
            if (consecutive == 2) break
        }
        assertEquals(2, consecutive, "Last window allocated $bytes bytes")
        assertTrue(checksum.isFinite() && checksum > 0.0)
        assertEquals(XrpRobotMode.DISABLED, robot.mode)
        assertEquals(0.0, robot.drivetrain.leftMotor.effort)
        assertEquals(0.0, robot.drivetrain.rightMotor.effort)
    }
}
