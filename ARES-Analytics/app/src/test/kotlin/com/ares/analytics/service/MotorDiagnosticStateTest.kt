package com.ares.analytics.service

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.Assume.assumeTrue
import kotlin.test.*

class MotorDiagnosticStateTest {
    private fun state(power:Double=0.8,velocity:Double=0.0,current:Double=10.0):MotorDiagnosticState = MotorDiagnosticState().apply {
        accept(MotorFeedbackSignal.POWER,0,power); accept(MotorFeedbackSignal.VELOCITY,0,velocity); accept(MotorFeedbackSignal.CURRENT,0,current)
    }
    @Test fun `all three measured inputs are required`() {
        val s=MotorDiagnosticState(); s.accept(MotorFeedbackSignal.POWER,0,0.8); s.accept(MotorFeedbackSignal.CURRENT,0,10.0)
        assertFalse(s.hasEvidence); s.accept(MotorFeedbackSignal.VELOCITY,0,0.0); assertTrue(s.isStalled)
    }
    @Test fun `input age is inclusive at one second and expires one microsecond later`() {
        val s=state(); s.accept(MotorFeedbackSignal.CURRENT,1_000_000,10.0); assertTrue(s.isStalled)
        s.accept(MotorFeedbackSignal.CURRENT,1_000_001,10.0); assertFalse(s.hasEvidence)
        s.accept(MotorFeedbackSignal.POWER,1_000_002,0.8); s.accept(MotorFeedbackSignal.VELOCITY,1_000_003,0.0)
        assertTrue(s.isStalled)
    }
    @Test fun `unknown current clears old average before recovery`() {
        val s=state(); s.accept(MotorFeedbackSignal.CURRENT,100,Double.NaN); assertFalse(s.hasEvidence)
        s.accept(MotorFeedbackSignal.CURRENT,200,0.0); assertTrue(s.isDisconnected); assertFalse(s.isStalled)
    }
    @Test fun `invalid duty and velocity do not become healthy evidence`() {
        val s=state(); s.accept(MotorFeedbackSignal.POWER,1,2.0); assertFalse(s.hasEvidence)
        s.accept(MotorFeedbackSignal.POWER,2,0.8); s.accept(MotorFeedbackSignal.VELOCITY,3,Double.POSITIVE_INFINITY)
        assertFalse(s.hasEvidence)
    }
    @Test fun `threshold equality and signed motor direction follow the MotorIO contract`() {
        assertFalse(state(power=0.35).isStalled); assertFalse(state(velocity=5.0).isStalled)
        assertFalse(state(current=5.0).isStalled); assertFalse(state(current=0.1).isDisconnected)
        assertTrue(state(power=-0.8,velocity=-1.0,current=10.0).isStalled)
        assertTrue(state(current=0.0).isDisconnected)
    }
    @Test fun `late independent feedback uses latest contributing source time`() {
        val s=MotorDiagnosticState(); s.accept(MotorFeedbackSignal.POWER,200,0.8); s.accept(MotorFeedbackSignal.VELOCITY,200,0.0)
        s.accept(MotorFeedbackSignal.CURRENT,100,10.0); assertTrue(s.isStalled); assertEquals(200L,s.timestampUs)
        assertFalse(s.accept(MotorFeedbackSignal.CURRENT,99,0.0)); assertTrue(s.isStalled)
        assertFalse(s.accept(MotorFeedbackSignal.POWER,-1,0.0))
    }
    @Test fun `high rate truncation is unknown and retained state remains bounded`() {
        val s=state()
        repeat(512) { s.accept(MotorFeedbackSignal.CURRENT,1L+it,10.0) }
        assertFalse(s.hasEvidence)
    }
    @Test fun `motor updates allocate no heap after warmup`() {
        val bean=ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean!=null && bean.isThreadAllocatedMemorySupported); bean!!; bean.isThreadAllocatedMemoryEnabled=true
        val s=state(); var time=0L
        fun update() {
            time+=10_000
            s.accept(MotorFeedbackSignal.POWER,time,0.8); s.accept(MotorFeedbackSignal.VELOCITY,time,0.0)
            s.accept(MotorFeedbackSignal.CURRENT,time,6.0)
        }
        repeat(50_000) { update() }
        val id=Thread.currentThread().id; var zero=0; var bytes=-1L
        for(window in 0 until 10) {
            val before=bean.getThreadAllocatedBytes(id); repeat(10_000) { update() }
            bytes=bean.getThreadAllocatedBytes(id)-before; zero=if(bytes==0L) zero+1 else 0
            if(zero==2) break
        }
        assertEquals(2,zero,"last allocation window: $bytes bytes"); assertTrue(s.isStalled)
        println("MotorDiagnosticState: two 10,000-update windows allocated 0 bytes")
    }
}
