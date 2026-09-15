package com.areslib.input

import java.math.BigDecimal
import java.math.MathContext
import kotlin.test.*

class InputNumericBoundaryAuditTest {
    private fun frame(value: Double? = null, first: Boolean = false, second: Boolean = false) = InputFrame(2, 2).apply {
        beginSample(true, if (value == null) 0 else 1, 2, 0L)
        value?.let { setAxis(0, it) }; setButton(0, first); setButton(1, second)
    }
    private val noAnalog = object : AnalogBindingListener {}

    @Test fun `axis normalization preserves finite extreme calibration ratios`() {
        val min = -Double.MAX_VALUE
        val center = min / 2.0
        val max = Double.MAX_VALUE
        val transform = AxisTransform(min, center, max)
        for (raw in listOf(min, center, 0.0, max / 2.0, max)) {
            val denominator = if (raw >= center) BigDecimal(max)-BigDecimal(center) else BigDecimal(center)-BigDecimal(min)
            val expected = (BigDecimal(raw)-BigDecimal(center)).divide(denominator, MathContext(50)).toDouble()
            assertEquals(expected, transform.apply(raw), 1e-15, "raw=$raw")
        }
    }

    @Test fun `unreported and nonfinite axes cannot become deflected through calibration`() {
        val transform = AxisTransform(0.0, 0.5, 1.0)
        for (raw in listOf<Double?>(null, Double.NaN, Double.POSITIVE_INFINITY)) {
            val binding = AnalogBinding(0, transform, noAnalog)
            binding.update(frame(raw), 0L)
            assertEquals(0.0, binding.value, "raw=$raw")
        }
    }

    @Test fun `axis invalidity bypasses digital release debounce and requires a new neutral sample`() {
        val source = AxisThresholdSource(0, 0.7, 0.5, transform = AxisTransform.trigger())
        val binding = DigitalBinding(source, DigitalBindingTiming(releaseDebounceNanos = 1000L), object : DigitalBindingListener {})
        val runtime = ControllerBindingRuntime(listOf(binding))
        runtime.update(frame(1.0), 0L)
        assertTrue(binding.isActive)
        runtime.update(frame(Double.NaN), 1L)
        assertFalse(binding.isActive)
        runtime.update(frame(1.0), 2L)
        assertFalse(binding.isActive)
        runtime.update(frame(0.0), 3L)
        runtime.update(frame(1.0), 4L)
        assertTrue(binding.isActive)
    }

    @Test fun `disconnected threshold sources are inactive even below zero`() {
        val source = AxisThresholdSource(0, -0.7, -0.5, ThresholdDirection.BELOW, AxisTransform(0.0, 0.5, 1.0))
        assertFalse(source.sample(InputFrame(1, 1), 0L))
    }

    @Test fun `self copy preserves every input and its sample identity`() {
        val sample = frame(0.75, true, true)
        val sequence = sample.sequence
        sample.copyFrom(sample)
        assertEquals(0.75, sample.axis(0))
        assertTrue(sample.button(0)); assertTrue(sample.button(1))
        assertEquals(sequence, sample.sequence)
    }

    @Test fun `invalid new sample cannot leave the prior frame active`() {
        val sample = frame(0.75, true)
        assertFailsWith<IllegalArgumentException> { sample.beginSample(true, 3, 2, 1L) }
        assertFalse(sample.isConnected)
        assertFalse(sample.button(0))
        assertEquals(0.0, sample.axis(0))
    }

    @Test fun `finite slew rate cannot jump over an overflowing delta`() {
        for (sign in listOf(1.0, -1.0)) {
            val binding = AnalogBinding(0, AxisTransform(outputMin = -Double.MAX_VALUE, outputMax = Double.MAX_VALUE), noAnalog,
                riseRatePerSecond = Double.MAX_VALUE, fallRatePerSecond = Double.MAX_VALUE)
            binding.update(frame(-sign), 0L)
            binding.update(frame(-sign), 1_000_000_000L)
            assertEquals(-sign, binding.value / Double.MAX_VALUE)
            binding.update(frame(sign), 2_500_000_000L)
            assertEquals(sign * 0.5, binding.value / Double.MAX_VALUE, 1e-15)
        }
    }

    @Test fun `neutral output is emitted even below the change threshold`() {
        var emitted = Double.NaN
        val binding = AnalogBinding(0, listener = object : AnalogBindingListener { override fun onValue(value: Double) { emitted = value } },
            emissionPolicy = AnalogEmissionPolicy.ON_CHANGE, changeEpsilon = 0.1)
        binding.update(frame(0.5), 0L)
        binding.update(frame(0.05), 1L)
        assertEquals(0.05, emitted)
        binding.update(frame(0.0), 2L)
        assertEquals(0.0, emitted)
    }

    @Test fun `minimum timestamp is a real accepted press for cooldown purposes`() {
        var presses = 0
        val binding = DigitalBinding(RawButtonSource(0), DigitalBindingTiming(cooldownNanos = 10L), object : DigitalBindingListener {
            override fun onPress() { presses++ }
        })
        binding.update(frame(first = true), Long.MIN_VALUE)
        binding.update(frame(), Long.MIN_VALUE + 1)
        binding.update(frame(first = true), Long.MIN_VALUE + 2)
        assertEquals(1, presses)
    }

