package com.ares.analytics.service.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalMathEngineTest {

    @Test
    fun `numerical derivative computes exact slope on linear and sinusoidal signals`() {
        // y(t) = 2.5 * t_sec + 1.0 over 0..2 seconds at 50 Hz (20 ms step)
        val samples = (0..100).map { i ->
            val tMs = i * 20L
            TimedScalarSample(timestampMs = tMs, value = 2.5 * (tMs / 1000.0) + 1.0)
        }
        val derivative = SignalMathEngine.derivative(samples)
        assertEquals(samples.size, derivative.size)
        derivative.forEach { sample ->
            assertEquals(2.5, sample.value, 1e-6)
        }

        // y(t) = sin(2 * pi * t_sec) -> dy/dt = 2 * pi * cos(2 * pi * t_sec)
        val sineSamples = (0..200).map { i ->
            val tMs = i * 10L
            TimedScalarSample(timestampMs = tMs, value = sin(2.0 * PI * (tMs / 1000.0)))
        }
        val sineDeriv = SignalMathEngine.derivative(sineSamples)
        for (i in 2 until sineDeriv.size - 2) {
            val tSec = sineDeriv[i].timestampMs / 1000.0
            val expected = 2.0 * PI * cos(2.0 * PI * tSec)
            assertEquals(expected, sineDeriv[i].value, 0.05)
        }
    }

    @Test
    fun `cumulative trapezoidal integral integrates current into coulombs and mAh`() {
        // Constant 18.0 A draw for 10 seconds -> 180.0 A*s = 50.0 mAh
        val currentSamples = (0..100).map { i ->
            TimedScalarSample(timestampMs = i * 100L, value = 18.0)
        }
        val integral = SignalMathEngine.cumulativeIntegral(currentSamples)
        assertEquals(101, integral.size)
        assertEquals(0.0, integral.first().value, 1e-9)
        assertEquals(180.0, integral.last().value, 1e-6)
        assertEquals(50.0, SignalMathEngine.ampSecondsToMilliAmpHours(integral.last().value), 1e-6)
    }

    @Test
    fun `channel delta interpolates asynchronous timestamps accurately`() {
        // Channel A: y = 3*t_sec sampled every 20 ms
        val a = (0..50).map { i -> TimedScalarSample(i * 20L, 3.0 * (i * 0.02)) }
        // Channel B: y = 1*t_sec sampled every 25 ms (offset cadence)
        val b = (0..40).map { i -> TimedScalarSample(i * 25L, 1.0 * (i * 0.025)) }

        val delta = SignalMathEngine.channelDelta(a, b)
        assertTrue(delta.size >= 45)
        delta.forEach { sample ->
            val expected = 2.0 * (sample.timestampMs / 1000.0)
            assertEquals(expected, sample.value, 1e-6)
        }
    }

    @Test
    fun `savitzky golay and moving average attenuate high frequency ripple while preserving quadratic trend`() {
        // Pure quadratic y(k) = 0.01 * k^2 + 0.5 * k - 2.0 should be exact in interior points under 5-pt quadratic SG
        val quadratic = (0..40).map { k ->
            TimedScalarSample(k * 20L, 0.01 * k * k + 0.5 * k - 2.0)
        }
        val sg = SignalMathEngine.savitzkyGolay5(quadratic)
        assertEquals(quadratic.size, sg.size)
        for (i in 2 until sg.size - 2) {
            assertEquals(quadratic[i].value, sg[i].value, 1e-9)
        }

        // Alternating +-1.0 ripple around 10.0 is attenuated by moving average
        val noisy = (0..40).map { k ->
            TimedScalarSample(k * 20L, 10.0 + if (k % 2 == 0) 1.0 else -1.0)
        }
        val smoothed = SignalMathEngine.movingAverage(noisy, windowSize = 5)
        for (i in 2 until smoothed.size - 2) {
            assertTrue(abs(smoothed[i].value - 10.0) <= 0.25)
        }
    }

    @Test
    fun `spectral analysis detects multi tone mechanical resonance and PID oscillation peaks`() {
        // 200 Hz sample rate (5 ms step), 512 samples: strong 12.5 Hz PID oscillation + secondary 35 Hz structural buzz
        val dtMs = 5L
        val samples = (0 until 512).map { i ->
            val tSec = (i * dtMs) / 1000.0
            val v = 3.0 * sin(2.0 * PI * 12.5 * tSec) + 1.2 * sin(2.0 * PI * 35.0 * tSec)
            TimedScalarSample(timestampMs = i * dtMs, value = v)
        }
        val spectrum = SignalMathEngine.computeSpectrum(samples, topPeakCount = 3)
        assertTrue(spectrum.sampleRateHz in 199.0..201.0)
        assertTrue(spectrum.peaks.size >= 2)
        assertEquals(12.5, spectrum.peaks[0].frequencyHz, 0.8)
        assertEquals(35.0, spectrum.peaks[1].frequencyHz, 0.8)
        assertTrue(spectrum.peaks[0].amplitude > spectrum.peaks[1].amplitude)
    }
}
