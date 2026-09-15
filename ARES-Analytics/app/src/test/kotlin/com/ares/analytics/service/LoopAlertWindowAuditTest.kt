package com.ares.analytics.service

import com.ares.analytics.shared.models.*
import kotlinx.coroutines.test.*
import org.mockito.Mockito.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LoopAlertWindowAuditTest {
    private suspend fun TestScope.withEngine(block:suspend Fixture.()->Unit) {
        val store=TelemetryStore(); val db=mock(DatabaseService::class.java); val nt=mock(Nt4ClientService::class.java)
        `when`(nt.telemetryStore).thenReturn(store); `when`(nt.telemetryFlow).thenReturn(store.updates)
        val path=Files.createTempFile("loop-window", ".json"); Files.writeString(path,"[]")
        val engine=AlertEngineService(db,nt,path.toString(),StandardTestDispatcher(testScheduler))
        try { runCurrent(); Fixture(this,store,engine).block() }
        finally { engine.dispose(); runCurrent(); Files.deleteIfExists(path) }
    }
    private class Fixture(val scope:TestScope,val store:TelemetryStore,val engine:AlertEngineService) {
        suspend fun send(us:Long,value:Double,key:String="Robot/LoopTimeMs") {
            store.accept(TelemetryFrame(us/1_000,"live-telemetry",key,value,timestampUs=us)); scope.runCurrent()
        }
        val records get()=engine.alerts.value
    }
    @Test fun `sample one microsecond beyond the window does not count`()=runTest { withEngine {
        send(100_900,30.0); send(200_000,30.0); send(1_100_901,30.0)
        assertTrue(records.isEmpty())
    } }
    @Test fun `sample exactly one second old still counts`()=runTest { withEngine {
        send(100_900,40.0); send(200_000,30.0); send(1_100_900,30.0)
        assertEquals(40.0,records.single().peakValue)
    } }
    @Test fun `invalid periods cannot resolve a severe loop fault`()=runTest { withEngine {
        send(0,120.0)
        listOf(0.0,-1.0,Double.MIN_VALUE,Double.NaN,Double.POSITIVE_INFINITY).forEachIndexed { i,v ->
            send((i+1)*100_000L,v); assertNull(records.single().resolveTimestampMs,"$v is not healthy evidence")
        }
        send(2_000_000,20.0); assertEquals(2_000L,records.single().resolveTimestampMs)
    } }
    @Test fun `different source aliases cannot combine to manufacture repeated overruns`()=runTest { withEngine {
        send(100_000,30.0,"Robot/LoopTimeMs")
        send(200_000,30.0,"Profiling/LoopTime_ms")
        send(300_000,30.0,"System/LoopTimeMs")
        assertTrue(records.isEmpty())
    } }
    @Test fun `healthy alias cannot resolve another sources loop fault`()=runTest { withEngine {
        send(100_000,120.0,"Robot/LoopTimeMs"); send(200_000,20.0,"Profiling/LoopTime_ms")
        assertNull(records.single().resolveTimestampMs)
    } }
    @Test fun `alias alert retains actual source key and independent interval`()=runTest { withEngine {
        send(100_000,130.0,"Profiling/LoopTime_ms")
        assertEquals("Profiling/LoopTime_ms",records.single().ruleKey)
        send(1_200_000,20.0,"Profiling/LoopTime_ms")
        assertEquals(1_100L,records.single().durationMs)
    } }
    @Test fun `active occurrence retains an earlier peak after many newer samples`()=runTest { withEngine {
        send(0,90.0); send(10_000,30.0); send(20_000,30.0)
        repeat(20) { send(30_000L+it*10_000,35.0) }
        assertEquals(90.0,records.single().peakValue)
        send(2_000_000,20.0); send(3_000_000,120.0)
        assertEquals(2,records.size); assertEquals(120.0,records.first().peakValue)
    } }
}
