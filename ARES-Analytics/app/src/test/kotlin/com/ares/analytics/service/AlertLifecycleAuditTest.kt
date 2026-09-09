package com.ares.analytics.service

import com.ares.analytics.shared.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mockito.Mockito.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AlertLifecycleAuditTest {
    private suspend fun TestScope.withEngine(
        rule: ThresholdRule = ThresholdRule("Audit/Value", "Audit", minValue=10.0, audibleAlert=false),
        realDatabase: Boolean = false,
        block: suspend Fixture.() -> Unit,
    ) {
        val flow=TelemetryStore()
        val databasePath = if (realDatabase) Files.createTempFile("alert-records", ".duckdb") else null
        val db = databasePath?.let { DatabaseService(it.toString()) } ?: mock(DatabaseService::class.java)
        val nt=mock(Nt4ClientService::class.java)
        `when`(nt.telemetryFlow).thenReturn(flow.updates)
        `when`(nt.telemetryStore).thenReturn(flow)
        val path=Files.createTempFile("alert-lifecycle", ".json")
        Files.writeString(path,Json.encodeToString(listOf(rule)))
        val engine=AlertEngineService(db,nt,path.toString(),StandardTestDispatcher(testScheduler))
        try { runCurrent(); Fixture(this,engine,flow,db).block() }
        finally {
            engine.dispose(); runCurrent()
            if (databasePath != null) { db.close(); Files.deleteIfExists(databasePath) }
            Files.deleteIfExists(path)
        }
    }
    private class Fixture(val scope:TestScope,val engine:AlertEngineService,val flow:TelemetryStore,val db:DatabaseService) {
        suspend fun send(time:Long,value:Double,key:String="Audit/Value") {
            flow.accept(TelemetryFrame(time,"recording",key,value)); scope.runCurrent()
        }
        suspend fun triage() { engine.triageAlert(engine.alerts.value.single().alertId); scope.runCurrent() }
        val records get()=engine.alerts.value
    }
    @Test fun `acknowledged active fault still resolves`()=runTest { withEngine {
        send(100,9.0); triage(); send(120,11.0)
        assertEquals(120L,records.single().resolveTimestampMs)
        assertTrue(records.single().triaged)
    } }
    @Test fun `acknowledgment suppresses duplicate active occurrences`()=runTest { withEngine {
        send(100,9.0); triage(); send(110,8.0)
        assertEquals(1,records.size); assertEquals(8.0,records.single().peakValue)
    } }
    @Test fun `recurrence preserves closed evidence and starts a fresh interval`()=runTest { withEngine {
        send(100,9.0); send(120,11.0); send(200,8.0); send(210,12.0)
        assertEquals(2,records.size)
        assertEquals(listOf(200L,100L),records.map { it.triggerTimestampMs })
        assertEquals(listOf(10L,20L),records.map { it.durationMs })
        assertEquals(listOf(8.0,9.0),records.map { it.peakValue })
        assertEquals(2,records.map { it.alertId }.toSet().size)
    } }
    @Test fun `recurrence after acknowledgment requires new acknowledgment`()=runTest { withEngine {
        send(100,9.0); triage(); send(120,11.0); send(200,8.0)
        assertEquals(2,records.size); assertFalse(records.first().triaged)
        assertEquals(120L,records.last().resolveTimestampMs)
    } }
    @Test fun `invalid samples neither resolve nor poison peaks`()=runTest { withEngine {
        send(100,9.0)
        for((index,v) in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY).withIndex()) {
            send(110L+index,v)
            assertNull(records.single().resolveTimestampMs, "$v must not resolve a fault")
            assertEquals(9.0,records.single().peakValue, "$v must not poison the peak")
        }
    } }
    @Test fun `older samples cannot resolve newer evidence`()=runTest { withEngine {
        send(100,9.0); send(150,8.0); send(140,12.0)
        assertNull(records.single().resolveTimestampMs)
        send(160,12.0); assertEquals(60L,records.single().durationMs)
    } }
    @Test fun `supported source timestamp endpoints retain exact duration`()=runTest { withEngine {
        send(0,9.0); send(MAX_SUPPORTED_TIMESTAMP_MS,12.0)
        assertEquals(MAX_SUPPORTED_TIMESTAMP_MS,records.single().durationMs)
    } }
    @Test fun `two sided rule retains largest excursion beyond its violated bound`()=runTest {
        withEngine(ThresholdRule("Audit/Value","Range",minValue=0.0,maxValue=10.0,audibleAlert=false)) {
            send(100,11.0); send(110,-5.0); send(120,12.0)
            assertEquals(-5.0,records.single().peakValue)
        }
    }
    @Test fun `unchanged peak does not cause repeated database writes`()=runTest { withEngine {
        send(100,9.0); clearInvocations(db)
        repeat(20) { send(101L+it,9.5) }
        verifyNoInteractions(db)
    } }
    @Test fun `composite loop alerts retain worsening peaks`()=runTest { withEngine {
        send(100,120.0,"Robot/LoopTimeMs"); send(110,160.0,"Robot/LoopTimeMs")
        assertEquals(160.0,records.single().peakValue)
    } }
    @Test fun `triaged resolved records can be cleared while live evidence remains`()=runTest { withEngine {
        send(100,9.0); triage(); send(120,11.0)
        engine.clearAllResolvedAlerts(); scope.runCurrent(); assertTrue(records.isEmpty())
        send(200,8.0); triage(); engine.clearAllResolvedAlerts(); scope.runCurrent()
        assertEquals(1,records.size); assertNull(records.single().resolveTimestampMs)
    } }
    @Test fun `text placeholders do not become numerical fault evidence`()=runTest { withEngine {
        flow.accept(TelemetryFrame(100,"recording","Audit/Value",0.0,stringValue="unavailable"))
        scope.runCurrent(); assertTrue(records.isEmpty())
    } }
    @Test fun `database preserves separate recurring intervals and acknowledgment`()=runTest {
        withEngine(realDatabase=true) {
            send(100,9.0); triage(); send(120,11.0); send(200,8.0); send(210,12.0)
            val stored = withContext(Dispatchers.IO) {
                withTimeout(5_000) {
                    var rows = db.getAlerts("recording")
                    while (rows.size != 2 || rows.any { it.resolveTimestampMs == null }) {
                        delay(5)
                        rows = db.getAlerts("recording")
                    }
                    rows
                }
            }
            assertEquals(listOf(100L,200L),stored.map { it.triggerTimestampMs })
            assertEquals(listOf(20L,10L),stored.map { it.durationMs })
            assertEquals(listOf(true,false),stored.map { it.triaged })
            assertEquals(listOf(9.0,8.0),stored.map { it.peakValue })
        }
    }
    @Test fun `inclusive threshold endpoints are healthy`()=runTest {
        withEngine(ThresholdRule("Audit/Value","Range",minValue=0.0,maxValue=10.0,audibleAlert=false)) {
            send(0,0.0); send(1,10.0); assertTrue(records.isEmpty())
        }
    }
}
