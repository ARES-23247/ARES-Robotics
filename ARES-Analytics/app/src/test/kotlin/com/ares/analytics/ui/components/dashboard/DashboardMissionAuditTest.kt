package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlin.test.*

class DashboardMissionAuditTest {
    private val workspace=WorkspaceConfig(id="audit",robotId="robot",robotName="Robot",teamId="1",seasonId="2026",league=League.FTC,projectPath="/fixture")
    private fun live()=DashboardMissionSnapshot(workspace,true,false,false,false,null,
        loopTimeMs=20.0,batteryVoltage=12.6,brownoutCount=0,loopOverruns=0,lastUpdateAgeMs=0)
    private fun alert(id:String,key:String="battery_low",session:String="live-telemetry",time:Long=1,resolved:Long?=null)=
        AlertRecord(id,session,key,time,resolveTimestampMs=resolved)

    @Test fun `replay switch never displays previous selected session`() {
        val old=ReplayFrame(0,emptyMap(),sessionId="old")
        assertNull(selectDashboardReplayFrame(old,"new",true))
        val matching=old.copy(sessionId="new")
        assertSame(matching,selectDashboardReplayFrame(matching,"new",false))
    }
    @Test fun `live rewind cannot borrow an unrelated historical frame`() {
        assertNull(selectDashboardReplayFrame(ReplayFrame(0,emptyMap(),sessionId="archive"),null,true))
        val live=ReplayFrame(0,emptyMap(),sessionId="live-telemetry")
        assertSame(live,selectDashboardReplayFrame(live,null,true))
        assertNull(selectDashboardReplayFrame(live,null,false))
    }
    @Test fun `missing empty and case mismatched replay identities remain unavailable`() {
        assertNull(selectDashboardReplayFrame(null,"selected",true))
        assertNull(selectDashboardReplayFrame(ReplayFrame(0,emptyMap()),"",true))
        assertNull(selectDashboardReplayFrame(ReplayFrame(0,emptyMap(),sessionId="Selected"),"selected",true))
    }
    @Test fun `disconnected live rewind is described as replay`() {
        val snapshot=live().copy(isConnected=false,isReplayActive=true)
        assertTrue(snapshot.healthSummary.contains("Replaying"))
        assertFalse(snapshot.healthSummary.contains("No live connection"))
    }
    @Test fun `historical evidence never has a live freshness classification`() {
        assertNotEquals(TelemetryFreshness.FRESH,live().copy(primarySessionId="recorded").freshness)
    }
    @Test fun `selected replay without a matching frame does not claim playback evidence`() {
        val summary=live().copy(primarySessionId="recorded",lastUpdateAgeMs=-1).healthSummary
        assertTrue(summary.contains("Waiting",ignoreCase=true))
        assertFalse(summary.startsWith("Replaying"))
    }
    @Test fun `simulator source does not classify estimated state as ground truth`() {
        val source=live().copy(isLocalSimulator=true,isSimulatorRunning=true).sourceType
        assertEquals("SIMULATED",source.badge)
        assertTrue(source.explanation.contains("estimated",ignoreCase=true))
    }
    @Test fun `missing overrun and brownout counters cannot become nominal zeros`() {
        for (snapshot in listOf(live().copy(loopOverruns=null),live().copy(brownoutCount=null))) {
            assertTrue(snapshot.healthSummary.contains("incomplete"))
            assertFalse(snapshot.healthSummary.contains("0 overruns"))
        }
    }
    @Test fun `active alerts prevent an all nominal summary`() {
        val summary=live().copy(activeAlerts=listOf(alert("fault"))).healthSummary
        assertFalse(summary.contains("nominal",ignoreCase=true))
        assertTrue(summary.contains("alert",ignoreCase=true))
    }
    @Test fun `unknown battery during brownout is not described as measured low voltage`() {
        val summary=live().copy(brownoutCount=1,batteryVoltage=null).healthSummary
        assertTrue(summary.contains("unknown",ignoreCase=true))
        assertFalse(summary.contains("(low)"))
    }
    @Test fun `invalid numeric evidence cannot create nominal or fabricated measurements`() {
        for (snapshot in listOf(live().copy(loopTimeMs=Double.MIN_VALUE),live().copy(batteryVoltage=-1.0),
            live().copy(loopOverruns=-1),live().copy(brownoutCount=-1))) {
            assertTrue(snapshot.healthSummary.contains("incomplete"))
            assertFalse(snapshot.healthSummary.contains("Infinity"))
        }
    }
    @Test fun `resolved alerts cannot take current priority`() {
        val current=alert("current","temperature_warm")
        assertEquals(current,live().copy(activeAlerts=listOf(alert("resolved",resolved=2),current)).highestPriorityAlert)
    }
    @Test fun `other recording alerts cannot leak into live mission status`() {
        assertNull(live().copy(activeAlerts=listOf(alert("old",session="archive"))).highestPriorityAlert)
    }
    @Test fun `replay does not promote historical events as current live alarms`() {
        assertNull(live().copy(isReplayActive=true,primarySessionId="archive",activeAlerts=listOf(alert("past",session="archive"))).highestPriorityAlert)
    }
    @Test fun `disconnected mission cannot present cached live alarms as current`() {
        assertNull(live().copy(isConnected=false,activeAlerts=listOf(alert("cached"))).highestPriorityAlert)
    }
    @Test fun `cancelled and mechanical names are not CAN bus diagnoses`() {
        for (key in listOf("action_cancelled","mechanical_warning","scan_complete")) {
            assertFalse(criticalAlertTitle(key).contains("CANBUS"))
        }
        assertEquals("CANBUS HARDWARE ERROR",criticalAlertTitle("Hardware/CAN/Utilization"))
    }
    @Test fun `equally urgent alert selection is deterministic and newest first`() {
        val older=alert("old",time=1); val newer=alert("new",time=2)
        assertEquals(newer,live().copy(activeAlerts=listOf(older,newer)).highestPriorityAlert)
        val a=alert("a",time=2); val z=alert("z",time=2)
        assertEquals(a,live().copy(activeAlerts=listOf(z,a)).highestPriorityAlert)
    }
    @Test fun `critical notices precede newer routine notices`() {
        val critical=alert("critical",time=1)
        assertEquals(critical,live().copy(activeAlerts=listOf(alert("routine","temperature",time=10),critical)).highestPriorityAlert)
    }
    @Test fun `freshness boundaries remain exact for live evidence`() {
        assertEquals(TelemetryFreshness.INACTIVE,live().copy(lastUpdateAgeMs=-1).freshness)
        assertEquals(TelemetryFreshness.FRESH,live().copy(lastUpdateAgeMs=500).freshness)
        assertEquals(TelemetryFreshness.STALE,live().copy(lastUpdateAgeMs=501).freshness)
    }
}
