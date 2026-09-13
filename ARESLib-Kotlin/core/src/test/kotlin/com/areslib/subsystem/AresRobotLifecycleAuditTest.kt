package com.areslib.subsystem

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.hardware.HardwareRegistry
import com.areslib.hardware.SubsystemIO
import com.areslib.state.RobotState
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.*
import org.junit.jupiter.api.Test

class AresRobotLifecycleAuditTest {
    @Test fun sensorCallbacksShareStoreAndPreserveSuppliedTimestampAndOrder() {
        val robot = AresRobot()
        val observed = mutableListOf<String>()
        robot.registerSubsystem(object : Probe() {
            override fun readSensors(store: Store, timestampMs: Long) {
                assertSame(robot.store, store); assertEquals(42L, timestampMs)
                observed += "first"
                store.dispatch(RobotAction.UpdatePathProgress(0.5, timestampMs = timestampMs))
            }
        })
        robot.registerSubsystem(object : Probe() {
            override fun readSensors(store: Store, timestampMs: Long) {
                assertEquals(0.5, store.state.pathState.currentDistanceMeters)
                assertEquals(42L, store.state.timestampMs); observed += "second"
            }
        })
        robot.readAllSensors(42L)
        assertEquals(listOf("first", "second"), observed)
        robot.closeSubsystems()
    }

    @Test fun reentrantSafetyLeavesOneTraversalInCharge() {
        val registry = HardwareRegistry(); var externalSafes = 0
        registry.registerDevice("external", object : SubsystemIO { override fun safe() { externalSafes++ } })
        val robot = AresRobot(hardwareRegistry = registry); var firstSafes = 0; val later = Probe()
        robot.registerSubsystem(object : Probe() {
            override fun writeOutputs(state: RobotState, scale: Double) { firstSafes++; robot.safeAll() }
        })
        robot.registerSubsystem(later)
        robot.safeAll()
        assertEquals(1, firstSafes); assertEquals(1, later.writes); assertEquals(1, externalSafes)
        robot.closeSubsystems(); registry.closeAll()
    }

    private open class Probe : Subsystem {
        var reads = 0
        var writes = 0
        var closes = 0
        var lastScale = -1.0
        override fun readSensors(store: Store, timestampMs: Long) { reads++ }
        override fun writeOutputs(state: RobotState, scale: Double) { writes++; lastScale = scale }
        override fun close() { closes++ }
        override fun equals(other: Any?): Boolean = true
        override fun hashCode(): Int = 1
    }

    @Test fun duplicateRegistrationUsesIdentityAndPollsOnce() {
        val robot = AresRobot()
        val first = Probe(); val second = Probe()
        robot.registerSubsystem(first); robot.registerSubsystem(first); robot.registerSubsystem(second)
        robot.readAllSensors(10L); robot.writeAllOutputs(0.5)
        assertEquals(1, first.reads); assertEquals(1, first.writes)
        assertEquals(1, second.reads); assertEquals(1, second.writes)
        assertEquals(2, robot.getRegisteredSubsystems().size)
        robot.closeSubsystems()
    }

    @Test fun exposedRegistryCannotRemoveSafetyParticipants() {
        val robot = AresRobot(); val first = Probe()
        robot.registerSubsystem(first)
        val before = robot.getRegisteredSubsystems()
        assertFailsWith<UnsupportedOperationException> { (before as MutableList<Subsystem>).clear() }
        robot.registerSubsystem(Probe())
        assertEquals(1, before.size, "Retained registry snapshot must remain stable")
        assertEquals(2, robot.getRegisteredSubsystems().size)
        robot.safeAll()
        assertEquals(0.0, first.lastScale)
        robot.closeSubsystems()
    }

    @Test fun powerScaleIsBoundedAndNonfiniteValuesNeutralize() {
        val robot = AresRobot(); val probe = Probe(); robot.registerSubsystem(probe)
        for ((input, expected) in listOf(-1.0 to 0.0, 0.4 to 0.4, 2.0 to 1.0,
            Double.NaN to 0.0, Double.POSITIVE_INFINITY to 0.0, Double.NEGATIVE_INFINITY to 0.0)) {
            robot.writeAllOutputs(input); assertEquals(expected, probe.lastScale)
        }
        robot.closeSubsystems()
    }

    @Test fun closeNeutralizesEverySubsystemBeforeClosingAndIsTerminal() {
        val events = mutableListOf<String>()
        val robot = AresRobot()
        repeat(2) { id -> robot.registerSubsystem(object : Probe() {
            override fun writeOutputs(state: RobotState, scale: Double) { events += "safe" + id; assertEquals(0.0, scale) }
            override fun close() { events += "close" + id }
        }) }
        robot.closeSubsystems(); robot.closeSubsystems()
        assertEquals(listOf("safe0","safe1","close0","close1"), events)
        assertTrue(robot.getRegisteredSubsystems().isEmpty())
        assertFailsWith<IllegalStateException> { robot.registerSubsystem(Probe()) }
        assertFailsWith<IllegalStateException> { robot.readAllSensors(20L) }
        assertFailsWith<IllegalStateException> { robot.writeAllOutputs(1.0) }
    }

