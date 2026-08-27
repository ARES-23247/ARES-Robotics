package com.areslib.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnalogBindingTest {
    @Test
    fun `slew limiting zones and disconnect cleanup are deterministic`() {
        val values = ArrayList<Double>()
        val zones = RecordingZoneListener()
        val binding = AnalogBinding(
            axisIndex = 0,
            transform = AxisTransform.trigger(),
            listener = object : AnalogBindingListener {
                override fun onValue(value: Double) {
                    values.add(value)
                }
            },
            zones = listOf(AnalogZone("prepare", 0.4, 0.6, hysteresis = 0.05, listener = zones)),
            riseRatePerSecond = 1.0,
            fallRatePerSecond = 2.0,
        )
        val frame = InputFrame(1, 1)
        val runtime = ControllerBindingRuntime(analogBindings = listOf(binding))

        setAxis(frame, 1.0)
        runtime.update(frame, 0L)
        assertEquals(0.0, binding.value, 1e-12)

        setAxis(frame, 1.0)
        runtime.update(frame, 500_000_000L)
        assertEquals(0.5, binding.value, 1e-12)
        assertEquals(1, zones.enterCount)
        assertEquals(1, zones.activeCount)

        setAxis(frame, 0.0)
        runtime.update(frame, 1_000_000_000L)
        assertEquals(0.0, binding.value, 1e-12)
        assertEquals(1, zones.exitCount)

        setAxis(frame, 1.0)
        runtime.update(frame, 1_500_000_000L)
        setAxis(frame, 0.5)
        runtime.update(frame, 2_000_000_000L)
        frame.beginSample(false, sampleTimeNanos = 2_100_000_000L)
        runtime.update(frame, 2_100_000_000L)

        assertEquals(0.0, values.last(), 1e-12, "disconnect explicitly commands neutral output")
        assertEquals(2, zones.exitCount, "disconnect exits an active zone")
    }

    @Test
    fun `analog input must return neutral before reconnecting`() {
        val values = ArrayList<Double>()
        val binding = AnalogBinding(
            axisIndex = 0,
            transform = AxisTransform.trigger(),
            listener = object : AnalogBindingListener {
                override fun onValue(value: Double) {
                    values.add(value)
                }
            },
        )
        val frame = InputFrame(1, 1)
        val runtime = ControllerBindingRuntime(analogBindings = listOf(binding))

        setAxis(frame, 1.0)
        runtime.update(frame, 0L)
        frame.beginSample(false, sampleTimeNanos = 1L)
        runtime.update(frame, 1L)
        val afterDisconnect = values.size

        setAxis(frame, 1.0)
        runtime.update(frame, 2L)
        assertEquals(afterDisconnect, values.size, "held trigger is suppressed after reconnect")

        setAxis(frame, 0.0)
        runtime.update(frame, 3L)
        setAxis(frame, 0.5)
        runtime.update(frame, 4L)
        assertEquals(0.5, values.last(), 1e-12)
    }

    private fun setAxis(frame: InputFrame, value: Double) {
        frame.beginSample(true, 1, 0, 0L)
        frame.setAxis(0, value)
    }
}

private class RecordingZoneListener : AnalogZoneListener {
    var enterCount = 0
    var activeCount = 0
    var exitCount = 0

    override fun onEnter(value: Double) {
        enterCount++
    }

    override fun onActive(value: Double) {
        activeCount++
    }

    override fun onExit(value: Double) {
        exitCount++
    }
}
