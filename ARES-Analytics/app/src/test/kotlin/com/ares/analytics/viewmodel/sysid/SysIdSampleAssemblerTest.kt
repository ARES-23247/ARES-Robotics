package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.shared.models.TelemetryFrame
import kotlin.test.*

class SysIdSampleAssemblerTest {
    private fun frame(index: Int, value: Double, time: Long=1000) = TelemetryFrame(time/1000,"run","SysId/Data/$index",value,timestampUs=time)
    @Test fun `pending and completed storage stay bounded even for incomplete streams`() {
        val assembler=SysIdSampleAssembler(8)
        repeat(1000) { assembler.accept(frame(0,it.toDouble(),it*1000L),"SYSID") }
        assertEquals(8,assembler.pendingCount)
        repeat(1000) { assembler.accept(frame(1,Double.NaN,it*1000L),"SYSID") }
        assertTrue(assembler.pendingCount<=8)
        assertEquals(8,assembler.completedCount)
        assembler.clear()
        assertEquals(0,assembler.pendingCount);assertEquals(0,assembler.completedCount)
    }
    @Test fun `invalid indices do not allocate partial rows`() {
        val assembler=SysIdSampleAssembler()
        for(i in listOf(-1,7,100)) assertNull(assembler.accept(frame(i,1.0),"SYSID"))
        assertEquals(0,assembler.pendingCount)
    }
    @Test fun `conflicting duplicate channels invalidate the whole source row`() {
        val assembler=SysIdSampleAssembler()
        assembler.accept(frame(0,20.0),"SYSID");assembler.accept(frame(1,6.0),"SYSID")
        assembler.accept(frame(1,7.0),"SYSID")
        for(i in 2..4) assertNull(assembler.accept(frame(i,1.0),"SYSID"))
        assertEquals(0,assembler.pendingCount)
    }
    @Test fun `valid strings preserve positions and oversized strings are rejected`() {
        val assembler=SysIdSampleAssembler()
        val packed=TelemetryFrame(1,"run","SysId/Data",0.0,"20|6|100|3|2")
        assertContentEquals(doubleArrayOf(20.0,6.0,100.0,3.0,2.0),assembler.accept(packed,"SYSID"))
        assertNull(assembler.accept(packed,"SYSID"))
        assertNull(assembler.accept(packed.copy(timestampMs=2,timestampUs=2000,stringValue="1|".repeat(1000)),"SYSID"))
    }
    @Test fun `eviction cannot complete a row whose early channel was lost`() {
        val assembler=SysIdSampleAssembler(2)
        for(t in 1L..3L) assembler.accept(frame(0,t.toDouble(),t*1000),"SYSID")
        for(i in 1..4) assertNull(assembler.accept(frame(i,1.0,1000),"SYSID"))
    }
    @Test fun `every calibration requires exactly its declared measurement columns`() {
        for((kind,columns) in listOf("SYSID" to 5,"PINPOINT_SPIN" to 4,"VISION_CALIBRATION" to 4,"LINEAR_DRIVE" to 3,"TRACK_WIDTH_SPIN" to 7)) {
            val assembler=SysIdSampleAssembler()
            val expected=DoubleArray(columns) { if(it==0) 20.0 else it.toDouble() }
            for(i in 0 until columns-1) assertNull(assembler.accept(frame(i,expected[i]),kind))
            assertContentEquals(expected,assembler.accept(frame(columns-1,expected.last()),kind))
            assertNull(assembler.accept(frame(columns,0.0),kind))
            assertEquals(0,assembler.pendingCount)
        }
    }
}
