package com.areslib.frc.drivetrain

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import edu.wpi.first.math.Matrix
import edu.wpi.first.math.numbers.N1
import edu.wpi.first.math.numbers.N3
import java.util.Optional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class FrcSwerveBridgeVisionTest {
    @Test fun `all nonfinite pose axes and timestamps reject both vision overloads and seeds`() = withPhoenixBridge { fixture, io ->
        for (invalid in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val poses = listOf(Pose2d(invalid, 1.0), Pose2d(1.0, invalid), Pose2d(1.0, 2.0, Rotation2d(invalid)))
            clearInvocations(fixture.drivetrain)
            for (pose in poses) {
                assertThrows(IllegalArgumentException::class.java) { io.seedPose(pose) }
                assertThrows(IllegalArgumentException::class.java) { io.addVisionMeasurement(pose, 100.0) }
                assertThrows(IllegalArgumentException::class.java) { io.addVisionMeasurement(pose, 100.0, 0.5, 0.7, 0.3) }
            }
            assertThrows(IllegalArgumentException::class.java) { io.addVisionMeasurement(Pose2d(), invalid) }
            assertThrows(IllegalArgumentException::class.java) { io.addVisionMeasurement(Pose2d(), invalid, 0.5, 0.7, 0.3) }
            verifyNoInteractions(fixture.drivetrain)
        }
    }

    @Test fun `each invalid covariance axis fails without updating vendor history`() = withPhoenixBridge { fixture, io ->
        clearInvocations(fixture.drivetrain)
        for (invalid in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -0.0, -1.0)) {
            for (axis in 0 until 3) {
                val sd = doubleArrayOf(0.5, 0.7, 0.3); sd[axis] = invalid
                assertThrows(IllegalArgumentException::class.java) { io.addVisionMeasurement(Pose2d(), 100.0, sd[0], sd[1], sd[2]) }
            }
        }
        verifyNoInteractions(fixture.drivetrain)
    }

    @Test fun `valid estimator inputs preserve units timebase and covariance workspace reuse`() = withPhoenixBridge { fixture, io ->
        val pose = Pose2d(-2.5, 1.25, Rotation2d(-0.7))
        io.seedPose(pose)
        val nativePose = argumentCaptor<edu.wpi.first.math.geometry.Pose2d>()
        verify(fixture.drivetrain).resetPose(nativePose.capture())
        assertEquals(pose.x, nativePose.value.x); assertEquals(pose.y, nativePose.value.y)
        assertEquals(pose.heading.radians, nativePose.value.rotation.radians)
        io.addVisionMeasurement(pose, -0.25)
        verify(fixture.drivetrain).addVisionMeasurement(nativePose.capture(), eq(-0.25))
        var firstMatrix: Matrix<N3, N1>? = null
        val observed = mutableListOf<List<Double>>()
        doAnswer {
            val matrix = it.getArgument<Matrix<N3, N1>>(2)
            if (firstMatrix == null) firstMatrix = matrix else assertSame(firstMatrix, matrix)
            observed.add(listOf(it.getArgument<Double>(1), matrix[0, 0], matrix[1, 0], matrix[2, 0]))
            null
        }.`when`(fixture.drivetrain).addVisionMeasurement(any(edu.wpi.first.math.geometry.Pose2d::class.java), anyDouble(), any())
        io.addVisionMeasurement(pose, 0.0, 0.2, 0.3, 1.0e6)
        io.addVisionMeasurement(pose, 99.75, 0.4, 0.5, 0.6)
        assertEquals(listOf(listOf(0.0, 0.2, 0.3, 1.0e6), listOf(99.75, 0.4, 0.5, 0.6)), observed)
    }

    @Test fun `history publishes only finite complete poses and preserves caller tails`() = withPhoenixBridge { fixture, io ->
        val output = doubleArrayOf(7.0, 8.0, 9.0, 10.0)
        for (invalid in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (axis in 0 until 3) {
                val coordinates = doubleArrayOf(1.0, 2.0, 0.5); coordinates[axis] = invalid
                val nativePose = mock(edu.wpi.first.math.geometry.Pose2d::class.java)
                `when`(nativePose.x).thenReturn(coordinates[0]); `when`(nativePose.y).thenReturn(coordinates[1])
                `when`(nativePose.rotation).thenReturn(edu.wpi.first.math.geometry.Rotation2d(coordinates[2]))
                `when`(fixture.drivetrain.samplePoseAt(99.0)).thenReturn(Optional.of(nativePose))
                assertFalse(io.samplePoseAt(99.0, output))
                assertArrayEquals(doubleArrayOf(7.0, 8.0, 9.0, 10.0), output)
            }
        }
        `when`(fixture.drivetrain.samplePoseAt(99.0)).thenReturn(Optional.empty())
        assertFalse(io.samplePoseAt(99.0, output))
        `when`(fixture.drivetrain.samplePoseAt(99.0)).thenReturn(Optional.of(
            edu.wpi.first.math.geometry.Pose2d(-2.0, 3.0, edu.wpi.first.math.geometry.Rotation2d(-0.5))))
        assertTrue(io.samplePoseAt(99.0, output))
        assertArrayEquals(doubleArrayOf(-2.0, 3.0, -0.5, 10.0), output)
    }

    @Test fun `bad history arguments and native exceptions never alter output`() = withPhoenixBridge { fixture, io ->
        clearInvocations(fixture.drivetrain)
        val output = doubleArrayOf(7.0, 8.0, 9.0)
        assertThrows(IllegalArgumentException::class.java) { io.samplePoseAt(100.0, DoubleArray(2)) }
        for (invalid in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(io.samplePoseAt(invalid, output))
        }
        verifyNoInteractions(fixture.drivetrain)
        val failure = AssertionError("history")
        `when`(fixture.drivetrain.samplePoseAt(99.0)).thenThrow(failure)
        assertSame(failure, assertThrows(AssertionError::class.java) { io.samplePoseAt(99.0, output) })
        assertArrayEquals(doubleArrayOf(7.0, 8.0, 9.0), output)
    }

    private inline fun <reified T> argumentCaptor() = org.mockito.ArgumentCaptor.forClass(T::class.java)
}
