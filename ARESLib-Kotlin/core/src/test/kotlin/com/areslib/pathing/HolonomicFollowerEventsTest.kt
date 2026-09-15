package com.areslib.pathing

import com.areslib.Store
import com.areslib.math.geometry.Pose2d
import com.areslib.state.RobotState
import com.areslib.subsystem.DrivetrainSubsystem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class FollowerDriveProbe : DrivetrainSubsystem {
    var pose = Pose2d()
    var poseReads = 0
    var vx = 0.0
    var vy = 0.0
    var omega = 0.0
    var writes = 0
    var writeFailure: Throwable? = null
    override fun getEstimatedPose(): Pose2d { poseReads++; return pose }
    override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) {
        writes++
        writeFailure?.let { throw it }
        this.vx = vx; this.vy = vy; this.omega = omega
    }
    override fun readSensors(store: Store, timestampMs: Long) = Unit
    override fun writeOutputs(state: RobotState, scale: Double) = Unit
}

internal fun followerTarget(distance: Double = 0.0) = PathPoint(Pose2d(), 1.0, distance)

class HolonomicFollowerEventsTest {
    @Test
    fun `positive and negative zero thresholds preserve authored tie order`() {
        val follower = HolonomicPathFollower(FollowerDriveProbe())
        val seen = mutableListOf<String>()
        follower.onEventTriggered = { seen.add(it) }
        follower.startPath(Path(emptyList(), listOf(PathEvent("positive", 0.0), PathEvent("negative", -0.0))))
        follower.update(followerTarget(), 0.02)
        assertEquals(listOf("positive", "negative"), seen)
    }

    @Test
    fun `each occurrence fires in distance order with stable ties and no replay on retreat`() {
        val follower = HolonomicPathFollower(FollowerDriveProbe())
        val seen = mutableListOf<String>()
        follower.onEventTriggered = { seen.add(it) }
        val path = Path(emptyList(), listOf(
            PathEvent("again", 3.0), PathEvent("first", 1.0),
            PathEvent("again", 1.0), PathEvent("tie", 1.0)))
        follower.startPath(path)
        follower.update(followerTarget(1.0), 0.02)
        assertEquals(listOf("first", "again", "tie"), seen)
        follower.update(followerTarget(0.0), 0.02)
        follower.update(followerTarget(4.0), 0.02)
        follower.update(followerTarget(4.0), 0.02)
        assertEquals(listOf("first", "again", "tie", "again"), seen)
        seen.clear()
        follower.startPath(path)
        follower.update(followerTarget(4.0), 0.02)
        assertEquals(listOf("first", "again", "tie", "again"), seen)
    }

    @Test
    fun `setup owns event snapshot and later updates never read caller list`() {
        val events = object : AbstractMutableList<PathEvent>() {
            val values = mutableListOf(PathEvent("once", 1.0))
            var reads = 0
            override val size get() = values.size
            override fun get(index: Int): PathEvent { reads++; return values[index] }
            override fun add(index: Int, element: PathEvent) = values.add(index, element)
            override fun set(index: Int, element: PathEvent) = values.set(index, element)
            override fun removeAt(index: Int) = values.removeAt(index)
        }
        val follower = HolonomicPathFollower(FollowerDriveProbe())
        val seen = mutableListOf<String>()
        follower.onEventTriggered = { seen.add(it) }
        follower.startPath(Path(emptyList(), events))
        events.clear()
        val setupReads = events.reads
        repeat(100) { follower.update(followerTarget(2.0), 0.02) }
        assertEquals(listOf("once"), seen)
        assertEquals(setupReads, events.reads)
    }

    @Test
    fun `callback stop cancels remaining callbacks and old motion but permits later update`() {
        val drive = FollowerDriveProbe()
        val follower = HolonomicPathFollower(drive)
        val seen = mutableListOf<String>()
        follower.startPath(Path(emptyList(), listOf(PathEvent("stop", 0.0), PathEvent("later", 0.0))))
        follower.onEventTriggered = { seen.add(it); follower.stop() }
        follower.update(followerTarget(), 0.02)
        assertEquals(listOf("stop"), seen)
        assertEquals(0.0, drive.vx)
        follower.onEventTriggered = { seen.add(it) }
        follower.update(followerTarget(), 0.02)
        assertEquals(listOf("stop", "later"), seen)
        assertTrue(drive.vx > 0.0)
    }

    @Test
    fun `callback replacement cannot emit old path command or consume new path markers`() {
        val drive = FollowerDriveProbe()
        val follower = HolonomicPathFollower(drive)
        val seen = mutableListOf<String>()
        val replacement = Path(emptyList(), listOf(PathEvent("new", 0.0)))
        follower.startPath(Path(emptyList(), listOf(PathEvent("replace", 0.0), PathEvent("old", 0.0))))
        follower.onEventTriggered = { name ->
            seen.add(name)
            if (name == "replace") follower.startPath(replacement)
        }
        follower.update(followerTarget(), 0.02)
        assertEquals(listOf("replace"), seen)
        assertEquals(0, drive.writes)
        follower.update(followerTarget(), 0.02)
        assertEquals(listOf("replace", "new"), seen)
        assertTrue(drive.vx > 0.0)
    }

    @Test
    fun `callback failure stops and preserves original failure even if cleanup throws same instance`() {
        val drive = FollowerDriveProbe()
        val follower = HolonomicPathFollower(drive)
        val failure = IllegalStateException("callback and writer")
        drive.writeFailure = failure
        follower.startPath(Path(emptyList(), listOf(PathEvent("fail", 0.0))))
        follower.onEventTriggered = { throw failure }
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            follower.update(followerTarget(), 0.02)
        })
        assertEquals(1, drive.writes)
        assertEquals(0, failure.suppressed.size)
    }

    @Test
    fun `distinct cleanup failure is suppressed and failed event is not retried`() {
        val drive = FollowerDriveProbe()
        val follower = HolonomicPathFollower(drive)
        val original = IllegalArgumentException("event")
        val cleanup = IllegalStateException("stop")
        var events = 0
        drive.writeFailure = cleanup
        follower.startPath(Path(emptyList(), listOf(PathEvent("fail", 0.0))))
        follower.onEventTriggered = { events++; throw original }
        assertSame(original, assertThrows(IllegalArgumentException::class.java) {
            follower.update(followerTarget(), 0.02)
        })
        assertArrayEquals(arrayOf(cleanup), original.suppressed)
        drive.writeFailure = null
        follower.update(followerTarget(), 0.02)
        assertEquals(1, events)
    }

    @Test
    fun `restarting same path resets occurrence progress`() {
        val follower = HolonomicPathFollower(FollowerDriveProbe())
        val path = Path(emptyList(), listOf(PathEvent("again", 0.0)))
        var count = 0
        follower.onEventTriggered = { count++ }
        repeat(2) { follower.startPath(path); follower.update(followerTarget(), 0.02) }
        assertEquals(2, count)
    }
}
