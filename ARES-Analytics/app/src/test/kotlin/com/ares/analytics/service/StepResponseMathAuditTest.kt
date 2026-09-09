package com.ares.analytics.service

import kotlin.math.*
import kotlin.test.*

class StepResponseMathAuditTest {
    private fun response(tau: Double=400.0, delay: Double=120.0, gain: Double=2.0,
        period: Long=20, duration: Long=6000, offset: Double=0.0): List<AlignedDataRow> =
        (0L..duration step period).map { t ->
            val elapsed = t - 200.0 - delay
            val progress = if (elapsed <= 0) 0.0 else -expm1(-elapsed/tau)
            AlignedDataRow(t, if (t < 200) 0.0 else 6.0, offset + gain*6*progress,
                if (elapsed <= 0) 0.0 else gain*6/(tau/1000)*exp(-elapsed/tau))
        }
    @Test fun `known first order delay and time constant are separated`() {
        val m = StepResponseAnalysis.identify(response())
        assertEquals(120.0, m.deadTimeMs, 2.0)
        assertEquals(400.0, m.timeConstantMs, 2.0)
        assertEquals(2.0, m.processGain, 1e-4)
        assertTrue(m.modelFit > 0.999)
    }
    @Test fun `timing estimates are stable across sample periods`() {
        for (period in listOf(5L, 20L, 40L)) {
            val m = StepResponseAnalysis.identify(response(period=period))
            assertEquals(120.0, m.deadTimeMs, 3.0)
            assertEquals(400.0, m.timeConstantMs, 3.0)
        }
    }
    @Test fun `zero delay plant does not acquire a five percent response delay`() {
        val m = StepResponseAnalysis.identify(response(delay=0.0))
        assertEquals(0.0, m.deadTimeMs, 1.0)
    }
    @Test fun `first order SIMC gains use a consistent PI rule`() {
        val m = StepResponseMetrics(879.0,0.0,1700.0,120.0,400.0,2.0,1.0)
        val g = StepResponseAnalysis.gains(m)
        assertEquals(0.4/(2*0.48), g.kP, 1e-12)
        assertEquals(g.kP/0.4, g.kI, 1e-12)
        assertEquals(0.0, g.kD)
    }
    @Test fun `negative plant gain cannot produce positive feedback gains`() {
        val m = StepResponseAnalysis.identify(response(gain=-2.0))
        assertFalse(m.isUsable)
        assertEquals(AutoTunerPIDFGains(0.0,0.0,0.0), StepResponseAnalysis.gains(m))
    }
    @Test fun `nonfinite or incomplete model metrics cannot recommend gains`() {
        val valid = StepResponseMetrics(879.0,0.0,1700.0,120.0,400.0,2.0,1.0)
        for (m in listOf(valid.copy(deadTimeMs=Double.NaN), valid.copy(modelFit=Double.NaN),
            valid.copy(settlingTimeMs=Double.NaN), valid.copy(percentOvershoot=Double.POSITIVE_INFINITY))) {
            assertFalse(m.isUsable)
            assertEquals(AutoTunerPIDFGains(0.0,0.0,0.0), StepResponseAnalysis.gains(m))
        }
    }
    @Test fun `truncated rising data does not masquerade as steady state`() {
        assertFalse(StepResponseAnalysis.identify(response(tau=10000.0, duration=2000)).isUsable)
    }
    @Test fun `voltage ramp is not a constant input step`() {
        val data = List(200) { AlignedDataRow(it*20L,it*2.0,it*0.3,15.0) }
        assertFalse(StepResponseAnalysis.identify(data).isUsable)
    }
    @Test fun `late incomplete larger step cannot hide an earlier measured response`() {
        val data = response(delay=0.0, tau=200.0, duration=3300).map {
            if (it.timestampMs >= 3260) it.copy(voltage=100.0) else it
        }
        val m = StepResponseAnalysis.identify(data)
        assertTrue(m.isUsable); assertEquals(200.0, m.timeConstantMs, 2.0)
    }
    @Test fun `large finite offset does not overflow baseline and tail means`() {
        val m = StepResponseAnalysis.identify(response(gain=1e306, offset=1e308))
        assertTrue(m.isUsable)
        assertEquals(1.0, m.processGain/1e306, 1e-4)
        assertEquals(400.0, m.timeConstantMs, 2.0)
    }
    @Test fun `moving initial state needs an independently identified DC gain`() {
        val initial = 8.0*exp(-200.0/400.0)
        val data = (0L..6000L step 20).map { t ->
            val voltage = if(t < 200) 0.0 else 6.0
            val v = if(t < 200) 8.0*exp(-t/400.0) else 12+(initial-12)*exp(-(t-200)/400.0)
            AlignedDataRow(t, voltage, v, (voltage*2-v)/0.4)
        }
        assertFalse(StepResponseAnalysis.identify(data).isUsable)
        val m = StepResponseAnalysis.identify(data, identifiedDcGain=2.0)
        assertTrue(m.isUsable); assertEquals(2.0, m.processGain)
        assertEquals(400.0, m.timeConstantMs, 2.0); assertEquals(0.0, m.deadTimeMs, 1.0)
    }
    @Test fun `negative voltage motion preserves a positive plant gain and timing`() {
        val data = response().map { it.copy(voltage=-it.voltage, velocity=-it.velocity, accel=-it.accel) }
        val m = StepResponseAnalysis.identify(data)
        assertTrue(m.isUsable); assertEquals(2.0, m.processGain, 1e-4)
        assertEquals(120.0, m.deadTimeMs, 2.0); assertEquals(400.0, m.timeConstantMs, 2.0)
    }
    @Test fun `unordered duplicate and invalid rows cannot identify a model`() {
        val data = response()
        val badRows = listOf(data[20].copy(timestampMs=-1), data[20].copy(timestampMs=Long.MAX_VALUE),
            data[20].copy(voltage=Double.NaN), data[20].copy(velocity=Double.NaN), data[20].copy(accel=Double.POSITIVE_INFINITY))
        for (bad in badRows) {
            val rows = data.toMutableList(); rows[20] = bad
            assertFalse(StepResponseAnalysis.identify(rows).isUsable)
        }
        assertFalse(StepResponseAnalysis.identify(data.reversed()).isUsable)
        assertFalse(StepResponseAnalysis.identify(data.take(30) + data[29] + data.drop(30)).isUsable)
    }
    @Test fun `missing excitation and invalid independent gains are rejected`() {
        assertFalse(StepResponseAnalysis.identify(emptyList()).isUsable)
        assertFalse(StepResponseAnalysis.identify(response(gain=0.0)).isUsable)
        assertFalse(StepResponseAnalysis.identify(response().map { it.copy(voltage=0.0) }).isUsable)
        for (gain in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(StepResponseAnalysis.identify(response(), gain).isUsable)
        }
    }
    @Test fun `gain arithmetic remains finite at representational limits`() {
        val valid = StepResponseMetrics(879.0,0.0,1700.0,120.0,400.0,2.0,1.0)
        assertEquals(AutoTunerPIDFGains(0.0,0.0,0.0), StepResponseAnalysis.gains(valid.copy(timeConstantMs=Double.MIN_VALUE)))
        val limited = StepResponseAnalysis.gains(valid.copy(deadTimeMs=0.0, timeConstantMs=200.0, processGain=1e-5))
        assertEquals(AutoTunerPIDFGains(50.0,100.0,0.0), limited)
    }
    @Test fun `settling is the first sample after the last tolerance excursion`() {
        val data = response().map { if (it.timestampMs==4000L) it.copy(velocity=11.0) else it }
        val m = StepResponseAnalysis.identify(data)
        assertEquals(3820.0, m.settlingTimeMs)
    }
    @Test fun `linked input lists preserve the same model without mutation`() {
        val array = response()
        val linked = java.util.LinkedList(array)
        assertEquals(StepResponseAnalysis.identify(array), StepResponseAnalysis.identify(linked))
        assertEquals(array, linked)
    }
    @Test fun `settling analysis uses linear sample access for a late excursion`() {
        val source = response(period=1, duration=20000).map { if(it.timestampMs==19000L) it.copy(velocity=11.0) else it }
        var reads = 0
        val counted = object : AbstractList<AlignedDataRow>(), java.util.RandomAccess {
            override val size: Int get() = source.size
            override fun get(index: Int): AlignedDataRow { reads++; return source[index] }
        }
        StepResponseAnalysis.identify(counted)
        assertTrue(reads < 30*source.size, "Expected linear sample access, observed $reads reads for ${source.size} rows")
    }
}
