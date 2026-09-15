package org.aresfirst.marvin.sim

import com.areslib.sim.field.SimGamePieceBodyFactory
import com.areslib.state.DriveState
import com.areslib.state.RobotState
import com.areslib.telemetry.ITelemetry
import com.areslib.telemetry.TelemetryTopicConstants
import org.aresfirst.marvin.FlyingBall
import org.dyn4j.dynamics.Body
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SimTelemetryPublisherAuditTest {
    private open class Sink : ITelemetry {
        override fun putNumber(key: String, value: Double) {}
        override fun putBoolean(key: String, value: Boolean) {}
        override fun putString(key: String, value: String) {}
        override fun putDoubleArray(key: String, value: DoubleArray) {}
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
    }

    private class SnapshotSink : Sink() {
        val arrays = mutableMapOf<String, DoubleArray>()
        val numbers = mutableMapOf<String, Double>()
        override fun putDoubleArray(key: String, value: DoubleArray) { arrays[key] = value.copyOf() }
        override fun putNumber(key: String, value: Double) { numbers[key] = value }
        fun frame(key: String) = arrays.getValue(key)
    }

    private fun publish(
        publisher: Dyn4jSimTelemetryPublisher,
        sink: ITelemetry,
        state: RobotState = RobotState(),
        ground: List<Body> = emptyList(),
        flying: List<FlyingBall> = emptyList(),
    ) = publisher.publishVisualization(state, sink, 90.0, 0.0, -Math.PI / 2.0,
        ground, flying, 11.0, 12.0, -0.75)

    @Test fun `mechanism offsets and yaw pitch composition use odometry and correct angle units`() {
        val sink = SnapshotSink()
        val state = RobotState(drive = DriveState(odometryX = 2.0, odometryY = 3.0,
            odometryHeading = Math.PI / 2.0))
        publish(Dyn4jSimTelemetryPublisher(), sink, state)
        assertArrayEquals(doubleArrayOf(2.0, 3.35, 0.2, 0.5, -0.5, 0.5, 0.5),
            sink.frame("Robot/Superstructure/3D/Intake"), 1e-12)
        val halfRoot = Math.sqrt(0.5)
        assertArrayEquals(doubleArrayOf(2.0, 2.8, 0.6, halfRoot, 0.0, 0.0, halfRoot),
            sink.frame("Robot/Superstructure/3D/Cowl"), 1e-12)
        assertArrayEquals(doubleArrayOf(2.0, 2.9, 0.6, 0.5, 0.5, -0.5, 0.5),
            sink.frame("Robot/Superstructure/3D/Flywheel"), 1e-12)
    }

    @Test fun `truth estimator and odometry remain distinct in scalar and atomic frames`() {
        val initial = RobotState()
        val state = initial.copy(drive = initial.drive.copy(odometryX = 2.0, odometryY = 3.0,
            odometryHeading = 0.25, poseEstimator = initial.drive.poseEstimator.copy(
                estimatedPoseX = 4.0, estimatedPoseY = 5.0, estimatedPoseHeading = 0.5)))
        val sink = SnapshotSink()
        val publisher = Dyn4jSimTelemetryPublisher()
        publish(publisher, sink, state)
        val first = sink.frame("ARES/SimulatorPoseFrame")
        assertArrayEquals(doubleArrayOf(11.0, 12.0, -0.75, 4.0, 5.0, 0.5, 2.0, 3.0, 0.25, 0.0), first)
        val keys = listOf("ARES/TruePose/0", "ARES/TruePose/1", "ARES/TruePose/2",
            TelemetryTopicConstants.ESTIMATED_POSE_X, TelemetryTopicConstants.ESTIMATED_POSE_Y,
            TelemetryTopicConstants.ESTIMATED_POSE_HEADING, TelemetryTopicConstants.DRIVE_ODOM_X,
            TelemetryTopicConstants.DRIVE_ODOM_Y, TelemetryTopicConstants.DRIVE_ODOM_HEADING)
        keys.forEachIndexed { i, key -> assertEquals(first[i], sink.numbers.getValue(key)) }
        publish(publisher, sink)
        assertEquals(1.0, sink.frame("ARES/SimulatorPoseFrame")[9])
        assertEquals(0.0, first[9], "A telemetry snapshot must survive later publication")
        assertEquals(4.0, first[3])
    }

    @Test fun `ground and flying records retain identity geometry and quaternion ordering`() {
        val groundMetadata = SimGamePieceBodyFactory.fallback("ground-a", "ground-type", 0.24)
        val flyingMetadata = SimGamePieceBodyFactory.fallback("flying-b", "flying-type", 0.32)
        val body = SimGamePieceBodyFactory.createBody(groundMetadata, 1.0, 2.0, Math.PI / 2.0)
        val flying = FlyingBall(3.0, 4.0, 5.0, 0.0, 0.0, 0.0, flyingMetadata)
        val sink = SnapshotSink()
        publish(Dyn4jSimTelemetryPublisher(), sink, ground = listOf(body), flying = listOf(flying))
        val fuel = sink.frame("Robot/FuelPoses")
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 0.0635, Math.sqrt(0.5), 0.0, 0.0, Math.sqrt(0.5)),
            fuel.copyOfRange(0, 7), 1e-12)
        assertArrayEquals(doubleArrayOf(3.0, 4.0, 5.0, 1.0, 0.0, 0.0, 0.0), fuel.copyOfRange(7, 14))
        val frame = sink.frame(TelemetryTopicConstants.GAME_PIECES_FRAME)
        assertEquals(21, frame.size)
        assertEquals(2.0, frame[0])
        assertEquals(2.0, frame[1])
        listOf(groundMetadata, flyingMetadata).forEachIndexed { i, metadata ->
            val offset = 2 + i * 9
            assertArrayEquals(doubleArrayOf(metadata.instanceKey.toDouble(), metadata.typeKey.toDouble(),
                1.0 + 2.0 * i, 2.0 + 2.0 * i, if (i == 0) Math.PI / 2.0 else 0.0,
                metadata.widthMeters, metadata.heightMeters, metadata.shapeCode.toDouble(),
                metadata.colorRgb.toDouble()), frame.copyOfRange(offset, offset + 9), 1e-12)
        }
    }

    @Test fun `growth shrinkage and empty frames remove stale legacy poses and preserve exact typed size`() {
        val publisher = Dyn4jSimTelemetryPublisher()
        val sink = SnapshotSink()
        var capacity = 700
        // Cross both the initial capacity and its growth allowance, then repeatedly shrink/regrow.
        for (count in listOf(0, 2, 101, 1, 0, 111, 112, 0, 3, 3, 1, 0)) {
            val flying = List(count) { i -> FlyingBall(i + 1.0, 2.0, 3.0, 0.0, 0.0, 0.0) }
            publish(publisher, sink, flying = flying)
            val fuel = sink.frame("Robot/FuelPoses")
            assertTrue(fuel.size >= capacity)
            assertTrue(fuel.size >= count * 7)
            capacity = fuel.size
            for (i in 0 until count) assertArrayEquals(
                doubleArrayOf(i + 1.0, 2.0, 3.0, 1.0, 0.0, 0.0, 0.0), fuel.copyOfRange(i * 7, (i + 1) * 7))
            assertTrue((count * 7 until fuel.size).all { fuel[it] == 0.0 }, "Stale tail after count=$count")
            val typed = sink.frame(TelemetryTopicConstants.GAME_PIECES_FRAME)
            assertEquals(3 + 9 * count, typed.size)
            assertEquals(count.toDouble(), typed[1])
        }
    }

    @Test fun `both sequence counters wrap without exceeding exact double integers`() {
        val publisher = Dyn4jSimTelemetryPublisher()
        val sink = SnapshotSink()
        for (name in listOf("gamePieceSequence", "simulatorPoseSequence")) {
            publisher.javaClass.getDeclaredField(name).apply { isAccessible = true }
                .setLong(publisher, 9_007_199_254_740_990L)
        }
        for (expected in listOf(9_007_199_254_740_990.0, 9_007_199_254_740_991.0, 0.0, 1.0)) {
            publish(publisher, sink)
            assertEquals(expected, sink.frame("ARES/SimulatorPoseFrame").last())
            assertEquals(expected, sink.frame(TelemetryTopicConstants.GAME_PIECES_FRAME).last())
        }
    }

    @Test fun `steady count publishing stays allocation free after warmup`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        org.junit.jupiter.api.Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val publisher = Dyn4jSimTelemetryPublisher()
        val sink = Sink() // Consumes synchronously; transport serialization is outside this measurement.
        val state = RobotState()
        val ground = listOf(SimGamePieceBodyFactory.createBody(SimGamePieceBodyFactory.fallback("stable"), 1.0, 2.0))
        val flying = listOf(FlyingBall(3.0, 4.0, 5.0, 0.0, 0.0, 0.0))
        repeat(30_000) { publish(publisher, sink, state, ground, flying) }
        val thread = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(100_000) { publish(publisher, sink, state, ground, flying) }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        println("Steady telemetry publisher: $allocated bytes / 100000 frames")
        assertTrue(allocated <= 4096, "Unexpected per-frame allocation: $allocated bytes")
    }
}
