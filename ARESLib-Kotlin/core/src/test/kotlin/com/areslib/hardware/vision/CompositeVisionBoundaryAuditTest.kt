package com.areslib.hardware.vision

import com.areslib.math.geometry.*
import com.areslib.state.VisionMeasurement
import kotlin.test.*

class CompositeVisionBoundaryAuditTest {
    private class Source(var values: List<VisionMeasurement> = emptyList()) : VisionIO, AutoCloseable {
        var connected = true
        var writeValues = true
        var polls = 0
        var closes = 0
        var orientations = 0
        var modes = 0
        var poll: (() -> Unit)? = null
        var closeFailure: Throwable? = null
        var forwardFailure: Throwable? = null
        override fun updateInputs(inputs: VisionIOInputs) {
            polls++
            poll?.invoke()
            inputs.isConnected = connected
            if (writeValues) inputs.measurements = values
        }
        override fun setOrientation(yawDegrees: Double, yawRateDegPerSec: Double, pitchDegrees: Double,
            pitchRateDegPerSec: Double, rollDegrees: Double, rollRateDegPerSec: Double, linearVelocityMps: Double) {
            orientations++
            forwardFailure?.let { throw it }
        }
        override fun setImuMode(mode: Int) { modes++; forwardFailure?.let { throw it } }
        override fun close() { closes++; closeFailure?.let { throw it } }
    }

    private fun observation(time: Long = 100L, tags: Int = 1) = VisionMeasurement(timestampMs = time, tagCount = tags)

    @Test fun `constructor owns its child membership`() {
        val first = Source(listOf(observation()))
        val added = Source(listOf(observation(200L)))
        val children = mutableListOf<VisionIO>(first)
        val composite = CompositeVisionIO(children)
        children.add(added)
        val inputs = VisionIOInputs()
        composite.updateInputs(inputs)
        assertEquals(1, first.polls); assertEquals(0, added.polls)
        assertEquals(1, inputs.measurements.size)
    }

    @Test fun `one child identity is polled and closed once despite duplicate entries`() {
        val child = Source(listOf(observation()))
        val composite = CompositeVisionIO(listOf(child, child))
        composite.updateInputs(VisionIOInputs()); composite.close()
        assertEquals(1, child.polls); assertEquals(1, child.closes)
    }

    @Test fun `disconnected and partially written child snapshots cannot replay stale observations`() {
        val child = Source(listOf(observation()))
        val composite = CompositeVisionIO(listOf(child)); val inputs = VisionIOInputs()
        composite.updateInputs(inputs)
        assertEquals(1, inputs.measurements.size)
        child.connected = false
        composite.updateInputs(inputs)
        assertFalse(inputs.isConnected); assertTrue(inputs.measurements.isEmpty())
        child.connected = true; child.writeValues = false
        composite.updateInputs(inputs)
        assertTrue(inputs.isConnected); assertTrue(inputs.measurements.isEmpty())
    }

    @Test fun `failed poll invalidates every output even when the caller had old connected data`() {
        val child = Source(listOf(observation()))
        val composite = CompositeVisionIO(listOf(child)); val inputs = VisionIOInputs()
        composite.updateInputs(inputs)
        val failure = IllegalStateException("poll failed")
        child.poll = { throw failure }
        assertSame(failure, assertFails { composite.updateInputs(inputs) })
        assertFalse(inputs.isConnected); assertTrue(inputs.measurements.isEmpty()); assertTrue(inputs.cameraPoses.isEmpty())
    }

    @Test fun `closed composite cannot restart child polling or orientation writes`() {
        val child = Source(listOf(observation())); val composite = CompositeVisionIO(listOf(child))
        val inputs = VisionIOInputs()
        composite.updateInputs(inputs); composite.close(); composite.close()
        composite.updateInputs(inputs)
        composite.setOrientation(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0); composite.setImuMode(1)
        assertEquals(1, child.polls); assertEquals(1, child.closes)
        assertEquals(0, child.orientations); assertEquals(0, child.modes)
        assertFalse(inputs.isConnected); assertTrue(inputs.measurements.isEmpty())
    }

    @Test fun `close continues after every failure and preserves the primary exception`() {
        val firstFailure = AssertionError("first close")
        val secondFailure = IllegalStateException("second close")
        val first = Source().apply { closeFailure = firstFailure }
        val second = Source().apply { closeFailure = secondFailure }
        val third = Source()
        val composite = CompositeVisionIO(listOf(first, second, third))
        assertSame(firstFailure, assertFails { composite.close() })
        assertEquals(listOf(1, 1, 1), listOf(first.closes, second.closes, third.closes))
        assertTrue(firstFailure.suppressed.contains(secondFailure))
        composite.close()
    }

    @Test fun `close during child polling prevents later polling and publication`() {
        val first = Source(listOf(observation())); val second = Source(listOf(observation(200L)))
        val composite = CompositeVisionIO(listOf(first, second))
        first.poll = { composite.close() }
        val inputs = VisionIOInputs()
        composite.updateInputs(inputs)
        assertEquals(0, second.polls); assertFalse(inputs.isConnected); assertTrue(inputs.measurements.isEmpty())
    }

    @Test fun `orientation failure cannot prevent other cameras receiving their hint`() {
        val failure = IllegalStateException("orientation failed")
        val first = Source().apply { forwardFailure = failure }; val second = Source()
        val composite = CompositeVisionIO(listOf(first, second))
        assertSame(failure, assertFails { composite.setOrientation(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0) })
        assertEquals(1, second.orientations)
    }

