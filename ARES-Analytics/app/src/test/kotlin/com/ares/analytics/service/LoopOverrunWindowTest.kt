package com.ares.analytics.service

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.*
import org.junit.Assume.assumeTrue

class LoopOverrunWindowTest {
    @Test fun `one second boundary is inclusive at microsecond precision`() {
        val inside=LoopOverrunWindow(); val outside=LoopOverrunWindow()
        for(w in listOf(inside,outside)) { w.accept(900,40.0); w.accept(100_000,30.0) }
        inside.accept(1_000_900,30.0); outside.accept(1_000_901,30.0)
        assertTrue(inside.isSlow); assertFalse(outside.isSlow)
        assertEquals(40.0,inside.peakMs)
    }
    @Test fun `moderate threshold is strict and severe threshold inclusive`() {
        val w=LoopOverrunWindow()
        repeat(3) { w.accept(it.toLong(),25.0) }; assertFalse(w.isSlow)
        repeat(3) { w.accept(3L+it,Math.nextUp(25.0)) }; assertTrue(w.isSlow)
        val severe=LoopOverrunWindow(); severe.accept(0,100.0); assertTrue(severe.isSlow)
    }
    @Test fun `invalid input cannot mutate active evidence`() {
        val w=LoopOverrunWindow(); w.accept(100,120.0)
        for(v in listOf(0.0,-1.0,Double.MIN_VALUE,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
            assertFalse(w.accept(200,v)); assertTrue(w.isSlow); assertEquals(120.0,w.peakMs)
        }
        assertFalse(w.accept(-1,20.0)); assertFalse(w.accept(99,20.0))
        assertEquals(1,w.retainedSampleCount)
    }
    @Test fun `healthy samples age evidence without filling the retained sample buffer`() {
        val w=LoopOverrunWindow(); w.accept(0,30.0)
        repeat(10_000) { w.accept(1L+it,20.0) }
        assertEquals(1,w.retainedSampleCount)
        w.accept(1_000_001,20.0); assertEquals(0,w.retainedSampleCount); assertFalse(w.isSlow)
    }
    @Test fun `occurrence peak survives replacement of the original three samples`() {
        val w=LoopOverrunWindow(); w.accept(0,90.0); w.accept(1,30.0); w.accept(2,30.0)
        repeat(1_000) { w.accept(3L+it,40.0) }
        assertEquals(90.0,w.peakMs); assertTrue(w.isSlow); assertEquals(3,w.retainedSampleCount)
        w.accept(2_000_000,20.0); assertFalse(w.isSlow)
        w.accept(3_000_000,110.0); assertEquals(110.0,w.peakMs)
    }
    @Test fun `same timestamp may contain distinct ordered samples`() {
        val w=LoopOverrunWindow(); repeat(3) { w.accept(100,30.0) }
        assertTrue(w.isSlow); assertEquals(3,w.retainedSampleCount)
    }
    @Test fun `isolated severe event resolves on valid healthy evidence under the existing policy`() {
        val w=LoopOverrunWindow(); w.accept(0,120.0); assertTrue(w.isSlow)
        w.accept(1,20.0); assertFalse(w.isSlow)
        w.accept(2,30.0); assertFalse(w.isSlow)
        w.accept(3,30.0); assertTrue(w.isSlow); assertEquals(120.0,w.peakMs)
    }
    @Test fun `long timestamp endpoints do not overflow window aging`() {
        val w=LoopOverrunWindow(); w.accept(0,120.0); w.accept(Long.MAX_VALUE,20.0)
        assertFalse(w.isSlow); assertEquals(0,w.retainedSampleCount)
        repeat(3) { w.accept(Long.MAX_VALUE,30.0) }; assertTrue(w.isSlow)
    }
    @Test fun `bounded detector matches independent full window occurrence oracle`() {
        data class Sample(val time:Long,val period:Double)
        val history=ArrayDeque<Sample>(); val random=Random(731)
        val w=LoopOverrunWindow(); var time=0L; var active=false; var peak=Double.NaN
        repeat(50_000) { iteration ->
            time+=random.nextLong(0,150_001)
            val period=when(random.nextInt(8)) { 0->20.0; 1->25.0; 2->100.0; 3->120.0; else->random.nextDouble(25.01,99.99) }
            history.addLast(Sample(time,period))
            while(history.first.time < time-1_000_000) history.removeFirst()
            val slow=period>=100.0 || history.count { it.period>25.0 }>=3
            val nextPeak=if(slow) max(history.maxOf { it.period },if(active) peak else 0.0) else period
            assertTrue(w.accept(time,period)); assertEquals(slow,w.isSlow,"sample $iteration")
            assertEquals(nextPeak,w.peakMs,"peak at sample $iteration")
            assertTrue(w.retainedSampleCount<=3)
            active=slow; peak=nextPeak
        }
    }
    @Test fun `valid and rejected updates allocate no heap after warmup`() {
        val bean=ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean!=null && bean.isThreadAllocatedMemorySupported)
        bean!!; bean.isThreadAllocatedMemoryEnabled=true
        val w=LoopOverrunWindow(); var time=0L
        fun update() { w.accept(time++,if(time%4==0L) 20.0 else 30.0); w.accept(time,Double.NaN) }
        repeat(50_000) { update() }
        val threadId=Thread.currentThread().id; var zeroWindows=0; var lastBytes=-1L
        for(window in 0 until 10) {
            val before=bean.getThreadAllocatedBytes(threadId)
            repeat(10_000) { update() }
            lastBytes=bean.getThreadAllocatedBytes(threadId)-before
            zeroWindows=if(lastBytes==0L) zeroWindows+1 else 0
            if(zeroWindows==2) break
        }
        assertEquals(2,zeroWindows,"last allocation window: $lastBytes bytes")
        assertTrue(w.retainedSampleCount<=3)
        println("LoopOverrunWindow: two consecutive 10,000-update windows allocated 0 bytes")
    }
}
