package com.areslib.input

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class InputLoopAllocationAuditTest {
    @Test fun `reused sampling copying shaping chords and cancellation have bounded allocation`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        val source = InputFrame(1, 2)
        val copy = InputFrame(1, 2)
        val suppression = ButtonSuppressionState(2)
        var presses = 0
        var values = 0
        var zoneEntries = 0
        val digitalListener = object : DigitalBindingListener {
            override fun onPress() { presses++ }
        }
        val runtime = ControllerBindingRuntime(
            listOf(
                DigitalBinding(SuppressingButtonChordSource(intArrayOf(0, 1), 75L, suppression), listener = digitalListener),
                DigitalBinding(SuppressibleButtonSource(0, suppression), listener = digitalListener),
                DigitalBinding(AxisThresholdSource(0, 0.7, 0.5), listener = digitalListener)
            ),
            listOf(AnalogBinding(0, listener = object : AnalogBindingListener {
                override fun onValue(value: Double) { values++ }
            }, zones = listOf(AnalogZone("positive", 0.5, 1.0, listener = object : AnalogZoneListener {
                override fun onEnter(value: Double) { zoneEntries++ }
            })), riseRatePerSecond = 50.0, fallRatePerSecond = 50.0)),
            nanoTime = { 0L }
        )
        fun loop(start: Int, end: Int) {
            for (i in start until end) {
                val phase = i % 8
                source.beginSample(phase != 7, 1, 2, i.toLong())
                if (source.isConnected) {
                    source.setAxis(0, if (phase >= 3) 1.0 else 0.0)
                    source.setButton(0, phase in 1..4)
                    source.setButton(1, phase in 2..4)
                }
                copy.copyFrom(source)
                runtime.update(copy, i * 20_000_000L)
                if (phase == 6) runtime.cancel()
            }
        }
        loop(0, 100_000)
        val before = bean.getThreadAllocatedBytes(threadId)
        val start = System.nanoTime()
        loop(100_000, 110_000)
        val elapsed = System.nanoTime() - start
        val allocated = bean.getThreadAllocatedBytes(threadId) - before
        println("Input loop: $allocated bytes / 10,000 updates, ${elapsed / 10_000.0} ns/update (desktop JVM)")
        assertTrue(allocated <= 4096L, "Input loop allocated $allocated bytes")
        assertTrue(presses > 0 && values > 0 && zoneEntries > 0)
    }
}
