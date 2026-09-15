package org.firstinspires.ftc.teamcode

import com.areslib.ftc.FtcMecanumRobot
import com.areslib.hardware.HardwareRegistry
import com.areslib.math.estimation.PoseEstimator
import com.areslib.pathing.NamedCommands
import com.areslib.state.RobotFieldManager
import com.areslib.subsystem.Subsystem
import com.areslib.tuning.TuningManager
import com.areslib.util.RobotClock
import com.areslib.telemetry.AresGamepad
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.Servo
import org.firstinspires.ftc.teamcode.opmodes.AresRobot
import org.firstinspires.ftc.teamcode.opmodes.installGeneratedSubsystems
import org.firstinspires.ftc.teamcode.opmodes.installGeneratedSuperstructures
import org.firstinspires.ftc.teamcode.opmodes.robot.AresDriveController
import org.firstinspires.ftc.teamcode.opmodes.robot.AresSuperstructureController
import org.firstinspires.ftc.teamcode.opmodes.robot.AresTelemetryHelper
import org.junit.Assert.*
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito.*

class FtcSeasonLifecycleAuditTest {
    private class RuntimeFixture {
        val base = mock(FtcMecanumRobot::class.java, RETURNS_DEEP_STUBS)
        val drive = mock(AresDriveController::class.java)
        val telemetry = mock(AresTelemetryHelper::class.java)
        val superstructure = mock(AresSuperstructureController::class.java)
        val robot = mock(AresRobot::class.java, CALLS_REAL_METHODS)
        init {
            doReturn(null).`when`(base).fatalUpdateFailure
            fun field(name: String, value: Any) {
                AresRobot::class.java.getDeclaredField(name).apply { isAccessible = true }.set(robot, value)
            }
            field("base", base); field("driveController", drive); field("telemetryHelper", telemetry)
            field("superstructureController", superstructure)
        }
        fun failSeason(failure: Throwable = IllegalStateException("season read failed")): Throwable {
            doAnswer { throw failure }.`when`(base).readAllSensors(anyLong())
            assertSame(failure, assertThrows(Throwable::class.java) { robot.update() })
            return failure
        }
    }

