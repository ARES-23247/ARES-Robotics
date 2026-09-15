package com.areslib.pathing

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.sequencer.*
import com.areslib.state.Alliance
import com.areslib.state.RobotState
import com.areslib.subsystem.DrivetrainSubsystem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

internal fun auditFollower() = HolonomicPathFollower(object : DrivetrainSubsystem {
    override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) = Unit
    override fun getEstimatedPose() = Pose2d()
    override fun readSensors(store: Store, timestampMs: Long) = Unit
    override fun writeOutputs(state: RobotState, scale: Double) = Unit
})

class AutoAssemblyAuditTest {
    private val owned = mutableListOf<Task>()
    private fun parse(command: String, alliance: Alliance = Alliance.BLUE): Task =
        PathPlannerAutoParser.parseAuto("""{"command":$command}""", auditFollower(), 123L, alliance).also(owned::add)
    private val pathNode = """{"type":"path","data":{"pathName":"example_path"}}"""
    private val waitNode = """{"type":"wait","data":{"waitTime":0.5}}"""

    @AfterEach fun cleanup() {
        owned.forEach { it.end(RobotState(), true); it.reset() }
        NamedCommands.clear()
    }

    @Test fun `explicit alliance is honored once independent of execution state`() {
        for (requested in Alliance.entries) for (live in Alliance.entries) {
            val task = parse(pathNode, requested)
            val state = RobotState().let { it.copy(drive = it.drive.copy(alliance = live)) }
            val path = task.initialize(state).filterIsInstance<RobotAction.SwitchPath>().single().path
            assertEquals(1.0, path.points.last().pose.x, 1e-9)
            assertEquals(if (requested == Alliance.RED) -1.0 else 1.0, path.points.last().pose.y, 1e-9)
        }
    }

    @Test fun `facade single path uses same explicit alliance convention`() {
        val builder = AutoBuilder().configureFollower(auditFollower())
        for (requested in Alliance.entries) {
            val task = builder.buildPath("example_path", requested).also(owned::add)
            val path = task.initialize(RobotState()).filterIsInstance<RobotAction.SwitchPath>().single().path
            assertEquals(if (requested == Alliance.RED) -1.0 else 1.0, path.points.last().pose.y, 1e-9)
        }
        assertFailsWith<IllegalStateException> { AutoBuilder().buildPath("example_path") }
        assertFailsWith<IllegalStateException> { AutoBuilder().buildAuto("missing", 0) }
    }

    @Test fun `starting pose rejects coercion nonfinite and incomplete data`() {
        for (pose in listOf(
            """{"position":{"x":"1","y":2},"rotation":0}""",
            """{"position":{"x":1e400,"y":2},"rotation":0}""",
            """{"position":{"x":1,"y":2},"rotation":"NaN"}""",
            """{"position":{"x":1},"rotation":0}"""
        )) assertFailsWith<IllegalArgumentException>(pose) {
            PathPlannerAutoParser.getStartingPose("""{"startingPose":$pose}""")
        }
    }

    @Test fun `absent pose stays optional and degrees convert to ccw radians`() {
        assertNull(PathPlannerAutoParser.getStartingPose("{}"))
        val pose = PathPlannerAutoParser.getStartingPose("""{"startingPose":{"position":{"x":-1,"y":2},"rotation":-90}}""")!!
        assertEquals(-1.0, pose.x)
        assertEquals(2.0, pose.y)
        assertEquals(-Math.PI / 2, pose.heading.radians, 1e-12)
    }

    @Test fun `numeric wait strings and missing child arrays are rejected`() {
        assertFailsWith<IllegalArgumentException> { parse("""{"type":"wait","data":{"waitTime":"0.5"}}""") }
        for (type in listOf("sequential", "parallel", "race", "deadline")) {
            assertFailsWith<IllegalArgumentException> { parse("""{"type":"$type","data":{}}""") }
        }
    }

    @Test fun `wait durations preserve millisecond boundary and reject invalid ranges`() {
        val task = parse(waitNode)
        assertFalse(task.isCompleted(RobotState(), 499))
        assertTrue(task.isCompleted(RobotState(), 500))
        for (value in listOf("-1", "1e400", "1e20")) {
            assertFailsWith<IllegalArgumentException> { parse("""{"type":"wait","data":{"waitTime":$value}}""") }
        }
    }

    @Test fun `command nesting is bounded before stack overflow`() {
        var command = waitNode
        repeat(70) { command = """{"type":"sequential","data":{"commands":[$command]}}""" }
        assertFailsWith<IllegalArgumentException> { parse(command) }
        assertFailsWith<IllegalArgumentException> { PathPlannerAutoParser.getFirstPathName("""{"command":$command}""") }
    }

    @Test fun `nested groups retain first path ordering and fresh named factories`() {
        val timestamps = mutableListOf<Long>()
        NamedCommands.register(CommandKey("probe"), "Probe") { timestamps += it; TimeWaitTask(1) }
        val named = """{"type":"named","data":{"name":"probe"}}"""
        val command = """{"type":"sequential","data":{"commands":[$named,{"type":"parallel","data":{"commands":[$waitNode,$pathNode]}},$named]}}"""
        val task = parse(command)
        assertEquals(TaskResources.DRIVE, task.requiredResources)
        assertEquals(listOf(123L, 123L), timestamps)
        assertEquals("example_path", PathPlannerAutoParser.getFirstPathName("""{"command":$command}"""))
        assertNull(PathPlannerAutoParser.getFirstPathName("""{"command":$waitNode}"""))
    }

    @Test fun `race and deadline preserve task semantics and missing commands fail`() {
        assertIs<ParallelRaceGroup>(parse("""{"type":"race","data":{"commands":[$waitNode]}}"""))
        assertIs<ParallelDeadlineGroup>(parse("""{"type":"deadline","data":{"commands":[$waitNode]}}"""))
        assertIs<SequentialTaskGroup>(parse("""{"type":"sequential","data":{"commands":[]}}"""))
        assertFailsWith<IllegalArgumentException> { parse("""{"type":"deadline","data":{"commands":[]}}""") }
        assertFailsWith<IllegalStateException> { parse("""{"type":"named","data":{"name":"unregistered"}}""") }
        assertFailsWith<IllegalStateException> { parse("""{"type":"unknown","data":{}}""") }
        for (root in listOf("null", "[]", "{}", "{broken")) {
            assertFailsWith<IllegalArgumentException> { PathPlannerAutoParser.parseAuto(root, auditFollower(), 0) }
        }
    }
}
