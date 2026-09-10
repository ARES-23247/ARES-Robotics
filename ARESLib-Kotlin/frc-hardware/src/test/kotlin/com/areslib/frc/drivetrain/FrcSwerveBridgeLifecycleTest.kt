package com.areslib.frc.drivetrain

import com.areslib.math.geometry.Pose2d
import com.areslib.state.DriveState
import com.ctre.phoenix6.swerve.SwerveRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class FrcSwerveBridgeLifecycleTest {
    @Test fun `reset failures leave feedback revoked and never bypass a failed brake`() {
        for (brakeFails in listOf(false, true)) withPhoenixBridge { fixture, io ->
            io.refresh()
            val failure = AssertionError("reset boundary")
            if (brakeFails) doThrow(failure).`when`(fixture.drivetrain).setControl(any(SwerveRequest::class.java))
            else doThrow(failure).`when`(fixture.drivetrain).resetPose(any(edu.wpi.first.math.geometry.Pose2d::class.java))
            assertSame(failure, assertThrows(AssertionError::class.java) { io.seedPose(Pose2d()) })
            assertFalse(io.currentMeasurementsValid)
            assertTrue(io.read().odometryX.isNaN())
            verify(fixture.drivetrain, times(1)).setControl(any(SwerveRequest::class.java))
            verify(fixture.drivetrain, times(if (brakeFails) 0 else 1)).resetPose(any(edu.wpi.first.math.geometry.Pose2d::class.java))
        }
    }

    @Test fun `invalid refresh attempts a failing brake exactly once`() = withPhoenixBridge { fixture, io ->
        fixture.state.Timestamp = 90.0
        val failure = AssertionError("brake")
        doThrow(failure).`when`(fixture.drivetrain).setControl(any(SwerveRequest::class.java))
        assertSame(failure, assertThrows(AssertionError::class.java) { io.refresh() })
        verify(fixture.drivetrain, times(1)).setControl(any(SwerveRequest::class.java))
    }

    @Test fun `refresh immediately brakes when an active drivetrain loses feedback`() = withPhoenixBridge { fixture, io ->
        var observed: SwerveRequest? = null
        doAnswer { observed = it.getArgument(0); null }.`when`(fixture.drivetrain).setControl(any(SwerveRequest::class.java))
        io.refresh()
        io.write(DriveState(xVelocityMetersPerSecond = 1.0), 1.0)
        assertInstanceOf(SwerveRequest.FieldCentric::class.java, observed)
        fixture.state.Timestamp = 90.0
        io.refresh()
        assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, observed)
    }

    @Test fun `pose reset revokes the old snapshot and brakes until feedback is refreshed`() = withPhoenixBridge { fixture, io ->
        var observed: SwerveRequest? = null
        doAnswer { observed = it.getArgument(0); null }.`when`(fixture.drivetrain).setControl(any(SwerveRequest::class.java))
        io.refresh()
        io.write(DriveState(xVelocityMetersPerSecond = 1.0), 1.0)
        io.seedPose(Pose2d(2.0, 3.0))
        assertTrue(io.read().odometryX.isNaN())
        assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, observed)
        io.write(DriveState(xVelocityMetersPerSecond = 1.0), 1.0)
        assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, observed)
        io.refresh()
        assertTrue(io.read().odometryX.isFinite())
    }

    @Test fun `all reported motor faults deny motion while healthy feedback restores it`() = withPhoenixBridge { fixture, io ->
        var observed: SwerveRequest? = null
        doAnswer { observed = it.getArgument(0); null }.`when`(fixture.drivetrain).setControl(any(SwerveRequest::class.java))
        val command = DriveState(xVelocityMetersPerSecond = 1.0, isFieldCentric = false)
        for ((name, signal) in fixture.clones) {
            if (!name.contains("hardware") && !name.contains("brownout") && !name.contains("temperature")) continue
            `when`(signal.valueAsDouble).thenReturn(1.0)
            io.refresh()
            assertTrue(io.currentMeasurementsValid, "a reported fault is still a valid measurement")
            io.write(command, 1.0)
            assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, observed, name)
            `when`(signal.valueAsDouble).thenReturn(0.0)
        }
        io.refresh()
        io.write(command, 1.0)
        assertInstanceOf(SwerveRequest.ApplyRobotSpeeds::class.java, observed)
    }

    @Test fun `bad motion snapshot independently denies otherwise fresh signal feedback`() = withPhoenixBridge { fixture, io ->
        var observed: SwerveRequest? = null
        doAnswer { observed = it.getArgument(0); null }.`when`(fixture.drivetrain).setControl(any(SwerveRequest::class.java))
        fixture.state.Timestamp = 90.0
        io.refresh()
        assertTrue(io.currentMeasurementsValid)
        io.write(DriveState(xVelocityMetersPerSecond = 1.0), 1.0)
        assertInstanceOf(SwerveRequest.SwerveDriveBrake::class.java, observed)
    }

    @Test fun `refresh exceptions revoke feedback and attempt brake without masking original failure`() = withPhoenixBridge { fixture, io ->
        for (same in listOf(false, true)) {
            io.refresh()
            val primary = AssertionError("refresh")
            val cleanup = if (same) primary else IllegalStateException("brake")
            doThrow(primary).`when`(fixture.clones.getValue("fl/current")).refresh()
            doThrow(cleanup).`when`(fixture.drivetrain).setControl(any(SwerveRequest::class.java))
            assertSame(primary, assertThrows(AssertionError::class.java) { io.refresh() })
            assertFalse(io.currentMeasurementsValid)
            assertTrue(io.read().odometryX.isNaN())
            assertEquals(if (same) 0 else 1, primary.suppressed.size)
            if (!same) assertSame(cleanup, primary.suppressed.single())
            doReturn(fixture.clones.getValue("fl/current")).`when`(fixture.clones.getValue("fl/current")).refresh()
            doNothing().`when`(fixture.drivetrain).setControl(any(SwerveRequest::class.java))
        }
        verify(fixture.drivetrain, times(2)).setControl(any(SwerveRequest::class.java))
    }

    @Test fun `failed cleanup attempts both resources once and closed native operations remain forbidden`() = withPhoenixBridge { fixture, io ->
        val primary = AssertionError("brake")
        val cleanup = IllegalStateException("native close")
        doThrow(primary).`when`(fixture.drivetrain).setControl(any(SwerveRequest::class.java))
        doThrow(cleanup).`when`(fixture.drivetrain).close()
        assertSame(primary, assertThrows(AssertionError::class.java) { io.close() })
        assertSame(cleanup, primary.suppressed.single())
        io.close(); io.safe()
        verify(fixture.drivetrain, times(1)).close()
        clearInvocations(fixture.drivetrain)
        assertThrows(IllegalStateException::class.java) { io.refresh() }
        assertThrows(IllegalStateException::class.java) { io.seedPose(Pose2d()) }
        assertThrows(IllegalStateException::class.java) { io.addVisionMeasurement(Pose2d(), 100.0) }
        assertThrows(IllegalStateException::class.java) { io.addVisionMeasurement(Pose2d(), 100.0, 1.0, 1.0, 1.0) }
        val sample = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertFalse(io.samplePoseAt(100.0, sample))
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0), sample)
        verifyNoInteractions(fixture.drivetrain)
    }

    @Test fun `native close failure alone remains primary and is never retried`() = withPhoenixBridge { fixture, io ->
        val failure = AssertionError("native close")
        doThrow(failure).`when`(fixture.drivetrain).close()
        assertSame(failure, assertThrows(AssertionError::class.java) { io.close() })
        io.close()
        verify(fixture.drivetrain, times(1)).close()
    }

    @Test fun `close waits for in-flight write then brakes and rejects later writes`() = withPhoenixBridge { fixture, io ->
        io.refresh()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        doAnswer {
            if (it.getArgument<SwerveRequest>(0) is SwerveRequest.SwerveDriveBrake) events.add("brake")
            else {
                events.add("write-start"); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                events.add("write-end")
            }
            null
        }.`when`(fixture.drivetrain).setControl(any(SwerveRequest::class.java))
        doAnswer { events.add("close"); null }.`when`(fixture.drivetrain).close()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val write = executor.submit { io.write(DriveState(xVelocityMetersPerSecond = 1.0), 1.0) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val close = executor.submit { closeStarted.countDown(); io.close() }
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { close.get(100, TimeUnit.MILLISECONDS) }
            verify(fixture.drivetrain, never()).close()
            release.countDown()
            write.get(5, TimeUnit.SECONDS); close.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("write-start", "write-end", "brake", "close"), events)
            assertThrows(IllegalStateException::class.java) { io.write(DriveState(), 0.0) }
        } finally {
            release.countDown(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `concurrent closes release native resource only once`() = withPhoenixBridge { fixture, io ->
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val calls = List(4) { executor.submit { check(start.await(5, TimeUnit.SECONDS)); io.close() } }
            start.countDown()
            calls.forEach { it.get(5, TimeUnit.SECONDS) }
            verify(fixture.drivetrain, times(1)).close()
            verify(fixture.drivetrain, times(1)).setControl(any(SwerveRequest::class.java))
        } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

}
