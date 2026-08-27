package org.aresfirst.starter.frc

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineAlliance
import com.areslib.routine.RoutinePose
import com.areslib.sequencer.TaskExecutor
import com.areslib.sequencer.TaskStateMachine
import com.areslib.sequencer.TaskStatus
import com.areslib.state.Alliance
import com.areslib.util.RobotClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class StarterFrcAutonomousRuntimeTest {
    @Test
    fun `selector rejects unknown request into deterministic do-nothing fallback`() {
        val selector = StarterFrcAutonomousSelector(
            entries = listOf(
                entry("drive", sortOrder = 1),
                entry("do-nothing", sortOrder = 0),
            ),
            defaultEntryId = "do-nothing",
        )

        val selection = selector.resolve("deleted-auto")

        assertEquals("do-nothing", selection.entry.entryId)
        assertTrue(selection.usedFallback)
        assertEquals(listOf("do-nothing", "drive"), selector.availableEntryIds)
    }

    @Test
    fun `ARES Studio autonomous request wins with standard dashboard compatibility fallback`() {
        assertEquals(
            "studio-auto",
            resolveStarterFrcAutonomousRequest(" studio-auto ", "dashboard-auto", "do-nothing"),
        )
        assertEquals(
            "dashboard-auto",
            resolveStarterFrcAutonomousRequest(" ", "dashboard-auto", "do-nothing"),
        )
        assertEquals(
            "do-nothing",
            resolveStarterFrcAutonomousRequest(null, "", "do-nothing"),
        )
    }

    @Test
    fun `opposite alliance mirrors FRC pose exactly once`() {
        val authored = entry("drive", authoredAlliance = RoutineAlliance.BLUE)
        val source = RoutinePose(2.0, 1.0, 0.25)

        val unchanged = transformStarterFrcPose(source, authored, Alliance.BLUE)
        val mirrored = transformStarterFrcPose(source, authored, Alliance.RED)

        assertEquals(2.0, unchanged.x, 1e-12)
        assertEquals(1.0, unchanged.y, 1e-12)
        assertEquals(CoordinateTestValues.FIELD_LENGTH - 2.0, mirrored.x, 1e-9)
        assertEquals(1.0, mirrored.y, 1e-9)
        assertEquals(
            2.0,
            transformStarterFrcPose(
                RoutinePose(mirrored.x, mirrored.y, mirrored.heading.radians),
                authored.copy(authoredAlliance = RoutineAlliance.RED),
                Alliance.BLUE,
            ).x,
            1e-9,
        )
    }

    @Test
    fun `do-nothing fallback never seeds or teleports robot pose`() {
        assertFalse(shouldSeedStarterFrcAutonomousPose(entry("do-nothing")))
        assertTrue(shouldSeedStarterFrcAutonomousPose(entry("drive")))
        assertTrue(
            shouldSeedStarterFrcAutonomousPose(
                entry("do-nothing").copy(routineId = "explicit-motion-routine"),
            ),
        )
    }

    @Test
    fun `closed-loop starter task reaches a GUI-authored pose in deterministic simulation`() {
        RobotClock.useMockTime(0L)
        try {
            val store = Store()
            val simulation = StarterDriveSimulation(startX = 1.0, startY = 1.0)
            store.dispatch(poseUpdate(1.0, 1.0, 0.0, 0L))
            val task = StarterFrcDriveToPoseTask(
                Pose2d(2.0, 1.5, Rotation2d(0.4)),
                StarterFrcMotionPreset.SAFE,
            )
            val executor = TaskExecutor().also { it.addTask(task) }

            var tick = 1L
            while (executor.size > 0 && tick <= 500L) {
                val now = tick * 20L
                RobotClock.useMockTime(now)
                executor.update(store.state, now).forEach(store::dispatch)
                store.dispatch(simulation.step(store.state, 0.02, now))
                tick++
            }

            assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(task))
            assertEquals(2.0, simulation.xMeters, 0.06)
            assertEquals(1.5, simulation.yMeters, 0.06)
            assertEquals(0.4, simulation.headingRadians, Math.toRadians(3.0))
            assertEquals(0.0, store.state.drive.xVelocityMetersPerSecond, 1e-12)
            assertEquals(0.0, store.state.drive.yVelocityMetersPerSecond, 1e-12)
        } finally {
            RobotClock.useSystemTime()
        }
    }

    @Test
    fun `stale pose feedback fails the autonomous task and commands neutral`() {
        RobotClock.useMockTime(0L)
        try {
            val store = Store()
            store.dispatch(poseUpdate(1.0, 1.0, 0.0, 0L))
            val task = StarterFrcDriveToPoseTask(
                Pose2d(3.0, 1.0, Rotation2d()),
                StarterFrcMotionPreset.BALANCED,
            )
            val executor = TaskExecutor().also { it.addTask(task) }

            RobotClock.useMockTime(1_000L)
            executor.update(store.state, 1_000L).forEach(store::dispatch)

            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertEquals(0.0, store.state.drive.xVelocityMetersPerSecond, 1e-12)
            assertEquals(0.0, store.state.drive.yVelocityMetersPerSecond, 1e-12)
            assertEquals(0.0, store.state.drive.angularVelocityRadiansPerSecond, 1e-12)
        } finally {
            RobotClock.useSystemTime()
        }
    }

    @Test
    fun `closed-loop starter task consumes typed velocity and acceleration limits`() {
        RobotClock.useMockTime(20L)
        try {
            val store = Store()
            store.dispatch(poseUpdate(0.0, 0.0, 0.0, 20L))
            store.dispatch(
                RobotAction.UpdateTuningState(
                    store.state.tuning.copy(
                        drive = store.state.tuning.drive.copy(pathVelocityScale = 0.25),
                    ),
                ),
            )
            val task = StarterFrcDriveToPoseTask(
                Pose2d(10.0, 0.0, Rotation2d()),
                StarterFrcMotionPreset.FAST,
            )
            val executor = TaskExecutor().also { it.addTask(task) }

            var previousVelocity = 0.0
            repeat(10) { index ->
                val now = 20L + index * 20L
                RobotClock.useMockTime(now)
                store.dispatch(poseUpdate(0.0, 0.0, 0.0, now))
                executor.update(store.state, now).forEach(store::dispatch)
                assertTrue(
                    store.state.drive.xVelocityMetersPerSecond - previousVelocity <= 0.0600000001,
                    "acceleration limit must bound each 20 ms command change",
                )
                previousVelocity = store.state.drive.xVelocityMetersPerSecond
            }

            assertEquals(0.425, store.state.drive.xVelocityMetersPerSecond, 1e-12)
        } finally {
            RobotClock.useSystemTime()
        }
    }

    @Test
    fun `blocked closed-loop drive times out and commands neutral`() {
        RobotClock.useMockTime(0L)
        try {
            val store = Store()
            val task = StarterFrcDriveToPoseTask(
                Pose2d(3.0, 1.0, Rotation2d()),
                StarterFrcMotionPreset.BALANCED,
            )
            val executor = TaskExecutor().also { it.addTask(task) }

            var now = 0L
            while (executor.size > 0 && now <= 11_000L) {
                RobotClock.useMockTime(now)
                store.dispatch(poseUpdate(1.0, 1.0, 0.0, now))
                executor.update(store.state, now).forEach(store::dispatch)
                now += 20L
            }

            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertEquals(0.0, store.state.drive.xVelocityMetersPerSecond, 1e-12)
            assertEquals(0.0, store.state.drive.yVelocityMetersPerSecond, 1e-12)
            assertEquals(0.0, store.state.drive.angularVelocityRadiansPerSecond, 1e-12)
        } finally {
            RobotClock.useSystemTime()
        }
    }

    private fun entry(
        id: String,
        sortOrder: Int = 0,
        authoredAlliance: RoutineAlliance = RoutineAlliance.BLUE,
    ) = AutonomousCatalogEntry(
        entryId = id,
        displayName = id,
        routineId = id,
        startingPose = RoutinePose(1.0, 1.0, 0.0),
        authoredAlliance = authoredAlliance,
        mirrorForOppositeAlliance = true,
        sortOrder = sortOrder,
    )

    private fun poseUpdate(x: Double, y: Double, heading: Double, timestampMs: Long) = RobotAction.PoseUpdate(
        xMeters = x,
        yMeters = y,
        headingRadians = heading,
        timestampMs = timestampMs,
        isReset = true,
        isExternalEstimate = true,
        applyControlHubGyroCorrection = false,
        motionMeasurementsValid = true,
        imuMeasurementsValid = true,
    )

    private object CoordinateTestValues {
        const val FIELD_LENGTH = 16.54175
    }
}