    @Test fun fatalSafetyErrorsRemainObservableAfterEveryParticipantIsAttempted() {
        val first = AssertionError("first"); val second = AssertionError("second")
        val registry = HardwareRegistry()
        var registrySafes = 0
        registry.registerDevice("external", object : SubsystemIO { override fun safe() { registrySafes++; throw second } })
        val robot = AresRobot(hardwareRegistry = registry)
        var laterSafes = 0
        robot.registerSubsystem(object : Probe() { override fun writeOutputs(state: RobotState, scale: Double) { throw first } })
        robot.registerSubsystem(object : Probe() { override fun writeOutputs(state: RobotState, scale: Double) { laterSafes++ } })
        assertSame(first, assertFailsWith<AssertionError> { robot.safeAll() })
        assertEquals(listOf(second), first.suppressed.toList())
        assertEquals(1, laterSafes); assertEquals(1, registrySafes)
        assertSame(first, assertFailsWith<AssertionError> { robot.closeSubsystems() })
        registry.closeAll()
    }

    @Test fun sharedFatalCloseErrorDoesNotSkipLaterCleanup() {
        val failure = AssertionError("shared")
        val robot = AresRobot()
        var closed = 0
        repeat(2) { robot.registerSubsystem(object : Probe() {
            override fun close() { closed++; throw failure }
        }) }
        assertSame(failure, assertFailsWith<AssertionError> { robot.closeSubsystems() })
        assertEquals(2, closed)
        assertTrue(failure.suppressed.isEmpty())
        robot.closeSubsystems()
        assertEquals(2, closed)
    }

    @Test fun ordinarySafetyAndCloseFailuresStillAttemptRemainingResources() {
        val robot = AresRobot(); val later = Probe()
        robot.registerSubsystem(object : Probe() {
            override fun writeOutputs(state: RobotState, scale: Double) { error("write") }
            override fun close() { error("close") }
        })
        robot.registerSubsystem(later)
        robot.safeAll(); robot.closeSubsystems()
        assertEquals(2, later.writes); assertEquals(1, later.closes)
    }

    @Test fun oneOutputBatchUsesOneSnapshotEvenIfACallbackDispatches() {
        val robot = AresRobot()
        val before = robot.store.state
        val seen = mutableListOf<RobotState>()
        robot.registerSubsystem(object : Probe() {
            override fun writeOutputs(state: RobotState, scale: Double) {
                seen += state
                robot.store.dispatch(RobotAction.SetIndicatorLight("led", 0.2, 20L))
            }
        })
        robot.registerSubsystem(object : Probe() { override fun writeOutputs(state: RobotState, scale: Double) { seen += state } })
        robot.writeAllOutputs(1.0)
        assertEquals(2, seen.size)
        assertSame(before, seen[0]); assertSame(before, seen[1])
        assertEquals(20L, robot.store.state.timestampMs)
        robot.closeSubsystems()
    }

    @Test fun closeFromOutputCallbackPreventsLaterActiveWrites() {
        val robot = AresRobot(); val later = Probe()
        robot.registerSubsystem(object : Probe() {
            override fun writeOutputs(state: RobotState, scale: Double) {
                if (scale > 0.0) robot.closeSubsystems()
            }
        })
        robot.registerSubsystem(later)
        robot.writeAllOutputs(1.0)
        assertEquals(0.0, later.lastScale)
        assertEquals(1, later.closes)
    }

    @Test fun registrationInsideReadCallbackIsRejectedWithoutChangingMembership() {
        val robot = AresRobot()
        robot.registerSubsystem(object : Probe() {
            override fun readSensors(store: Store, timestampMs: Long) { robot.registerSubsystem(Probe()) }
        })
        assertFailsWith<IllegalStateException> { robot.readAllSensors(10L) }
        assertEquals(1, robot.getRegisteredSubsystems().size)
        robot.closeSubsystems()
    }

    @Test fun repeatedLifecycleIterationDoesNotAllocate() {
        val robot = AresRobot(); repeat(8) { robot.registerSubsystem(Probe()) }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported); bean.isThreadAllocatedMemoryEnabled = true
        repeat(30_000) { robot.readAllSensors(10L); robot.writeAllOutputs(0.5); robot.getRegisteredSubsystems() }
        val id = Thread.currentThread().id; val before = bean.getThreadAllocatedBytes(id)
        repeat(10_000) { robot.readAllSensors(10L); robot.writeAllOutputs(0.5); robot.getRegisteredSubsystems() }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        println("Robot lifecycle: " + allocated + " bytes / 10000 read-write batches")
        assertTrue(allocated <= 4096L, "Lifecycle allocated " + allocated)
        robot.closeSubsystems()
    }
}
