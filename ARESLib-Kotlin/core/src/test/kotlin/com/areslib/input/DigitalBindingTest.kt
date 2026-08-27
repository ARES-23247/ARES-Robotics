package com.areslib.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DigitalBindingTest {
    @Test
    fun `press and release debounce reject unstable edges`() {
        val events = RecordingDigitalListener()
        val binding = DigitalBinding(
            RawButtonSource(0),
            DigitalBindingTiming(pressDebounceNanos = 20L, releaseDebounceNanos = 15L),
            events,
        )
        val frame = InputFrame(1, 1)

        binding.update(sample(frame, false), 0L)
        binding.update(sample(frame, true), 10L)
        binding.update(sample(frame, false), 15L) // bounce resets the candidate
        binding.update(sample(frame, true), 20L)
        binding.update(sample(frame, true), 39L)
        assertEquals(0, events.pressCount)

        binding.update(sample(frame, true), 40L)
        assertEquals(1, events.pressCount)
        assertTrue(binding.isActive)

        binding.update(sample(frame, false), 50L)
        binding.update(sample(frame, true), 55L) // release bounce
        binding.update(sample(frame, false), 60L)
        binding.update(sample(frame, false), 74L)
        assertEquals(0, events.releaseCount)
        binding.update(sample(frame, false), 75L)

        assertEquals(1, events.releaseCount)
        assertFalse(binding.isActive)
        assertEquals(BindingReleaseReason.INPUT_RELEASED, events.lastReleaseReason)
    }

    @Test
    fun `hold fires once and delayed updates do not burst repeats`() {
        val events = RecordingDigitalListener()
        val binding = DigitalBinding(
            RawButtonSource(0),
            DigitalBindingTiming(
                holdAfterNanos = 100L,
                repeatAfterNanos = 100L,
                repeatEveryNanos = 50L,
            ),
            events,
        )
        val frame = InputFrame(1, 1)

        binding.update(sample(frame, true), 0L)
        binding.update(sample(frame, true), 99L)
        assertEquals(0, events.holdCount)
        assertEquals(0, events.repeatCount)

        binding.update(sample(frame, true), 100L)
        binding.update(sample(frame, true), 350L)
        binding.update(sample(frame, true), 399L)

        assertEquals(1, events.holdCount)
        assertEquals(2, events.repeatCount, "a delayed loop emits one repeat rather than replaying every missed interval")

        binding.update(sample(frame, true), 400L)
        assertEquals(3, events.repeatCount)
    }

    @Test
    fun `cooldown rejects an entire activation`() {
        val events = RecordingDigitalListener()
        val binding = DigitalBinding(
            RawButtonSource(0),
            DigitalBindingTiming(cooldownNanos = 100L),
            events,
        )
        val frame = InputFrame(1, 1)

        binding.update(sample(frame, true), 0L)
        binding.update(sample(frame, false), 1L)
        binding.update(sample(frame, true), 50L)
        binding.update(sample(frame, false), 60L)
        assertEquals(1, events.pressCount)
        assertEquals(1, events.releaseCount, "a rejected activation has no unmatched release callback")

        binding.update(sample(frame, true), 100L)
        binding.update(sample(frame, false), 101L)
        assertEquals(2, events.pressCount)
        assertEquals(2, events.releaseCount)
    }

    @Test
    fun `runtime synthesizes one release and requires neutral after disconnect`() {
        val events = RecordingDigitalListener()
        val binding = DigitalBinding(
            RawButtonSource(0),
            DigitalBindingTiming(releaseDebounceNanos = 1_000L),
            events,
        )
        var clock = 0L
        val runtime = ControllerBindingRuntime(listOf(binding), nanoTime = { clock })
        val frame = InputFrame(1, 1)

        runtime.update(sample(frame, true), 0L)
        assertEquals(1, events.pressCount)

        frame.beginSample(false, sampleTimeNanos = 10L)
        runtime.update(frame, 10L)
        runtime.update(frame, 11L)
        assertEquals(1, events.releaseCount)
        assertEquals(BindingReleaseReason.DISCONNECTED, events.lastReleaseReason)

        runtime.update(sample(frame, true), 20L) // still held: do not restart
        assertEquals(1, events.pressCount)
        runtime.update(sample(frame, false), 30L) // re-arm at neutral
        runtime.update(sample(frame, true), 40L)
        assertEquals(2, events.pressCount)

        clock = 50L
        runtime.cancel()
        assertEquals(2, events.releaseCount)
        assertEquals(BindingReleaseReason.CANCELLED, events.lastReleaseReason)
    }

    @Test
    fun `time rewind safely releases and re-arms the binding`() {
        val events = RecordingDigitalListener()
        val binding = DigitalBinding(RawButtonSource(0), listener = events)
        val runtime = ControllerBindingRuntime(listOf(binding))
        val frame = InputFrame(1, 1)

        runtime.update(sample(frame, true), 100L)
        runtime.update(sample(frame, true), 50L)

        assertEquals(1, events.pressCount)
        assertEquals(1, events.releaseCount)
        assertEquals(BindingReleaseReason.TIME_REWIND, events.lastReleaseReason)

        runtime.update(sample(frame, false), 60L)
        runtime.update(sample(frame, true), 70L)
        assertEquals(2, events.pressCount)
    }

    @Test
    fun `maximum duration releases once and requires a new physical press`() {
        val events = RecordingDigitalListener()
        val binding = DigitalBinding(
            RawButtonSource(0),
            DigitalBindingTiming(maximumActiveNanos = 50L),
            events,
        )
        val frame = InputFrame(1, 1)

        binding.update(sample(frame, true), 0L)
        binding.update(sample(frame, true), 50L)
        binding.update(sample(frame, true), 60L)

        assertEquals(1, events.pressCount)
        assertEquals(1, events.releaseCount)
        assertEquals(BindingReleaseReason.MAXIMUM_DURATION, events.lastReleaseReason)

        binding.update(sample(frame, false), 70L)
        binding.update(sample(frame, true), 80L)
        assertEquals(2, events.pressCount)
    }

    private fun sample(frame: InputFrame, pressed: Boolean): InputFrame {
        frame.beginSample(true, 0, 1, 0L)
        frame.setButton(0, pressed)
        return frame
    }
}

private class RecordingDigitalListener : DigitalBindingListener {
    var pressCount = 0
    var heldCount = 0
    var holdCount = 0
    var repeatCount = 0
    var releaseCount = 0
    var lastReleaseReason: BindingReleaseReason? = null

    override fun onPress() {
        pressCount++
    }

    override fun onHeld(heldForNanos: Long) {
        heldCount++
    }

    override fun onHold(heldForNanos: Long) {
        holdCount++
    }

    override fun onRepeat(heldForNanos: Long) {
        repeatCount++
    }

    override fun onRelease(heldForNanos: Long, reason: BindingReleaseReason) {
        releaseCount++
        lastReleaseReason = reason
    }
}
