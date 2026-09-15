package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import com.ares.analytics.shared.models.AlertRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardAlertPresentationTest {
    private fun alert(id:String,key:String="battery_low",time:Long=1,source:String="live")=AlertRecord(id,source,key,time)

    @Test fun `classification preserves known categories with CAN segment boundaries`() {
        assertEquals(DashboardCriticalKind.CAN,dashboardCriticalKind("hardware/can/utilization"))
        assertEquals(DashboardCriticalKind.CAN,dashboardCriticalKind("CANBusFault"))
        assertEquals(DashboardCriticalKind.COMMS,dashboardCriticalKind("robot_comms_loss"))
        assertEquals(DashboardCriticalKind.BROWNOUT,dashboardCriticalKind("Diagnostics/Power/BrownoutCount"))
        assertEquals(DashboardCriticalKind.BATTERY,dashboardCriticalKind("Robot/BatteryVoltage"))
        for (key in listOf("cancelled","mechanical_warning","scanner_status")) assertNull(dashboardCriticalKind(key))
    }
    @Test fun `priority comparisons do not overflow at timestamp extremes`() {
        val old=alert("old",time=Long.MIN_VALUE); val recent=alert("new",time=Long.MAX_VALUE)
        assertEquals(recent,highestPriorityDashboardAlert(listOf(old,recent)))
        assertEquals(recent,highestPriorityDashboardAlert(listOf(recent,old)))
    }
    @Test fun `current alerts are an owned source filtered snapshot`() {
        val current=alert("current")
        val input=mutableListOf(current,alert("other",source="old"),alert("resolved").copy(resolveTimestampMs=2))
        val result=currentDashboardAlerts(input,"live",true)
        input.clear(); assertEquals(listOf(current),result)
        assertTrue(currentDashboardAlerts(listOf(current),"live",false).isEmpty())
    }
    @Test fun `triage is distinct from resolution of an active condition`() {
        val active=alert("active").copy(triaged=true)
        assertEquals(listOf(active),currentDashboardAlerts(listOf(active),"live",true))
    }
    @Test fun `alert detail preserves source time and rejects invalid numeric display`() {
        assertTrue(dashboardAlertDetail(alert("test",time=1234).copy(peakValue=12.0)).contains("Source time: 1234 ms"))
        val unknown=dashboardAlertDetail(alert("invalid",time=-1).copy(peakValue=Double.NaN))
        assertTrue(unknown.contains("Peak: --")); assertTrue(unknown.contains("unknown"))
        assertFalse(unknown.contains("NaN"))
        assertTrue(dashboardAlertDetail(alert("infinite").copy(peakValue=Double.POSITIVE_INFINITY)).contains("Peak: --"))
    }
    @Test fun `popup dismissal survives updates and resolved alerts are not readded`() = runTest {
        val original=alert("a")
        val alerts=mutableStateOf(listOf(original))
        val session=mutableStateOf("live")
        val enabled=mutableStateOf(true)
        var popups=DashboardAlertPopups(emptyList()) {}
        val scene=ImageComposeScene(10,10,coroutineContext=StandardTestDispatcher(testScheduler))
        fun pump() {
            runCurrent(); scene.render(testScheduler.currentTime*1_000_000).close()
            runCurrent(); scene.render(testScheduler.currentTime*1_000_000).close(); runCurrent()
        }
        try {
            scene.setContent {
                val state=rememberDashboardAlertPopups(alerts.value,session.value,enabled.value)
                SideEffect { popups=state }
            }
            pump(); assertEquals(listOf(original),popups.alerts)
            popups.dismiss(original); pump(); assertTrue(popups.alerts.isEmpty())
            val updated=original.copy(peakValue=99.0)
            alerts.value=listOf(updated); pump(); assertTrue(popups.alerts.isEmpty())
            alerts.value=listOf(updated.copy(resolveTimestampMs=2)); pump(); assertTrue(popups.alerts.isEmpty())
            alerts.value=listOf(updated); pump(); assertEquals(listOf(updated),popups.alerts)
            session.value="next"; pump(); assertTrue(popups.alerts.isEmpty())
            val next=alert("next",source="next")
            alerts.value=listOf(updated,next); pump(); assertEquals(listOf(next),popups.alerts)
            enabled.value=false; pump(); assertTrue(popups.alerts.isEmpty())
            enabled.value=true; pump(); assertEquals(listOf(next),popups.alerts)
        } finally { scene.close(); runCurrent() }
    }
}
