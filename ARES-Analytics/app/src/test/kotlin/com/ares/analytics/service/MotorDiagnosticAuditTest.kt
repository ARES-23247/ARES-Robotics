package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.*
import org.mockito.Mockito.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MotorDiagnosticAuditTest {
    private suspend fun TestScope.withEngine(rules:String="[]", block:suspend Fixture.()->Unit) {
        val store=TelemetryStore(); val nt=mock(Nt4ClientService::class.java); val db=mock(DatabaseService::class.java)
        `when`(nt.telemetryStore).thenReturn(store); `when`(nt.telemetryFlow).thenReturn(store.updates)
        val path=Files.createTempFile("motor-diagnostics", ".json"); Files.writeString(path,rules)
        val engine=AlertEngineService(db,nt,path.toString(),StandardTestDispatcher(testScheduler))
        try { runCurrent(); Fixture(this,store,engine).block() }
        finally { engine.dispose(); runCurrent(); Files.deleteIfExists(path) }
    }
    private class Fixture(val scope:TestScope,val store:TelemetryStore,val engine:AlertEngineService) {
        suspend fun send(us:Long,field:String,value:Double,motor:String="fl") {
            store.accept(TelemetryFrame(us/1000,"live-telemetry","Hardware/Motors/$motor/$field",value,timestampUs=us)); scope.runCurrent()
        }
        val active get()=engine.alerts.value.filter { it.resolveTimestampMs==null }
        suspend fun stall() { send(100_000,"Power",0.8); send(100_000,"Velocity",0.0); send(100_000,"CurrentAmps",10.0) }
    }
    @Test fun `missing velocity is not measured zero`()=runTest { withEngine {
        send(100_000,"Power",0.8); send(100_000,"CurrentAmps",10.0); assertTrue(active.isEmpty())
    } }
    @Test fun `voltage cannot substitute for normalized duty`()=runTest { withEngine {
        send(100_000,"Voltage",6.0); send(100_000,"Velocity",0.0); send(100_000,"CurrentAmps",10.0)
        assertTrue(active.isEmpty())
    } }
    @Test fun `old power and velocity cannot create a fresh current fault`()=runTest { withEngine {
        send(100_000,"Power",0.8); send(100_000,"Velocity",0.0); send(1_100_001,"CurrentAmps",10.0)
        assertTrue(active.isEmpty())
    } }
    @Test fun `unrelated motor activity cannot resolve an unobserved motor fault`()=runTest { withEngine {
        stall(); send(1_200_000,"Power",0.0,"fr")
        assertTrue(active.any { it.ruleKey.endsWith("fl/Stall") })
    } }
    @Test fun `new unknown current invalidates cached evidence`()=runTest { withEngine {
        send(100_000,"Power",0.0); send(100_000,"Velocity",0.0); send(100_000,"CurrentAmps",10.0)
        send(200_000,"CurrentAmps",Double.NaN); send(300_000,"Power",0.8)
        assertTrue(active.isEmpty())
    } }
    @Test fun `invalid negative current cannot resolve a fault`()=runTest { withEngine {
        stall(); send(200_000,"CurrentAmps",-20.0)
        assertTrue(active.any { it.ruleKey.endsWith("fl/Stall") })
    } }
    @Test fun `out of range duty cannot create a fault`()=runTest { withEngine {
        send(100_000,"Power",2.0); send(100_000,"Velocity",0.0); send(100_000,"CurrentAmps",10.0)
        assertTrue(active.isEmpty())
    } }
    @Test fun `current window expires at exact microsecond boundary`()=runTest { withEngine {
        send(100_900,"CurrentAmps",10.0)
        send(1_100_900,"Power",0.8); send(1_100_900,"Velocity",0.0)
        send(1_100_901,"CurrentAmps",0.0)
        assertTrue(active.any { it.ruleKey.endsWith("fl/Disconnected") })
        assertFalse(active.any { it.ruleKey.endsWith("fl/Stall") })
    } }
    @Test fun `valid moving feedback resolves a known motor fault`()=runTest { withEngine {
        stall(); send(200_000,"Velocity",10.0)
        assertTrue(active.isEmpty())
    } }
    @Test fun `delayed current records the latest contributing source timestamp`()=runTest { withEngine {
        send(200_000,"Power",0.8); send(200_000,"Velocity",0.0); send(100_000,"CurrentAmps",10.0)
        assertEquals(200L,active.single().triggerTimestampMs)
    } }
    @Test fun `temperature uses configured threshold once and only its own observations`()=runTest {
        withEngine("""[{"key":"Hardware/Motors/fl/TempC","displayName":"Configured thermal limit","maxValue":90.0,"audibleAlert":false}]""") {
            send(100_000,"TempC",80.0); assertTrue(active.isEmpty())
            send(200_000,"TempC",95.0); val fault=active.single()
            send(300_000,"Power",0.0,"fr"); assertEquals(fault,active.single())
            send(400_000,"TempC",85.0); assertTrue(active.isEmpty())
            assertEquals(1,engine.alerts.value.size)
            assertEquals(400L,engine.alerts.value.single().resolveTimestampMs)
        }
    }
}
