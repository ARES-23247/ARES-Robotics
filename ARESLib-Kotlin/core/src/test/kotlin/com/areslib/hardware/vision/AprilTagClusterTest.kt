package com.areslib.hardware.vision

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.reducer.rootReducer
import com.areslib.state.RobotState
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.*

class AprilTagClusterTest {
    private val members = listOf(-.2, -.07, .07, .2).mapIndexed { i, x -> AprilTagClusterMember(30 + i, x, -.18, .14) }
    private val cluster = AprilTagCluster("cell", members)

    @Test fun `reference trajectories remain correct for every nonempty visible subset`() {
        val rows = javaClass.getResourceAsStream("/vision/cluster-reference.csv")!!.bufferedReader().useLines {
            it.drop(1).map { row -> row.split(',').map(String::toDouble) }.toList()
        }
        val tracker = AprilTagClusterTracker(cluster)
        val output = ClusterTargetMeasurement()
        for (frame in rows.chunked(4)) for (mask in 1..15) {
            tracker.beginFrame()
            for (i in 0..3) if ((mask and (1 shl i)) != 0) {
                val r = frame[i]
                tracker.addTag(r[1].toInt(), r[2], r[3], r[4], r[5], r[6], r[7])
            }
            assertTrue(tracker.finishFrame(output, "front", 1000, 1000))
            assertEquals(frame[0][8], output.xMeters, 1e-10)
            assertEquals(frame[0][9], output.yMeters, 1e-10)
            assertEquals(frame[0][10], output.zMeters, 1e-10)
            assertEquals(Integer.bitCount(mask), output.contributingTags)
        }
    }

    @Test fun `conflicting tags require a strict majority and duplicate IDs cannot add confidence`() {
        val tracker = AprilTagClusterTracker(cluster)
        val output = ClusterTargetMeasurement()
        fun tag(index: Int, shift: Double = 0.0) {
            tracker.addTag(30 + index, -members[index].offsetX + shift, .18, 2.86, 0.0, 0.0, 0.0)
        }
        tracker.beginFrame(); tag(0); tag(1, 1.0)
        assertFalse(tracker.finishFrame(output, "front", 1000, 1000))
        tag(2)
        assertTrue(tracker.finishFrame(output, "front", 1000, 1000))
        assertEquals(2, output.contributingTags)
        assertEquals(3, output.visibleTags)
        assertEquals(0.0, output.xMeters, 1e-12)
        tracker.beginFrame(); tag(0); tag(0)
        assertFalse(tracker.finishFrame(output, "front", 1000, 1000))
        tracker.beginFrame()
        tracker.addTag(30, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        tracker.addTag(31, Double.NaN, 0.0, 3.0, 0.0, 0.0, 0.0)
        assertFalse(tracker.finishFrame(output, "front", 1000, 1000))
    }

    @Test fun `target actions do not modify EKF and retained state rejects stale future and mutable lists`() {
        val store = Store(RobotState(), ::rootReducer)
        val initial = store.state.drive.poseEstimator
        val target = ClusterTargetSnapshot("cell", "front", 1000, 1020, -.3, -.4, 3.0, 2, 3, .01)
        val targets = arrayListOf(target)
        store.dispatch(RobotAction.ClusterTargetsReceived(targets, 1020))
        targets.clear()
        assertEquals(listOf(target), store.state.vision.clusterTargets)
        assertSame(initial, store.state.drive.poseEstimator)
        assertTrue(target.bearingRadians > 0)
        assertTrue(target.elevationRadians > 0)
        assertTrue(target.isFresh(1250)); assertFalse(target.isFresh(1251)); assertFalse(target.isFresh(999))
        store.dispatch(RobotAction.ClusterTargetsReceived(listOf(target), 1251))
        assertTrue(store.state.vision.clusterTargets.isEmpty())
        assertSame(initial, store.state.drive.poseEstimator)
    }

    @Test fun `configuration rejects duplicate members and owns input collection`() {
        assertFailsWith<IllegalArgumentException> { AprilTagCluster("bad", listOf(members[0], members[0])) }
        assertFailsWith<IllegalArgumentException> { AprilTagCluster("bad", members, Double.NaN) }
        val source = ArrayList(members)
        val config = AprilTagCluster("owned", source)
        source.clear()
        assertEquals(4, config.members.size)
    }

    @Test fun `four cluster reconstruction stays within desktop budget without per-loop allocation`() {
        val trackers = Array(4) { AprilTagClusterTracker(cluster) }
        val outputs = Array(4) { ClusterTargetMeasurement() }
        val durations = LongArray(2000)
        fun update() {
            for (c in trackers.indices) {
                val t = trackers[c]; t.beginFrame()
                for (m in members) t.addTag(m.tagId, -m.offsetX, .18, 2.86, 0.0, 0.0, 0.0)
                check(t.finishFrame(outputs[c], "camera", 1000, 1000))
            }
        }
        repeat(100_000) { update() }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        for (i in durations.indices) { val start = System.nanoTime(); update(); durations[i] = System.nanoTime() - start }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        durations.sort()
        println("Cluster geometry desktop: p50=${durations[1000]}ns p95=${durations[1900]}ns p99=${durations[1980]}ns max=${durations.last()}ns; allocated=$allocated bytes/2000 frames; incremental budget=1ms, robot loop=20ms")
        assertTrue(allocated <= 4096, "Allocated $allocated bytes")
        assertTrue(durations[1900] < 1_000_000, "Geometry p95 exceeds 1ms desktop budget")
    }
}
