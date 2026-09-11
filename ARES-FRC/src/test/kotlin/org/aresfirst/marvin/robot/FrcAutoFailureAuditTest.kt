package org.aresfirst.marvin.robot

import com.areslib.action.RobotAction
import com.areslib.frc.FrcSwerveRobot
import com.areslib.hardware.HardwareRegistry
import com.areslib.hardware.SubsystemIO
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutinePose
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import com.areslib.telemetry.ITelemetry
import com.areslib.util.RobotClock
import org.aresfirst.marvin.marvin.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FrcAutoFailureAuditTest {
    private class Probe : SubsystemIO { var stops = 0; override fun safe() { stops++ } }
    private class Sink : ITelemetry {
        val strings = mutableMapOf<String, String>()
        var failKey: String? = null
        override fun putNumber(key: String, value: Double) {}
        override fun putBoolean(key: String, value: Boolean) {}
        override fun putString(key: String, value: String) {
            if (key == failKey) throw IllegalStateException("telemetry unavailable")
            strings[key] = value
        }
        override fun putDoubleArray(key: String, value: DoubleArray) {}
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
    }
    private class Fixture : AutoCloseable {
        val failure = AssertionError("injected reducer failure")
        var reject: (RobotAction) -> Boolean = { false }
        var rejectReduction: (RobotAction) -> Boolean = { false }
        val attempted = mutableListOf<RobotAction>()
        val probe = Probe()
        val telemetry = Sink()
        private val registry = HardwareRegistry().apply { registerDevice("stop-probe", probe) }
        val robot = FrcSwerveRobot(isSimulation = true, hardwareRegistry = registry,
            baseTelemetry = telemetry, initialState = RobotState(superstructure = SuperstructureState(custom = MarvinState())),
            reducer = { state, action ->
                if (rejectReduction(action)) throw failure
                MarvinReducer.reduce(state, action)
            }).also { robot -> robot.store.actionListener = { action ->
                attempted.add(action)
                if (reject(action)) throw failure
            } }
        fun runner(selection: () -> String = { "wait" }): FRCAutoOrchestrator {
            val document = AresRoutineCodec.decode("""{
                "schemaVersion":2,"documentId":"wait","revision":1,"name":"Wait",
                "steps":[{"kind":"WAIT","stepId":"hold","arguments":{},
                    "durationSeconds":10.0,"children":[],"elseChildren":[]}]
            }""")
            return FRCAutoOrchestrator(robot, selectionProvider = selection,
                autonomousEntries = listOf(AutonomousCatalogEntry(entryId = "wait", displayName = "Wait",
                    routineId = "wait", startingPose = RoutinePose(2.0, 2.0, 0.0))),
                defaultAutonomousEntryId = "wait", routineDocuments = mapOf("wait" to document))
        }
        override fun close() { reject = { false }; telemetry.failKey = null; robot.close() }
    }
    private val fixtures = mutableListOf<Fixture>()
    private fun fixture() = Fixture().also { fixtures.add(it) }
    @BeforeEach fun clock() { RobotClock.useMockTime(1000L) }
    @AfterEach fun cleanup() {
        try { assertAll(fixtures.map { f -> org.junit.jupiter.api.function.Executable { f.close() } }) }
        finally { RobotClock.useSystemTime() }
    }

    @Test fun `stop still inhibits and neutralizes after cancellation dispatch fails`() {
        val f = fixture()
        val runner = f.runner()
        runner.autonomousInit()
        runner.autonomousPeriodic()
        f.reject = { it is RobotAction.RoutineCancelled }
        val before = f.probe.stops
        assertSame(f.failure, assertThrows(AssertionError::class.java) { runner.stop() })
        assertTrue(runner.isFinishedForTest)
        assertTrue(f.probe.stops > before, "Cancellation failure skipped hardware safety")
        assertTrue(f.robot.store.state.superstructure.marvin.mechanismSafetyInhibited)
        val count = f.attempted.size
        runner.autonomousPeriodic()
        assertEquals(count, f.attempted.size, "Stopped runner must not update again")
    }

    @Test fun `each failed stop dispatch leaves remaining safety operations reachable`() {
        for (stage in 0..2) {
            val f = fixture()
            val runner = f.runner()
            runner.autonomousInit()
            f.attempted.clear()
            f.reject = { action -> when (stage) {
                0 -> action is RobotAction.JoystickDriveIntent
                1 -> action is RobotAction.SetDriveMode
                else -> action is SetMechanismSafetyInhibit
            } }
            val before = f.probe.stops
            assertSame(f.failure, assertThrows(AssertionError::class.java) { runner.stop() })
            assertTrue(f.attempted.any { it is RobotAction.JoystickDriveIntent })
            assertTrue(f.attempted.any { it is RobotAction.SetDriveMode }, "Drive zero failure skipped brake")
            assertTrue(f.attempted.any { it is SetMechanismSafetyInhibit }, "Drive failure skipped inhibit")
            assertTrue(f.probe.stops > before, "Stage $stage skipped hardware safety")
        }
    }

    @Test fun `initial drive failure enters fault lifecycle and still attempts hardware safety`() {
        val f = fixture()
        var selections = 0
        val runner = f.runner { selections++; "wait" }
        f.reject = { it is RobotAction.JoystickDriveIntent }
        runCatching { runner.autonomousInit() }
        assertTrue(runner.isFaultedForTest)
        assertTrue(runner.isFinishedForTest)
        assertTrue(f.probe.stops > 0)
        assertTrue(f.robot.store.state.superstructure.marvin.mechanismSafetyFaultLatched)
        assertEquals(0, selections, "Failed initial stop must not select/arm a routine")
    }

    @Test fun `telemetry failure cannot hide independent abort diagnostics or safety`() {
        val f = fixture()
        val runner = f.runner { error("selection failed") }
        f.telemetry.failKey = "ARES/Auto/Selected"
        runCatching { runner.autonomousInit() }
        assertTrue(f.probe.stops > 0)
        assertEquals("Blocked", f.telemetry.strings["ARES/Auto/Status"])
        assertTrue(f.telemetry.strings["ARES/Auto/Error"]?.contains("selection failed") == true)
        assertTrue(runner.isFaultedForTest)
    }

    @Test fun `invalidated reducer cannot prevent direct hardware safety`() {
        val f = fixture()
        val runner = f.runner()
        runner.autonomousInit()
        f.rejectReduction = { it is RobotAction.JoystickDriveIntent }
        val before = f.probe.stops
        assertSame(f.failure, assertThrows(AssertionError::class.java) { runner.stop() })
        assertTrue(runner.isFinishedForTest)
        assertTrue(f.probe.stops > before, "Store invalidation must not prevent direct hardware safety")
        assertThrows(IllegalStateException::class.java) { f.robot.store.dispatch(SetMechanismSafetyInhibit(true)) }
    }

    @Test fun `named action factories are fresh and reject unsupported arguments`() {
        val f = fixture()
        for (descriptor in FrcAutoCapabilities.descriptors) {
            val first = FrcAutoCapabilities.createActionTask(descriptor.key.value, emptyMap())!!
            val second = FrcAutoCapabilities.createActionTask(descriptor.key.value, emptyMap())!!
            assertNotSame(first, second)
            assertEquals(descriptor.requiredResources, first.requiredResources)
            first.initialize(f.robot.store.state)
            assertFalse(second.isCompleted(f.robot.store.state, 0L))
            first.releaseRuntimeState()
            second.releaseRuntimeState()
        }
        assertNull(FrcAutoCapabilities.createActionTask("missing", emptyMap()))
        assertNull(FrcAutoCapabilities.createCondition("missing", emptyMap()))
        assertThrows(IllegalArgumentException::class.java) {
            FrcAutoCapabilities.createActionTask("intake.collect", mapOf("speed" to "20"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrcAutoCapabilities.createCondition("shooter.ready", mapOf("tolerance" to "20"))
        }
    }

    @Test fun `feed gate times out safely and late readiness still grants only one bounded transfer`() {
        val f = fixture()
        val unready = f.robot.store.state
        val task = FrcAutoCapabilities.actionShooterFeedWhenReady()
        task.initialize(unready)
        assertTrue(task.execute(unready, 1999L).isEmpty())
        assertFalse(task.isCompleted(unready, 1999L))
        assertTrue(task.isCompleted(unready, 2000L))
        assertTrue(task.execute(unready, 2000L).isEmpty())
        task.end(unready, false).forEach(f.robot.store::dispatch)
        assertEquals(0.0, f.robot.store.state.superstructure.marvin.feeder.targetVelocityRps)
        task.releaseRuntimeState()
        val marvin = MarvinState(flywheel = FlywheelState(targetVelocityRpm = 4000.0,
            velocityRpm = 4000.0, velocityValid = true, allMotorsAtTarget = true),
            cowl = CowlState(targetAngleRotations = 1.55, angleRotations = 1.55, angleValid = true))
        val ready = unready.copy(superstructure = SuperstructureState(custom = marvin))
        task.initialize(ready)
        assertTrue(task.execute(ready, 1999L).any { it is StartTransfer })
        assertTrue(task.execute(ready, 2000L).isEmpty())
        assertFalse(task.isCompleted(ready, 2448L))
        assertTrue(task.isCompleted(ready, 2449L))
        assertTrue(task.isCompleted(ready, 1998L), "Elapsed-time rewind closes a started transfer")
        task.end(ready, true)
        task.releaseRuntimeState()
    }
}