    @Test fun `unrepresentable absolute repeat deadlines do not fire early`() {
        var repeats = 0
        val binding = DigitalBinding(RawButtonSource(0), DigitalBindingTiming(repeatAfterNanos = 10L, repeatEveryNanos = 10L), object : DigitalBindingListener {
            override fun onRepeat(heldForNanos: Long) { repeats++ }
        })
        binding.update(frame(first = true), Long.MAX_VALUE - 5)
        binding.update(frame(first = true), Long.MAX_VALUE)
        assertEquals(0, repeats)
    }

    @Test fun `exhausted repeat timing cannot repeat forever at the same maximum timestamp`() {
        var repeats = 0
        val binding = DigitalBinding(RawButtonSource(0), DigitalBindingTiming(repeatAfterNanos = 0L, repeatEveryNanos = 1L), object : DigitalBindingListener {
            override fun onRepeat(heldForNanos: Long) { repeats++ }
        })
        binding.update(frame(first = true), 0L)
        repeat(3) { binding.update(frame(first = true), Long.MAX_VALUE) }
        assertEquals(2, repeats)
    }

    @Test fun `chord time difference overflow cannot make distant presses simultaneous`() {
        val chord = ChordSource.rawButtons(0, 1, simultaneityWindowNanos = 75L)
        assertFalse(chord.sample(frame(first = true), Long.MIN_VALUE))
        assertFalse(chord.sample(frame(first = true, second = true), Long.MAX_VALUE))
    }

    @Test fun `suppression belongs to the actual frame as well as its sequence`() {
        val suppression = ButtonSuppressionState(2)
        val chord = SuppressingButtonChordSource(intArrayOf(0, 1), 75L, suppression)
        val single = SuppressibleButtonSource(0, suppression)
        val original = frame(first = true, second = true)
        assertTrue(chord.sample(original, 0L))
        assertFalse(single.sample(original, 0L))
        val replacement = frame(first = true)
        assertEquals(original.sequence, replacement.sequence)
        assertTrue(single.sample(replacement, 1L))
    }

    @Test fun `copy carries availability without converting missing feedback into neutral feedback`() {
        val original = InputFrame(2, 65)
        original.beginSample(true, 2, 65, 1L)
        original.setAxis(0, Double.NaN)
        original.setButton(64, false)
        val copy = InputFrame(3, 128)
        copy.copyFrom(original)
        assertFalse(copy.isAxisAvailable(0)); assertFalse(copy.isAxisAvailable(1))
        assertFalse(copy.isButtonAvailable(0)); assertTrue(copy.isButtonAvailable(64))
        original.setAxis(0, 0.0)
        copy.copyFrom(original)
        assertTrue(copy.isAxisAvailable(0))
        copy.beginSample(false, sampleTimeNanos = 2L)
        assertFalse(copy.isAxisAvailable(0)); assertFalse(copy.isButtonAvailable(64))
    }

    @Test fun `missing button immediately releases despite debounce and cannot rearm a held replacement`() {
        val binding = DigitalBinding(RawButtonSource(0), DigitalBindingTiming(releaseDebounceNanos = 100L), object : DigitalBindingListener {})
        binding.update(frame(first = true), 0L)
        val missing = InputFrame(2, 2).apply { beginSample(true, 0, 2, 1L) }
        binding.update(missing, 1L)
        assertFalse(binding.isActive)
        binding.update(frame(first = true), 2L)
        assertFalse(binding.isActive)
        binding.update(frame(), 3L)
        binding.update(frame(first = true), 4L)
        assertTrue(binding.isActive)
    }

    @Test fun `invalid writes and undersized copies cannot retain an old command`() {
        val axis = frame(0.75, true)
        assertFailsWith<IllegalArgumentException> { axis.setAxis(2, 0.0) }
        assertFalse(axis.isConnected)
        val button = frame(0.75, true)
        assertFailsWith<IllegalArgumentException> { button.setButton(2, false) }
        assertFalse(button.isConnected)
        val small = InputFrame(1, 1).apply { beginSample(true, 1, 1, 0L); setAxis(0, 0.75); setButton(0, true) }
        assertFailsWith<IllegalArgumentException> { small.copyFrom(frame(1.0, true, true)) }
        assertFalse(small.isConnected)
        assertEquals(0.0, small.axis(0)); assertFalse(small.button(0))
    }

    @Test fun `button storage ceiling division does not overflow`() {
        for (capacity in listOf(1, 63, 64, 65, 128, Int.MAX_VALUE)) {
            assertEquals(((capacity.toLong() + 63L) / 64L).toInt(), buttonWordCount(capacity))
        }
        assertFailsWith<IllegalArgumentException> { buttonWordCount(0) }
        assertFailsWith<IllegalArgumentException> { ButtonSuppressionState(-1) }
    }

    @Test fun `ordinary subnormal and extreme axes remain monotone and reach both endpoints`() {
        for (transform in listOf(AxisTransform(), AxisTransform(-Double.MIN_VALUE, 0.0, Double.MIN_VALUE),
            AxisTransform(-Double.MAX_VALUE, Double.MAX_VALUE / 2.0, Double.MAX_VALUE))) {
            assertEquals(-1.0, transform.apply(transform.inputMin))
            assertEquals(0.0, transform.apply(transform.inputCenter))
            assertEquals(1.0, transform.apply(transform.inputMax))
            var previous = -1.0
            for (i in 0..100) {
                val fraction = i / 100.0
                val raw = transform.inputMin * (1.0 - fraction) + transform.inputMax * fraction
                val value = transform.apply(raw)
                assertTrue(value.isFinite() && value >= previous && value in -1.0..1.0)
                previous = value
            }
        }
    }
}
