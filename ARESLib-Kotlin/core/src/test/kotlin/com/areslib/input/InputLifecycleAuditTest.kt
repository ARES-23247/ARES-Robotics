package com.areslib.input

import com.areslib.controls.controlScheme
import kotlin.test.*

class InputLifecycleAuditTest {
    private fun frame(first: Boolean = true, second: Boolean = true, axis: Double = 1.0) = InputFrame(1, 2).apply {
        beginSample(true, 1, 2, 0L)
        setAxis(0, axis)
        setButton(0, first)
        setButton(1, second)
    }

    @Test fun `failed update neutralizes earlier bindings and preserves the primary exception`() {
        val primary = IllegalStateException("press failed")
        val cleanup = IllegalArgumentException("release failed")
        var releases = 0
        val first = DigitalBinding(RawButtonSource(0), listener = object : DigitalBindingListener {
            override fun onRelease(heldForNanos: Long, reason: BindingReleaseReason) { releases++; throw cleanup }
        })
        val second = DigitalBinding(RawButtonSource(1), listener = object : DigitalBindingListener {
            override fun onPress() { throw primary }
        })
        val runtime = ControllerBindingRuntime(listOf(first, second), nanoTime = { 1L })
        assertSame(primary, assertFails { runtime.update(frame(), 0L) })
        assertFalse(first.isActive); assertFalse(second.isActive)
        assertEquals(1, releases)
        assertTrue(primary.suppressed.contains(cleanup))
        runtime.cancel()
        assertEquals(1, releases)
    }

    @Test fun `cancellation attempts every digital release zone exit and analog neutral`() {
        var digitalReleases = 0
        var exits = 0
        var output = 0.0
        val failure = IllegalStateException("cleanup failed")
        val first = DigitalBinding(RawButtonSource(0), listener = object : DigitalBindingListener {
            override fun onRelease(heldForNanos: Long, reason: BindingReleaseReason) { digitalReleases++; throw failure }
        })
        val second = DigitalBinding(RawButtonSource(1), listener = object : DigitalBindingListener {
            override fun onRelease(heldForNanos: Long, reason: BindingReleaseReason) { digitalReleases++ }
        })
        val analog = AnalogBinding(0, listener = object : AnalogBindingListener {
            override fun onValue(value: Double) { output = value }
        }, zones = listOf(AnalogZone("a", 0.5, 1.0, listener = object : AnalogZoneListener {
            override fun onExit(value: Double) { exits++; throw failure }
        }), AnalogZone("b", 0.5, 1.0, listener = object : AnalogZoneListener {
            override fun onExit(value: Double) { exits++ }
        })))
        val runtime = ControllerBindingRuntime(listOf(first, second), listOf(analog), nanoTime = { 1L })
        runtime.update(frame(), 0L)
        assertSame(failure, assertFails { runtime.cancel() })
        assertEquals(2, digitalReleases); assertEquals(2, exits)
        assertEquals(0.0, output); assertEquals(0.0, analog.value)
        assertFalse(first.isActive); assertFalse(second.isActive)
        runtime.cancel()
        assertEquals(2, digitalReleases); assertEquals(2, exits)
    }

    @Test fun `failed chord reset still clears every child and releases the accepted activation`() {
        var resets = 0
        var releases = 0
        val first = object : DigitalSource {
            override fun sample(frame: InputFrame, nowNanos: Long) = true
            override fun reset() { resets++; error("reset failed") }
        }
        val second = object : DigitalSource {
            override fun sample(frame: InputFrame, nowNanos: Long) = true
            override fun reset() { resets++ }
        }
        val binding = DigitalBinding(ChordSource(listOf(first, second)), listener = object : DigitalBindingListener {
            override fun onRelease(heldForNanos: Long, reason: BindingReleaseReason) { releases++ }
        })
        val runtime = ControllerBindingRuntime(listOf(binding), nanoTime = { 1L })
        runtime.update(frame(), 0L)
        assertFails { runtime.cancel() }
        assertEquals(2, resets); assertEquals(1, releases); assertFalse(binding.isActive)
    }

    @Test fun `cancelling from press prevents later bindings from activating`() {
        lateinit var runtime: ControllerBindingRuntime
        var laterPresses = 0
        val first = DigitalBinding(RawButtonSource(0), listener = object : DigitalBindingListener {
            override fun onPress() { runtime.cancel() }
        })
        val later = DigitalBinding(RawButtonSource(1), listener = object : DigitalBindingListener {
            override fun onPress() { laterPresses++ }
        })
        runtime = ControllerBindingRuntime(listOf(first, later), nanoTime = { 0L })
        runtime.update(frame(), 0L)
        assertFalse(first.isActive); assertFalse(later.isActive); assertEquals(0, laterPresses)
    }

    @Test fun `cancelling from held prevents hold and repeat callbacks`() {
        lateinit var runtime: ControllerBindingRuntime
        var laterCallbacks = 0
        val binding = DigitalBinding(RawButtonSource(0), DigitalBindingTiming(holdAfterNanos = 0L, repeatAfterNanos = 0L, repeatEveryNanos = 1L), object : DigitalBindingListener {
            override fun onHeld(heldForNanos: Long) { runtime.cancel() }
            override fun onHold(heldForNanos: Long) { laterCallbacks++ }
            override fun onRepeat(heldForNanos: Long) { laterCallbacks++ }
        })
        runtime = ControllerBindingRuntime(listOf(binding), nanoTime = { 0L })
        runtime.update(frame(), 0L)
        assertEquals(0, laterCallbacks)
    }

