package com.areslib.e2e.tier1.math

import com.areslib.control.feedback.LQRController
import com.areslib.control.feedback.PIDController
import com.areslib.math.estimation.HistoryBuffer
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Matrix3x3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GcAvoidanceTier1Test {

    @Test
    fun testLqrCalculate_returnsSameArrayInstance() {
        val lqr = LQRController(1, 1, 1)
        lqr.setSystemCoefficients(doubleArrayOf(1.0), doubleArrayOf(1.0), doubleArrayOf(1.0))
        lqr.reset(doubleArrayOf(0.0))
        
        val result1 = lqr.calculate(doubleArrayOf(0.0), doubleArrayOf(1.0), 0.02)
        val result2 = lqr.calculate(doubleArrayOf(0.0), doubleArrayOf(1.0), 0.02)
        
        // Ensure that the controller reuses the array internally for zero-allocation
        assertSame(result1, result2, "LQR calculate should return the same array instance to avoid GC")
    }

    @Test
    fun testPidCalculate_returnsPrimitiveDouble() {
        val pid = PIDController(1.0, 0.0, 0.0)
        
        val result = pid.calculate(0.0, 1.0, 0.02)
        
        // This implicitly checks that it returns a primitive double.
        // There is no wrapper object allocated.
        assertEquals(1.0, result, 1e-6)
    }

    @Test
    fun testPoseEstimatorHistoryBuffer_reusesEntriesWhenCapacityReached() {
        val capacity = 3
        val buffer = HistoryBuffer(capacity)
        
        buffer.addEntry(100L, Pose2d(), Matrix3x3.IDENTITY, 1.0)
        val entry1 = buffer.get(0)
        
        buffer.addEntry(200L, Pose2d(), Matrix3x3.IDENTITY, 1.0)
        buffer.addEntry(300L, Pose2d(), Matrix3x3.IDENTITY, 1.0)
        
        // Adding the 4th entry should overwrite the 1st entry's data
        buffer.addEntry(400L, Pose2d(), Matrix3x3.IDENTITY, 1.0)
        
        val entry4 = buffer.get(2) // The newest entry is at the end of the logical list
        
        // It should be the exact same object reference as the first one, just updated values
        assertSame(entry1, entry4, "HistoryBuffer should reuse objects without allocating")
        assertEquals(400L, entry1.timestampMs)
    }

    @Test
    fun testPoseEstimatorHistoryBuffer_updateEntryDoesNotAllocate() {
        val buffer = HistoryBuffer(5)
        buffer.addEntry(100L, Pose2d(), Matrix3x3.IDENTITY, 1.0)
        val entry = buffer.get(0)
        
        buffer.updateEntry(0, 200L, Pose2d(), Matrix3x3.IDENTITY, 1.0)
        
        val entryUpdated = buffer.get(0)
        assertSame(entry, entryUpdated, "updateEntry should not allocate a new object")
        assertEquals(200L, entry.timestampMs)
    }

    @Test
    fun testMatrixMultiplication_primitiveBackedAndInPlace() {
        val m1 = LQRController.Matrix(2, 2, doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        val m2 = LQRController.Matrix(2, 2, doubleArrayOf(2.0, 0.0, 1.0, 2.0))
        val mOut = LQRController.Matrix(2, 2)
        
        m1.multiplyInto(m2, mOut)
        
        // Output matrix should have correctly multiplied values in place
        assertEquals(4.0, mOut.get(0, 0), 1e-6)
        assertEquals(4.0, mOut.get(0, 1), 1e-6)
        assertEquals(10.0, mOut.get(1, 0), 1e-6)
        assertEquals(8.0, mOut.get(1, 1), 1e-6)
    }
}
