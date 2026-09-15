package com.areslib.xrp.hardware

import com.areslib.math.geometry.ChassisSpeeds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XrpDifferentialOutputAuditTest {
    private class Motor(override val channel: Int) : XrpMotorIO {
        var rejectActive = false
        var rejectStop = false
        var rejectUpdate = false
        var stops = 0
        override var effort = 0.0
            set(value) {
                if (rejectActive && value != 0.0) throw IllegalStateException("write failed")
                field = value
            }
        override val positionRadians = 0.0
        override val velocityRadiansPerSecond = 0.0
        override fun update() { if (rejectUpdate) throw IllegalStateException("update failed") }
        override fun stop() {
            stops++
            if (rejectStop) throw IllegalStateException("stop failed")
            effort = 0.0
        }
    }

    @Test fun `saturated drive preserves requested wheel ratio`() {
        val left = Motor(1); val right = Motor(2)
        val drive = StandardXrpDifferentialHardwareIO(left, right)
        drive.drive(ChassisSpeeds(1.275, 0.0, 0.85 / 0.155), 0.85)
        assertEquals(0.5, left.effort, 2e-15)
        assertEquals(1.0, right.effort, 2e-15)
    }

    @Test fun `invalid paired power neutralizes both motors`() {
        val left = Motor(1); val right = Motor(2)
        val drive = StandardXrpDifferentialHardwareIO(left, right)
        drive.setPowers(0.4, 0.5)
        drive.setPowers(Double.NaN, 0.5)
        assertEquals(0.0, left.effort)
        assertEquals(0.0, right.effort)
    }

    @Test fun `failed second write neutralizes the first motor before propagating`() {
        val left = Motor(1); val right = Motor(2).apply { rejectActive = true }
        val drive = StandardXrpDifferentialHardwareIO(left, right)
        assertFailsWith<IllegalStateException> { drive.setPowers(0.7, 0.8) }
        assertEquals(0.0, left.effort)
        assertEquals(0.0, right.effort)
    }

    @Test fun `failed first stop still attempts the second motor`() {
        val left = Motor(1).apply { rejectStop = true }; val right = Motor(2)
        val drive = StandardXrpDifferentialHardwareIO(left, right)
        assertFailsWith<IllegalStateException> { drive.stop() }
        assertEquals(1, left.stops)
        assertEquals(1, right.stops)
    }

    @Test fun `standard drive rejects invalid wheel radius`() {
        for (radius in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { StandardXrpDifferentialHardwareIO(wheelRadiusMeters = radius) }
        }
    }

    @Test fun `failed refresh stops both motors before propagating incomplete feedback`() {
        val left = Motor(1); val right = Motor(2)
        val drive = StandardXrpDifferentialHardwareIO(left, right)
        drive.setPowers(0.4, 0.5)
        left.rejectUpdate = true
        assertFailsWith<IllegalStateException> { drive.update() }
        assertEquals(0.0, left.effort)
        assertEquals(0.0, right.effort)
        assertEquals(1, left.stops)
        assertEquals(1, right.stops)
    }

    @Test fun `both stop failures retain the first and suppress the second`() {
        val left = Motor(1).apply { rejectStop = true }
        val right = Motor(2).apply { rejectStop = true }
        val failure = assertFailsWith<IllegalStateException> { StandardXrpDifferentialHardwareIO(left, right).stop() }
        assertEquals(1, left.stops)
        assertEquals(1, right.stops)
        assertEquals(1, failure.suppressed.size)
    }

    @Test fun `invalid chassis fields and limits neutralize the paired drive`() {
        val left = Motor(1); val right = Motor(2)
        val drive = StandardXrpDifferentialHardwareIO(left, right)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (speeds in listOf(ChassisSpeeds(invalid, 0.0, 0.0), ChassisSpeeds(0.0, invalid, 0.0), ChassisSpeeds(0.0, 0.0, invalid))) {
                drive.setPowers(0.5, 0.5)
                drive.drive(speeds)
                assertEquals(0.0, left.effort)
                assertEquals(0.0, right.effort)
            }
        }
        for (limit in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            drive.setPowers(0.5, 0.5)
            drive.drive(ChassisSpeeds(1.0, 0.0, 1.0), limit)
            assertEquals(0.0, left.effort)
            assertEquals(0.0, right.effort)
        }
    }
}