    @Test fun `cancelling from analog output prevents zone activation`() {
        lateinit var runtime: ControllerBindingRuntime
        var enters = 0
        val binding = AnalogBinding(0, listener = object : AnalogBindingListener {
            override fun onValue(value: Double) { if (value != 0.0) runtime.cancel() }
        }, zones = listOf(AnalogZone("a", 0.5, 1.0, listener = object : AnalogZoneListener {
            override fun onEnter(value: Double) { enters++ }
        })))
        runtime = ControllerBindingRuntime(analogBindings = listOf(binding), nanoTime = { 0L })
        runtime.update(frame(), 0L)
        assertEquals(0, enters); assertEquals(0.0, binding.value)
    }

    @Test fun `release callback may cancel without duplicate release`() {
        lateinit var runtime: ControllerBindingRuntime
        var releases = 0
        val binding = DigitalBinding(RawButtonSource(0), listener = object : DigitalBindingListener {
            override fun onRelease(heldForNanos: Long, reason: BindingReleaseReason) {
                releases++
                if (releases == 1) runtime.cancel()
            }
        })
        runtime = ControllerBindingRuntime(listOf(binding), nanoTime = { 1L })
        runtime.update(frame(), 0L)
        runtime.update(frame(first = false), 1L)
        assertEquals(1, releases); assertFalse(binding.isActive)
    }

    @Test fun `clock failure during cancellation still releases outputs`() {
        val failure = IllegalStateException("clock failed")
        var output = 0.0
        val binding = AnalogBinding(0, listener = object : AnalogBindingListener {
            override fun onValue(value: Double) { output = value }
        })
        val runtime = ControllerBindingRuntime(analogBindings = listOf(binding), nanoTime = { throw failure })
        runtime.update(frame(), 0L)
        assertSame(failure, assertFails { runtime.cancel() })
        assertEquals(0.0, output); assertEquals(0.0, binding.value)
    }

    @Test fun `cancelled chords require every member to return to neutral`() {
        for (source in listOf(ChordSource.rawButtons(0, 1), SuppressingButtonChordSource(intArrayOf(0, 1), 75L, ButtonSuppressionState(2)))) {
            var presses = 0
            val binding = DigitalBinding(source, listener = object : DigitalBindingListener {
                override fun onPress() { presses++ }
            })
            val runtime = ControllerBindingRuntime(listOf(binding), nanoTime = { 1L })
            runtime.update(frame(), 0L)
            runtime.cancel()
            runtime.update(frame(second = false), 2L)
            runtime.update(frame(), 3L)
            assertEquals(1, presses)
            runtime.update(frame(first = false, second = false), 4L)
            runtime.update(frame(), 5L)
            assertEquals(2, presses)
        }
    }

    @Test fun `long valid controller slots produce distinct valid generated binding identifiers`() {
        val scheme = controlScheme("test", "Test") {
            controller("d".repeat(64), "vader5-pro", 0) {
                button("a").onPress { action("one") }
                button("b").onPress { action("two") }
            }
        }
        assertEquals(2, scheme.bindings.map { it.bindingId }.toSet().size)
        assertTrue(scheme.bindings.all { it.bindingId.length <= 64 })
    }

    @Test fun `a throwing zone entry is unwound exactly once`() {
        var exits = 0
        var output = 0.0
        val failure = IllegalStateException("enter failed")
        val binding = AnalogBinding(0, listener = object : AnalogBindingListener {
            override fun onValue(value: Double) { output = value }
        }, zones = listOf(AnalogZone("zone", 0.5, 1.0, listener = object : AnalogZoneListener {
            override fun onEnter(value: Double) { throw failure }
            override fun onExit(value: Double) { exits++ }
        })))
        assertSame(failure, assertFails { binding.update(frame(), 0L) })
        assertEquals(1, exits); assertEquals(0.0, output)
        binding.update(frame(), 1L)
        assertEquals(1, exits)
    }

    @Test fun `recursive update aborts the original dispatch and neutralizes outputs`() {
        lateinit var runtime: ControllerBindingRuntime
        var releases = 0
        val binding = DigitalBinding(RawButtonSource(0), listener = object : DigitalBindingListener {
            override fun onPress() { runtime.update(frame(), 1L) }
            override fun onRelease(heldForNanos: Long, reason: BindingReleaseReason) { releases++ }
        })
        runtime = ControllerBindingRuntime(listOf(binding))
        assertFailsWith<IllegalStateException> { runtime.update(frame(), 0L) }
        assertFalse(binding.isActive); assertEquals(1, releases)
    }

    @Test fun `a suppressed held button cannot satisfy physical neutral rearming`() {
        val suppression = ButtonSuppressionState(2)
        val chord = SuppressingButtonChordSource(intArrayOf(0, 1), 75L, suppression)
        val binding = DigitalBinding(SuppressibleButtonSource(0, suppression), listener = object : DigitalBindingListener {})
        binding.update(frame(), 0L)
        binding.terminate(1L, BindingReleaseReason.CANCELLED, true)
        val consumed = frame()
        chord.sample(consumed, 2L)
        binding.update(consumed, 2L)
        binding.update(frame(), 3L)
        assertFalse(binding.isActive)
        binding.update(frame(first = false), 4L)
        binding.update(frame(), 5L)
        assertTrue(binding.isActive)
    }
}
