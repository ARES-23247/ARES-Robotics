package com.areslib.ftc.drivetrain

import com.areslib.Store
import com.areslib.ftc.MockDcMotorEx
import com.areslib.ftc.telemetry.FtcTelemetryManager
import com.areslib.hardware.HardwareRegistry
import com.areslib.pathing.FieldWaypoint
import com.areslib.pathing.FieldWaypointLoader
import com.areslib.subsystem.DriveSubsystem
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.HardwareMap
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.*

class MecanumWaypointAllocationAuditTest {
    @Test fun `inactive and held terminal waypoint requests reuse cached data`() {
        // Seed only the owned test process's singleton cache; never create robot deployment files.
        val cacheField = FieldWaypointLoader::class.java.getDeclaredField("cache").apply { isAccessible = true }
        val cache = cacheField.get(FieldWaypointLoader)
        val snapshotField = cache.javaClass.getDeclaredField("snapshot").apply { isAccessible = true }
        val originalSnapshot = snapshotField.get(cache)
        val registry = HardwareRegistry()
        val store = Store()
        val follower = MecanumTrajectoryFollower(DriveSubsystem(store))
        val hardware = MecanumHardwareIO(object : HardwareMap() {
            private val motors = Array(4) { MockDcMotorEx() }
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T =
                motors[listOf("fl", "fr", "rl", "rr").indexOf(deviceName)] as T
            override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
        }, registry)
        val telemetry = FtcTelemetryManager(store, registry)
        try {
            // This test needs only its text map; release background/logging ownership before measurement.
            telemetry.close()
            RobotClock.useMockTime(1000L)
            snapshotField.set(cache, null)
            follower.driveToWaypoint(store, hardware, telemetry, "Inactive", false)
            assertNull(snapshotField.get(cache), "an inactive request must not resolve any waypoint file")
            val originalWaypoint = FieldWaypoint("far", "Far", 100.0, 100.0, 0.0)
            snapshotField.set(cache, mapOf("Far" to originalWaypoint))
            follower.driveToWaypoint(store, hardware, telemetry, "Far", true, false)
            assertNull(follower.activePathfindTask)
            val poseField = MecanumTrajectoryFollower::class.java.getDeclaredField("cachedWaypointPose")
                .apply { isAccessible = true }
            val originalPose = poseField.get(follower)
            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            bean.isThreadAllocatedMemoryEnabled = true
            val thread = Thread.currentThread().id
            for (name in listOf("Far", "Missing")) {
                repeat(50_000) { follower.driveToWaypoint(store, hardware, telemetry, name, true, false) }
                repeat(2) {
                    val before = bean.getThreadAllocatedBytes(thread)
                    repeat(10_000) { follower.driveToWaypoint(store, hardware, telemetry, name, true, false) }
                    assertEquals(0L, bean.getThreadAllocatedBytes(thread) - before, name)
                }
            }
            assertSame(originalPose, poseField.get(follower))
            assertEquals("Waypoint 'Missing' not found!", telemetry.customDriverStationText["Error"])
            snapshotField.set(cache, mapOf("Far" to originalWaypoint.copy(x = 101.0)))
            follower.driveToWaypoint(store, hardware, telemetry, "Far", true, false)
            assertNotSame(originalPose, poseField.get(follower), "an explicit reload must refresh the converted pose")
        } finally {
            try { telemetry.close() }
            finally {
                try { registry.closeAll() }
                finally {
                    registry.clear()
                    snapshotField.set(cache, originalSnapshot)
                    RobotClock.useSystemTime()
                }
            }
        }
    }
}
