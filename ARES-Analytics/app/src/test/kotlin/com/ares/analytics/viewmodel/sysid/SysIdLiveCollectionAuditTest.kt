package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.service.*
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.viewmodel.SysIdState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SysIdLiveCollectionAuditTest {
    private class Client(db: DatabaseService): Nt4ClientService(db) {
        val frames = MutableSharedFlow<TelemetryFrame>()
        override val telemetryFlow: SharedFlow<TelemetryFrame> = frames
    }
    private class Fixture(val scope: TestScope, kind: String, maxSamples: Int, previewLimit: Int) {
        val file = File.createTempFile("sysid-live-audit", ".duckdb")
        val db = DatabaseService(file.absolutePath)
        val client = Client(db)
        val state = MutableStateFlow(SysIdState(isRobotConnected=true, isRoutineRunning=true, isLoading=true, activeCalibration=kind))
        var completions = 0
        var whenStopped: suspend () -> Unit = {}
        private val service = SysIdService(db)
        val collector = SysIdDataCollector(client, service, AutoTunerService(client,service), state,
            scope.backgroundScope, SysIdRegressionSolver(state), maxSamples=maxSamples, previewLimit=previewLimit,
            onRoutineCompleted={ completions++; whenStopped() })
        suspend fun send(index: Int, value: Double, us: Long=1000, session: String="run") {
            client.frames.emit(TelemetryFrame(us/1000,session,"SysId/Data/$index",value,timestampUs=us))
            scope.runCurrent()
        }
        suspend fun row(time: Double=20.0, us: Long=1000) {
            doubleArrayOf(time,6.0,100.0,3.0,2.0).forEachIndexed { i,v -> send(i,v,us) }
        }
        suspend fun finish() {
            client.frames.emit(TelemetryFrame(100,"run","SysId/Status",0.0,"NONE"))
            scope.runCurrent()
        }
    }
    private fun test(kind: String="DYNAMIC", maxSamples: Int=20_000, previewLimit: Int=1_000, block: suspend Fixture.() -> Unit) = runTest {
        val f = Fixture(this,kind,maxSamples,previewLimit)
        try {
            f.collector.startCollecting()
            runCurrent()
            f.block()
        } finally { f.client.stop(); f.db.close(); f.file.delete() }
    }

    @Test fun `last channel alone never makes a complete sample`() = test {
        send(4,2.0)
        assertTrue(state.value.liveSamples.isEmpty())
    }
    @Test fun `channels may arrive out of order without inventing zeros`() = test {
        for ((i,v) in listOf(4 to 2.0,0 to 20.0,2 to 100.0,1 to 6.0,3 to 3.0)) send(i,v)
        assertEquals(listOf(AlignedDataRow(20,6.0,3.0,2.0)),state.value.liveSamples)
    }
    @Test fun `distinct microsecond rows within one millisecond cannot mix`() = test {
        send(0,20.0,1001); send(0,40.0,1002)
        for(i in 1..4) { send(i,i.toDouble(),1001); send(i,i*10.0,1002) }
        finish()
        assertEquals(listOf(20L,40L),state.value.liveSamples.map { it.timestampMs })
        assertEquals(listOf(1.0,10.0),state.value.liveSamples.map { it.voltage })
    }
    @Test fun `duplicate final channels do not duplicate samples`() = test {
        row(); send(4,2.0); finish()
        assertEquals(1,state.value.liveSamples.size)
    }
    @Test fun `geometric calibration uses its own required columns and no motor sample`() = test("LINEAR_DRIVE") {
        send(0,20.0); send(1,0.2); send(2,3000.0); finish()
        assertTrue(state.value.liveSamples.isEmpty())
        assertEquals(1,state.value.liveCalibrationData.size)
        assertContentEquals(doubleArrayOf(20.0,0.2,3000.0),state.value.liveCalibrationData.single())
    }
    @Test fun `malformed packed string cannot shift columns`() = test {
        client.frames.emit(TelemetryFrame(1,"run","SysId/Data",0.0,"20|6|bad|3|2|1"))
        scope.runCurrent()
        assertTrue(state.value.liveSamples.isEmpty())
    }
    @Test fun `idle telemetry cannot append a run`() = test {
        state.value=state.value.copy(isRoutineRunning=false,isLoading=false)
        row()
        assertTrue(state.value.liveSamples.isEmpty())
    }
    @Test fun `clearing a run removes incomplete channel history`() = test {
        send(0,20.0);send(1,6.0)
        collector.clearBuffer()
        send(2,100.0);send(3,3.0);send(4,2.0)
        assertTrue(state.value.liveSamples.isEmpty())
    }
    @Test fun `different sessions cannot complete each others partial rows`() = test {
        send(0,20.0,session="first");send(1,6.0,session="first")
        send(2,100.0,session="second");send(3,3.0,session="second");send(4,2.0,session="second")
        assertTrue(state.value.liveSamples.isEmpty())
    }
    @Test fun `nonfinite or fractional row values cannot become samples`() = test {
        row(time=0.5)
        assertTrue(state.value.liveSamples.isEmpty())
        collector.clearBuffer()
        send(0,20.0);send(1,Double.NaN);send(2,100.0);send(3,3.0);send(4,2.0)
        assertTrue(state.value.liveSamples.isEmpty())
    }
    @Test fun `starting collection twice creates only one subscriber`() = test {
        collector.startCollecting();scope.runCurrent();row();finish()
        assertEquals(1,state.value.liveSamples.size)
        assertEquals(1,completions)
    }

    @Test fun `preview is bounded but completion contains the entire accepted run`() = test(maxSamples=8,previewLimit=2) {
        row(20.0,1000)
        val first = state.value.liveSamples
        for(i in 2..5) row(i*20.0,i*100000L)
        assertEquals(listOf(80L,100L),state.value.liveSamples.map { it.timestampMs })
        assertEquals(listOf(20L),first.map { it.timestampMs })
        finish()
        assertEquals(5,state.value.liveSamples.size)
    }
    @Test fun `preview is throttled between full snapshots`() = test {
        row(); val preview=state.value.liveSamples
        row(40.0,2000)
        assertSame(preview,state.value.liveSamples)
        finish()
        assertEquals(2,state.value.liveSamples.size)
    }
    @Test fun `capacity failure requests stop once and never analyzes a truncated run`() = test(maxSamples=3,previewLimit=2) {
        for(i in 1..5) row(i*20.0,i*100000L)
        assertEquals(1,completions)
        assertFalse(state.value.isRoutineRunning)
        assertTrue(state.value.errorMessage!!.contains("exceeded"))
        assertNull(state.value.summary)
        finish()
        assertEquals(1,completions)
        assertNull(state.value.summary)
    }
    @Test fun `completed analysis cannot overwrite a new run started during stop`() = test {
        row()
        whenStopped={ collector.clearBuffer(); state.value=state.value.copy(isRoutineRunning=true,isLoading=true) }
        finish()
        assertTrue(state.value.liveSamples.isEmpty())
        assertNull(state.value.summary)
        assertTrue(state.value.isRoutineRunning)
    }
    @Test fun `wrong-session status and replay cannot terminate or populate live collection`() = test {
        row()
        client.frames.emit(TelemetryFrame(100,"other","SysId/Status",0.0,"NONE"));scope.runCurrent()
        assertEquals(0,completions)
        client.isReplayActive.value=true
        row(40.0,2000);finish()
        assertEquals(0,completions)
        client.isReplayActive.value=false
        finish()
        assertEquals(1,state.value.liveSamples.size)
    }
    @Test fun `late active status does not revive a locally stopped run`() = test {
        row()
        state.value=state.value.copy(isRoutineRunning=false,isLoading=false)
        client.frames.emit(TelemetryFrame(2,"run","SysId/Status",0.0,"DYNAMIC"));scope.runCurrent()
        assertFalse(state.value.isRoutineRunning)
        finish();finish()
        assertEquals(1,completions)
    }
    @Test fun `published calibration arrays cannot mutate the analysis snapshot`() = test("LINEAR_DRIVE") {
        for(i in 0..19) {
            send(0,i*20.0,(i+1)*1000L);send(1,i*0.1,(i+1)*1000L);send(2,3000.0,(i+1)*1000L)
        }
        whenStopped={ state.value.liveCalibrationData.last()[1]=999.0 }
        finish()
        assertEquals(2850.0,state.value.recommendedTicksPerMeter!!,1e-9)
    }
    @Test fun `failed stop does not publish a fit and collector can accept a later new run`() = test {
        row();whenStopped={ error("stop unavailable") };finish()
        assertNull(state.value.summary)
        assertTrue(state.value.errorMessage!!.contains("stop unavailable"))
        collector.clearBuffer();whenStopped={}
        state.value=state.value.copy(isRoutineRunning=true,isLoading=true)
        row(40.0,2000);finish()
        assertEquals(2,completions)
        assertEquals(1,state.value.liveSamples.size)
    }
    @Test fun `empty completion leaves loading and reports missing data only once`() = test {
        finish();finish()
        assertEquals(1,completions)
        assertFalse(state.value.isLoading)
        assertNull(state.value.summary)
        assertNotNull(state.value.errorMessage)
    }
    @Test fun `mechanism changes invalidate an in-progress dataset`() = test {
        row()
        state.value=state.value.copy(selectedMechanism=com.areslib.control.assist.SysIdMechanism.ANGULAR)
        row(40.0,2000)
        assertEquals(1,completions)
        assertTrue(state.value.errorMessage!!.contains("Mechanism changed"))
        assertNull(state.value.summary)
    }
    @Test fun `conflicting payload times never overwrite accepted samples`() = test {
        row()
        for((i,v) in doubleArrayOf(20.0,7.0,100.0,3.0,2.0).withIndex()) send(i,v,2000)
        assertEquals(1,completions)
        assertTrue(state.value.errorMessage!!.contains("Conflicting"))
        assertEquals(6.0,state.value.liveSamples.single().voltage)
    }
    @Test fun `acknowledged status starts a pending collection and unknown status is ignored`() = test {
        state.value=state.value.copy(isRoutineRunning=false,isLoading=true)
        client.frames.emit(TelemetryFrame(1,"run","SysId/Status",0.0,"bogus"));scope.runCurrent()
        assertFalse(state.value.isRoutineRunning)
        client.frames.emit(TelemetryFrame(1,"run","SysId/Status",0.0,"DYNAMIC"));scope.runCurrent()
        assertTrue(state.value.isRoutineRunning)
        row();finish()
        assertEquals(1,state.value.liveSamples.size)
    }
    @Test fun `cancelled stop retains coroutine cancellation and permits explicit collector restart`() = test {
        row();whenStopped={ throw kotlinx.coroutines.CancellationException("cancelled stop") };finish()
        assertNull(state.value.summary)
        collector.clearBuffer();whenStopped={}
        state.value=state.value.copy(isRoutineRunning=true,isLoading=true)
        collector.startCollecting();scope.runCurrent();row(40.0,2000);finish()
        assertEquals(2,completions)
        assertEquals(1,state.value.liveSamples.size)
    }
    @Test fun `failure of an older stop callback cannot fault a newly started run`() = test {
        row()
        whenStopped={
            collector.clearBuffer()
            state.value=state.value.copy(isRoutineRunning=true,isLoading=true,errorMessage=null)
            error("old stop failed")
        }
        finish()
        assertTrue(state.value.isRoutineRunning)
        assertNull(state.value.errorMessage)
        assertNull(state.value.summary)
    }
    @Test fun `mechanism selection during stop cannot relabel an older fit`() = test {
        row()
        whenStopped={ state.value=state.value.copy(selectedMechanism=com.areslib.control.assist.SysIdMechanism.ANGULAR) }
        finish()
        assertNull(state.value.summary)
        assertNull(state.value.tuningRecommendation)
    }
}
