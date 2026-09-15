package com.ares.analytics.service

import com.ares.analytics.shared.models.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mockito.Mockito.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AlertSourceAuditTest {
    private suspend fun TestScope.withEngine(block:suspend Fixture.()->Unit) {
        val store=TelemetryStore()
        val db=mock(DatabaseService::class.java)
        val nt=mock(Nt4ClientService::class.java)
        `when`(nt.telemetryStore).thenReturn(store)
        `when`(nt.telemetryFlow).thenReturn(store.updates)
        val path=Files.createTempFile("alert-source", ".json")
        Files.writeString(path,Json.encodeToString(listOf(ThresholdRule("Audit/Value","high",maxValue=10.0,audibleAlert=false))))
        val engine=AlertEngineService(db,nt,path.toString(),StandardTestDispatcher(testScheduler))
        try { runCurrent(); Fixture(this,store,engine).block() }
        finally { engine.dispose(); runCurrent(); Files.deleteIfExists(path) }
    }
    private class Fixture(val scope:TestScope,val store:TelemetryStore,val engine:AlertEngineService) {
        suspend fun send(ms:Long,value:Double,key:String="Audit/Value",us:Long=ms*1_000,order:Long=0) {
            store.accept(TelemetryFrame(ms,"live-telemetry",key,value,timestampUs=us,sampleOrder=order)); scope.runCurrent()
        }
        val records get()=engine.alerts.value
        fun reset() { store.clear(); scope.runCurrent() }
    }
    @Test fun `target reset removes old live alerts without needing another frame`()=runTest { withEngine {
        send(100,11.0); assertEquals(1,records.size); reset(); assertTrue(records.isEmpty())
    } }
    @Test fun `queued old target frame cannot recreate a cleared alarm`()=runTest { withEngine {
        store.accept(TelemetryFrame(100,"live-telemetry","Audit/Value",11.0))
        store.clear(); scope.runCurrent(); assertTrue(records.isEmpty())
    } }
    @Test fun `new target clock can restart below the prior high water mark`()=runTest { withEngine {
        send(1_000,9.0); reset(); send(10,12.0)
        assertEquals(10L,records.single().triggerTimestampMs)
    } }
    @Test fun `old motor state cannot combine with new target current`()=runTest { withEngine {
        send(100,0.8,"Hardware/Motors/fl/Power"); send(100,0.0,"Hardware/Motors/fl/Velocity")
        reset(); send(200,10.0,"Hardware/Motors/fl/CurrentAmps")
        assertTrue(records.isEmpty())
    } }
    @Test fun `loop windows restart at target changes`()=runTest { withEngine {
        send(100,30.0,"Robot/LoopTimeMs"); send(120,30.0,"Robot/LoopTimeMs")
        reset(); send(140,30.0,"Robot/LoopTimeMs"); assertTrue(records.isEmpty())
    } }
    @Test fun `older microsecond sample cannot resolve a newer same millisecond fault`()=runTest { withEngine {
        send(100,11.0,us=100_900); send(100,9.0,us=100_100)
        assertNull(records.single().resolveTimestampMs)
    } }
    @Test fun `sample order disambiguates matching source timestamps`()=runTest { withEngine {
        send(100,11.0,order=2); send(100,9.0,order=1)
        assertNull(records.single().resolveTimestampMs)
        send(100,9.0,order=3); assertEquals(100L,records.single().resolveTimestampMs)
    } }
    @Test fun `duplicate frame identities do not multiply loop overrun evidence`()=runTest { withEngine {
        repeat(3) { send(100,30.0,"Robot/LoopTimeMs") }; assertTrue(records.isEmpty())
        send(100,30.0,"Robot/LoopTimeMs",order=1); send(100,30.0,"Robot/LoopTimeMs",order=2)
        assertEquals(1,records.size)
    } }
    @Test fun `restart does not reinterpret retained frames as fresh fault evidence`()=runTest { withEngine {
        engine.stop(); scope.runCurrent()
        send(100,11.0)
        engine.startEngine(); scope.runCurrent(); assertTrue(records.isEmpty())
        send(101,12.0); assertEquals(1,records.size)
    } }
    @Test fun `a new target publication may reuse an immutable frame object from retained history`()=runTest { withEngine {
        engine.stop(); scope.runCurrent()
        val reused=TelemetryFrame(100,"live-telemetry","Audit/Value",11.0)
        store.accept(reused)
        engine.startEngine(); scope.runCurrent(); assertTrue(records.isEmpty())
        store.clear(); store.accept(reused); scope.runCurrent()
        assertEquals(1,records.size)
    } }
    @Test fun `raw source stream retains intermediate samples rather than only latest values`()=runTest { withEngine {
        store.accept(TelemetryFrame(100,"live-telemetry","Robot/LoopTimeMs",30.0))
        store.accept(TelemetryFrame(120,"live-telemetry","Robot/LoopTimeMs",30.0))
        store.accept(TelemetryFrame(140,"live-telemetry","Robot/LoopTimeMs",30.0))
        scope.runCurrent(); assertEquals(1,records.size)
    } }
}
