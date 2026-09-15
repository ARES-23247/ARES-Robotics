package com.ares.analytics.service

import java.util.ArrayDeque
import kotlin.random.Random
import kotlin.test.*

class MotorCurrentWindowTest {
    @Test fun `empty and expired windows are unknown with an inclusive exact boundary`() {
        val w=MotorCurrentWindow(); assertTrue(w.meanAt(0).isNaN())
        w.accept(900,10.0); assertEquals(10.0,w.meanAt(1_000_900))
        assertTrue(w.meanAt(1_000_901).isNaN())
    }
    @Test fun `overflow is unknown until the omitted sample actually expires`() {
        val w=MotorCurrentWindow(2); w.accept(0,1.0); w.accept(1,3.0); w.accept(2,5.0)
        assertEquals(2,w.retainedSampleCount); assertTrue(w.meanAt(1_000_000).isNaN())
        assertEquals(4.0,w.meanAt(1_000_001)); assertEquals(5.0,w.meanAt(1_000_002))
        assertTrue(w.meanAt(1_000_003).isNaN())
    }
    @Test fun `large finite samples do not overflow their mean`() {
        val w=MotorCurrentWindow(4)
        repeat(3) { w.accept(it.toLong(),Double.MAX_VALUE) }
        assertEquals(Double.MAX_VALUE,w.meanAt(3))
        w.accept(3,0.0); assertTrue(w.meanAt(3).isFinite())
        assertEquals(Double.MAX_VALUE*0.75,w.meanAt(3))
    }
    @Test fun `expiring a large value recovers small values without cancellation drift`() {
        val w=MotorCurrentWindow(4); w.accept(0,Double.MAX_VALUE); w.accept(100,1.0); w.accept(200,1.0)
        assertEquals(1.0,w.meanAt(1_000_001))
    }
    @Test fun `delayed current may arrive after another signal advanced the observation time`() {
        val w=MotorCurrentWindow(); w.accept(0,2.0); assertEquals(2.0,w.meanAt(500_000))
        assertTrue(w.accept(100_000,4.0)); assertEquals(3.0,w.meanAt(500_000))
        w.meanAt(2_000_000); assertTrue(w.accept(200_000,9.0)); assertTrue(w.meanAt(2_000_000).isNaN())
    }
    @Test fun `invalid and backwards samples cannot corrupt retained values`() {
        val w=MotorCurrentWindow(); w.accept(100,2.0)
        for(v in listOf(-1.0,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) assertFalse(w.accept(200,v))
        assertFalse(w.accept(-1,3.0)); assertFalse(w.accept(99,3.0))
        assertTrue(w.meanAt(99).isNaN()); assertEquals(2.0,w.meanAt(100))
    }
    @Test fun `clear discards overflow and chronology as well as samples`() {
        val w=MotorCurrentWindow(1); w.accept(100,2.0); w.accept(200,4.0); assertTrue(w.meanAt(200).isNaN())
        w.clear(); w.accept(0,6.0); assertEquals(6.0,w.meanAt(0))
    }
    @Test fun `distinct samples at one timestamp retain their sample weights`() {
        val w=MotorCurrentWindow(4); w.accept(0,0.0); w.accept(0,0.0); w.accept(0,9.0)
        assertEquals(3.0,w.meanAt(0))
    }
    @Test fun `unsupported capacities are rejected`() {
        for(c in listOf(0,-1,3,8192)) assertFailsWith<IllegalArgumentException> { MotorCurrentWindow(c) }
    }
    @Test fun `timestamp endpoints use subtraction without deadline overflow`() {
        val w=MotorCurrentWindow(2); w.accept(0,2.0); assertTrue(w.meanAt(Long.MAX_VALUE).isNaN())
        w.accept(Long.MAX_VALUE,4.0); assertEquals(4.0,w.meanAt(Long.MAX_VALUE))
    }
    @Test fun `tree mean matches an independent full sample oracle across wrap and expiry`() {
        data class Sample(val time:Long,val value:Double)
        val history=ArrayDeque<Sample>(); val random=Random(3201); val w=MotorCurrentWindow()
        var time=0L
        repeat(10_000) {
            time+=random.nextLong(5_000,25_001); val value=random.nextDouble(0.0,100.0)
            history.addLast(Sample(time,value)); while(history.first.time<time-1_000_000) history.removeFirst()
            assertTrue(w.accept(time,value))
            val expected=history.sumOf { it.value }/history.size
            assertEquals(expected,w.meanAt(time),1e-10)
            assertEquals(history.size,w.retainedSampleCount)
        }
    }
}