    /** Runs the real facade constructor while isolating shared services and physical IO. */
    private fun construction(
        configure: (FtcMecanumRobot) -> Unit,
        block: (HardwareMap, MockedConstruction<FtcMecanumRobot>) -> Unit,
    ) {
        val field = RobotFieldManager.activeConfig
        val tags = PoseEstimator.activeTags
        val map = mock(HardwareMap::class.java)
        try {
            mockConstruction(TuningManager::class.java).use {
                mockConstruction(FtcMecanumRobot::class.java, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)) { base, _ ->
                    doReturn(null).`when`(base).fatalUpdateFailure
                    configure(base)
                }.use { constructed -> block(map, constructed) }
            }
        } finally {
            RobotFieldManager.setActiveConfig(field)
            PoseEstimator.activeTags = tags
            NamedCommands.clear()
        }
    }

    @Test fun tuningInstallationFailureClosesAlreadyConstructedBase() {
        val failure = IllegalStateException("tuning installation failed")
        construction({ base -> doThrow(failure).`when`(base).tuningManager = any() }) { map, constructed ->
            assertSame(failure, assertThrows(Throwable::class.java) { AresRobot(map) })
            verify(constructed.constructed().single(), times(1)).close()
        }
    }

    @Test fun failedRegistrationClosesOnlySubsystemsStillOwnedByInstaller() {
        val first = mock(Subsystem::class.java)
        val second = mock(Subsystem::class.java)
        val third = mock(Subsystem::class.java)
        val failure = IllegalStateException("registration failed")
        val registered = mutableListOf<Subsystem>()
        val thrown = assertThrows(Throwable::class.java) {
            installGeneratedSubsystems(mock(HardwareMap::class.java), HardwareRegistry(), {
                if (it === second) throw failure
                registered += it
            }) { _, _ -> listOf(first, second, third) }
        }
        assertSame(failure, thrown)
        assertEquals(listOf(first), registered)
        verify(first, never()).close()
        verify(second, times(1)).close()
        verify(third, times(1)).close()
    }

    @Test fun autonomousAdapterPropagatesConstructionFailureAfterClosingTheBase() {
        val failure = IllegalStateException("season initialization failed")
        construction({ base -> doThrow(failure).`when`(base).tuningManager = any() }) { map, constructed ->
            val opMode = object : org.firstinspires.ftc.teamcode.dsl.AresAutoBase() {}.apply {
                hardwareMap = map
                telemetry = mock(org.firstinspires.ftc.robotcore.external.Telemetry::class.java)
            }
            assertSame(failure, assertThrows(Throwable::class.java) { opMode.init() })
            verify(constructed.constructed().single(), times(1)).close()
            opMode.stop()
            verify(constructed.constructed().single(), times(1)).close()
        }
    }

    @Test fun failedSuperstructureRegistrationClosesRemainingCoordinators() {
        val first = mock(Subsystem::class.java)
        val second = mock(Subsystem::class.java)
        val failure = IllegalStateException("coordinator registration failed")
        val cleanup = AssertionError("coordinator close failed")
        doThrow(cleanup).`when`(second).close()
        val thrown = assertThrows(Throwable::class.java) {
            installGeneratedSuperstructures({ throw failure }) { listOf(first, second) }
        }
        assertSame(failure, thrown)
        verify(first).close(); verify(second).close()
        assertEquals(listOf(cleanup), failure.suppressed.toList())
    }

    @Test fun seasonFailureRetainsBothSafetyFailures() {
        val f = RuntimeFixture()
        val safety = AssertionError("subsystem neutral failed")
        val platform = AssertionError("platform neutral failed")
        doThrow(safety).`when`(f.base).safeAll()
        doThrow(platform).`when`(f.base).safeHardware()
        val failure = f.failSeason()
        assertEquals(listOf(safety, platform), failure.suppressed.toList())
    }

    @Test fun latchedFailureRetriesSafetyWithoutDuplicatingSuppressedErrors() {
        val f = RuntimeFixture()
        val safety = AssertionError("neutral failed")
        doThrow(safety).`when`(f.base).safeAll()
        val failure = f.failSeason()
        repeat(2) { assertSame(failure, assertThrows(Throwable::class.java) { f.robot.update() }) }
        verify(f.base, times(3)).safeHardware()
        verify(f.base, times(1)).readAllSensors(anyLong())
        assertEquals(listOf(safety), failure.suppressed.toList())
    }

    @Test fun seasonInterruptRestoresTheThreadInterruptFlag() {
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            RuntimeFixture().failSeason(InterruptedException("stop requested"))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun driveIsRejectedAfterASeasonFault() {
        val f = RuntimeFixture()
        val failure = f.failSeason()
        assertSame(failure, assertThrows(Throwable::class.java) { f.robot.driveFieldCentric(1.0, 0.0, 0.0) })
        verifyNoInteractions(f.drive)
    }

    @Test fun calibrationCannotBeArmedAfterASeasonFault() {
        val f = RuntimeFixture()
        val failure = f.failSeason()
        clearInvocations(f.base)
        assertSame(failure, assertThrows(Throwable::class.java) { f.robot.enableCalibrationMode() })
        verify(f.base, never()).enableCalibrationMode()
        verify(f.base, never()).isLiveTuningEnabled = true
    }

    @Test fun driveIsRejectedAfterClose() {
        val f = RuntimeFixture()
        f.robot.close()
        assertThrows(IllegalStateException::class.java) { f.robot.driveFieldCentric(1.0, 0.0, 0.0) }
        verifyNoInteractions(f.drive)
    }

    @Test fun calibrationCannotBeArmedAfterClose() {
        val f = RuntimeFixture()
        f.robot.close()
        clearInvocations(f.base)
        assertThrows(IllegalStateException::class.java) { f.robot.enableCalibrationMode() }
        verify(f.base, never()).enableCalibrationMode()
        verify(f.base, never()).isLiveTuningEnabled = true
    }

    @Test fun closeDelegatesSharedShutdownOnceInsteadOfRepeatingSubsystemCleanup() {
        val f = RuntimeFixture()
        f.robot.close(); f.robot.close()
        verify(f.base, times(1)).disableCalibrationMode()
        verify(f.base, times(1)).close()
        verify(f.base, never()).safeAll()
        verify(f.base, never()).closeSubsystems()
    }

    @Test fun constructorCleanupCannotReplaceTheOriginalFailureWithSelfSuppression() {
        val failure = IllegalStateException("subsystem registration failed")
        construction({ base ->
            val placeholder = mock(Subsystem::class.java)
            doThrow(failure).`when`(base).close()
            doThrow(failure).`when`(base).registerSubsystem(any(Subsystem::class.java) ?: placeholder)
        }) { map, constructed ->
            doReturn(mock(Servo::class.java)).`when`(map).get(eq(Servo::class.java), anyString())
            assertSame(failure, assertThrows(Throwable::class.java) { AresRobot(map) })
            verify(constructed.constructed().single()).close()
        }
    }

    @Test fun healthyFramesConsumeOneFreshCacheAndPowerScaleInOrder() {
        val f = RuntimeFixture()
        val power = f.base.powerManager
        var nextScale = 0.0
        doAnswer {
            doReturn(nextScale).`when`(power).powerScale
            RobotClock.useMockTime(4321L)
            null
        }.`when`(f.base).update(null, null)
        try {
            for (scale in listOf(0.0, 0.25, 1.0)) {
                nextScale = scale
                clearInvocations(f.base, power, f.telemetry)
                f.robot.update()
                val order = inOrder(f.base, power, f.telemetry)
                order.verify(f.base).update(null, null)
                order.verify(f.base).readAllSensors(4321L)
                order.verify(power).powerScale
                order.verify(f.base).writeAllOutputs(scale)
                order.verify(f.telemetry).updateTelemetry()
                verify(f.base, times(1)).readAllSensors(anyLong())
                verify(f.base, times(1)).writeAllOutputs(anyDouble())
                verify(f.base, never()).safeAll()
                verify(f.base, never()).safeHardware()
            }
        } finally { RobotClock.useSystemTime() }
    }

    @Test fun aSharedFrameFailureSkipsSeasonReadsWritesAndTelemetry() {
        val f = RuntimeFixture()
        val failure = AssertionError("shared refresh failed")
        doThrow(failure).`when`(f.base).update(null, null)
        assertSame(failure, assertThrows(Throwable::class.java) { f.robot.update() })
        assertSame(failure, f.robot.fatalUpdateFailure)
        verify(f.base, never()).readAllSensors(anyLong())
        verify(f.base, never()).writeAllOutputs(anyDouble())
        verifyNoInteractions(f.telemetry)
        verify(f.base).safeAll(); verify(f.base).safeHardware()
    }

    @Test fun aLatchedSharedFailureRejectsEveryCommandBeforeTheController() {
        val f = RuntimeFixture()
        val failure = IllegalStateException("shared latch")
        doReturn(failure).`when`(f.base).fatalUpdateFailure
        val driver = mock(AresGamepad::class.java)
        val commands = listOf<() -> Unit>(
            { f.robot.driveFieldCentric(0.5, -0.5, 0.25) },
            { f.robot.driveWithGamepad(driver) },
            { f.robot.resetPoseForAlliance() },
            { f.robot.toggleAlliance() },
            { f.robot.enableCalibrationMode() },
            { f.robot.update() },
        )
        commands.forEach { assertSame(failure, assertThrows(Throwable::class.java) { it() }) }
        verifyNoInteractions(f.drive, f.superstructure, f.telemetry)
        verify(f.base, never()).enableCalibrationMode()
        verify(f.base, never()).update(null, null)
        verify(f.base).safeAll(); verify(f.base).safeHardware()
    }

    @Test fun closedFacadeRejectsRemainingCommandsAndUpdate() {
        val f = RuntimeFixture()
        val driver = mock(AresGamepad::class.java)
        f.robot.close()
        clearInvocations(f.base)
        listOf<() -> Unit>(
            { f.robot.driveWithGamepad(driver) }, { f.robot.resetPoseForAlliance() },
            { f.robot.toggleAlliance() }, { f.robot.update() },
        ).forEach { assertThrows(IllegalStateException::class.java) { it() } }
        verifyNoInteractions(f.base, f.drive, f.superstructure, f.telemetry)
    }

    @Test fun healthyCommandsPreserveArgumentsAndDelegateExactlyOnce() {
        val f = RuntimeFixture()
        val driver = mock(AresGamepad::class.java)
        f.robot.driveFieldCentric(0.5, -0.75, 0.25)
        f.robot.driveWithGamepad(driver, false)
        f.robot.resetPoseForAlliance()
        f.robot.toggleAlliance()
        f.robot.addTelemetry("Custom", 17)
        verify(f.drive).driveFieldCentric(0.5, -0.75, 0.25)
        verify(f.drive).driveWithGamepad(driver, false)
        verify(f.drive).resetPoseForAlliance()
        verify(f.superstructure).toggleAlliance()
        verify(f.telemetry).addTelemetry("Custom", 17)
        verifyNoMoreInteractions(f.drive, f.superstructure, f.telemetry)
    }

    @Test fun calibrationArmsInOrderAndRollsBackFailedEnable() {
        val f = RuntimeFixture()
        f.robot.enableCalibrationMode()
        inOrder(f.base).apply {
            verify(f.base).isLiveTuningEnabled = true
            verify(f.base).enableCalibrationMode()
        }
        val failure = IllegalStateException("calibration enable failed")
        doThrow(failure).`when`(f.base).enableCalibrationMode()
        clearInvocations(f.base)
        assertSame(failure, assertThrows(Throwable::class.java) { f.robot.enableCalibrationMode() })
        inOrder(f.base).apply {
            verify(f.base).isLiveTuningEnabled = true
            verify(f.base).enableCalibrationMode()
            verify(f.base).isLiveTuningEnabled = false
        }
    }

    @Test fun shutdownContinuesAfterFailedDisarmAndRetainsBothFailures() {
        val f = RuntimeFixture()
        val first = IllegalStateException("disarm failed")
        val second = AssertionError("shared close failed")
        doThrow(first).`when`(f.base).disableCalibrationMode()
        doThrow(second).`when`(f.base).close()
        assertSame(first, assertThrows(Throwable::class.java) { f.robot.close() })
        assertEquals(listOf(second), first.suppressed.toList())
        verify(f.base).isLiveTuningEnabled = false
        verify(f.base).close()
        f.robot.close()
        verify(f.base, times(1)).close()
        assertThrows(IllegalStateException::class.java) { f.robot.update() }
    }

    @Test fun shutdownPreservesInterruptAndIgnoresSelfSuppression() {
        val f = RuntimeFixture()
        val failure = InterruptedException("stop requested")
        doAnswer { throw failure }.`when`(f.base).disableCalibrationMode()
        doAnswer { throw failure }.`when`(f.base).close()
        try {
            assertSame(failure, assertThrows(Throwable::class.java) { f.robot.close() })
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, failure.suppressed.size)
            verify(f.base).close()
        } finally { Thread.interrupted() }
    }

    @Test fun registrationRollbackClosesRepeatedObjectsOnceAndKeepsAcceptedOwnership() {
        val accepted = mock(Subsystem::class.java)
        val rejected = mock(Subsystem::class.java)
        val last = mock(Subsystem::class.java)
        val failure = IllegalStateException("registration failed")
        val cleanup = AssertionError("close failed")
        doThrow(cleanup).`when`(last).close()
        doThrow(failure).`when`(rejected).close()
        val created = listOf(accepted, rejected, accepted, rejected, last)
        assertSame(failure, assertThrows(Throwable::class.java) {
            installGeneratedSuperstructures({ if (it === rejected) throw failure }) { created }
        })
        verify(accepted, never()).close()
        inOrder(last, rejected).apply { verify(last).close(); verify(rejected).close() }
        verify(rejected, times(1)).close()
        assertEquals(listOf(cleanup), failure.suppressed.toList())
    }

    @Test fun successfulRegistrationTransfersOriginalListInOrderWithoutCleanup() {
        val first = mock(Subsystem::class.java)
        val second = mock(Subsystem::class.java)
        val created = listOf(first, second)
        val registered = mutableListOf<Subsystem>()
        val installed = installGeneratedSuperstructures({ registered += it }) { created }
        assertSame(created, installed)
        assertEquals(created, registered)
        verifyNoInteractions(first, second)
    }

    @Test fun successfulConstructionWithMissingFieldDisablesTagsAndClosesNormally() {
        construction({}) { map, constructed ->
            doReturn(mock(Servo::class.java)).`when`(map).get(eq(Servo::class.java), anyString())
            val robot = AresRobot(map)
            try {
                assertFalse(robot.hasCanonicalFieldContract)
                assertTrue(PoseEstimator.activeTags.isEmpty())
                assertEquals("unavailable-ftc-season-field", RobotFieldManager.activeConfig.id)
                verify(constructed.constructed().single(), never()).close()
            } finally { robot.close() }
            verify(constructed.constructed().single(), times(1)).close()
        }
    }
}