    @Test fun `IMU mode failure cannot prevent the other camera changing mode`() {
        val failure = IllegalStateException("mode failed")
        val first = Source().apply { forwardFailure = failure }; val second = Source()
        val composite = CompositeVisionIO(listOf(first, second))
        assertSame(failure, assertFails { composite.setImuMode(4) })
        assertEquals(1, second.modes)
    }

    @Test fun `correlation uses robot capture time rather than incompatible source clock epochs`() {
        val first = observation().apply { captureTimestampMicros = 1_000_000L }
        val better = observation(105L, 2).apply { captureTimestampMicros = 9_000_000L }
        val inputs = VisionIOInputs()
        CompositeVisionIO(listOf(Source(listOf(first)), Source(listOf(better)))).updateInputs(inputs)
        assertEquals(listOf(better), inputs.measurements)
        val later = observation(200L).apply { captureTimestampMicros = better.captureTimestampMicros }
        CompositeVisionIO(listOf(Source(listOf(better, later)))).updateInputs(inputs)
        assertEquals(listOf(better, later), inputs.measurements)
    }

    @Test fun `timestamp conversion and difference overflow cannot reorder or correlate remote times`() {
        val first = observation(Long.MIN_VALUE)
        val middle = observation(0L)
        val last = observation(Long.MAX_VALUE)
        val inputs = VisionIOInputs()
        CompositeVisionIO(listOf(Source(listOf(last, middle, first)))).updateInputs(inputs)
        assertEquals(listOf(first, middle, last), inputs.measurements)
    }

    @Test fun `invalid observation cannot win a correlation window over a usable camera`() {
        val usable = observation()
        val invalid = listOf(
            observation(tags = 10).apply { targetPose.translation.x = Double.NaN },
            observation(tags = 10).apply { targetPose.rotation.q = Quaternion(0.0, 0.0, 0.0, 0.0) },
            observation(tags = 10).apply { averageTagDistanceMeters = Double.NaN },
            observation(tags = 10).apply { ambiguity = Double.NaN },
            observation(tags = 10).apply { latencyMs = Double.POSITIVE_INFINITY }
        )
        for (bad in invalid) {
            val inputs = VisionIOInputs()
            CompositeVisionIO(listOf(Source(listOf(bad, usable)))).updateInputs(inputs)
            assertEquals(listOf(usable), inputs.measurements)
        }
    }

    @Test fun `quality selection retains variance ordering without square overflow or underflow`() {
        for (scale in listOf(1e-200, 1e200, Double.MAX_VALUE / 2.0)) {
            val worse = observation().apply { stdDevXMeters = scale * 2.0; stdDevYMeters = scale * 2.0 }
            val better = observation().apply { stdDevXMeters = scale; stdDevYMeters = scale }
            val inputs = VisionIOInputs()
            CompositeVisionIO(listOf(Source(listOf(worse, better)))).updateInputs(inputs)
            assertEquals(listOf(better), inputs.measurements)
        }
    }

    @Test fun `correlation windows retain stable ties and exact oldest-frame boundaries`() {
        val first = observation(100L)
        val tied = observation(100L)
        val boundary = observation(110L, 2)
        val later = observation(111L)
        val inputs = VisionIOInputs()
        CompositeVisionIO(listOf(Source(listOf(later, first, boundary, tied)))).updateInputs(inputs)
        assertEquals(listOf(boundary, later), inputs.measurements)
        CompositeVisionIO(listOf(Source(listOf(first, tied)))).updateInputs(inputs)
        assertSame(first, inputs.measurements.single())
    }

    @Test fun `variance ordering agrees with an exact represented-number oracle across component scales`() {
        fun score(m: VisionMeasurement): java.math.BigDecimal {
            val x = java.math.BigDecimal(m.stdDevXMeters)
            val y = java.math.BigDecimal(m.stdDevYMeters)
            return x * x + y * y
        }
        for (scale in listOf(Double.MIN_VALUE, 1e-200, 0.1, 1e200, Double.MAX_VALUE / 2.0)) {
            val first = observation().apply { stdDevXMeters = scale * 2.0; stdDevYMeters = scale }
            val second = observation().apply { stdDevXMeters = scale; stdDevYMeters = scale * 2.0 }
            val third = observation().apply { stdDevXMeters = scale; stdDevYMeters = scale }
            for (pair in listOf(listOf(first, second), listOf(first, third), listOf(third, second))) {
                val expected = if (score(pair[0]) <= score(pair[1])) pair[0] else pair[1]
                val inputs = VisionIOInputs()
                CompositeVisionIO(listOf(Source(pair))).updateInputs(inputs)
                assertSame(expected, inputs.measurements.single())
            }
        }
        val worse = observation().apply { stdDevXMeters = Double.MAX_VALUE; stdDevYMeters = Double.MAX_VALUE }
        val better = observation().apply { stdDevXMeters = Double.MAX_VALUE * 0.9; stdDevYMeters = Double.MAX_VALUE * 0.9 }
        val inputs = VisionIOInputs()
        CompositeVisionIO(listOf(Source(listOf(worse, better)))).updateInputs(inputs)
        assertSame(better, inputs.measurements.single())
    }

    @Test fun `recursive poll is rejected and the outer snapshot is neutralized`() {
        val child = Source(listOf(observation())); val composite = CompositeVisionIO(listOf(child))
        val inputs = VisionIOInputs(); val nested = VisionIOInputs(isConnected = true)
        child.poll = { composite.updateInputs(nested) }
        assertFailsWith<IllegalStateException> { composite.updateInputs(inputs) }
        assertEquals(1, child.polls); assertFalse(inputs.isConnected); assertFalse(nested.isConnected)
    }
}
