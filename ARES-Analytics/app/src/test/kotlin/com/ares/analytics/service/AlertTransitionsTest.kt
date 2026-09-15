package com.ares.analytics.service

import com.ares.analytics.shared.models.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class AlertTransitionsTest {
    private val alert = AlertRecord("a","s","Audit/Value",100,peakValue=9.0)
    @Test fun `abandoned CAS outcome does not escape after retry becomes noop`() {
        val state=MutableStateFlow(mapOf("a" to alert))
        var attempts=0
        val result=commitAlertTransition(state) { current ->
            attempts++
            if (current.getValue("a").triaged) null else {
                state.value=mapOf("a" to alert.copy(triaged=true))
                AlertOutcome(alert.copy(peakValue=8.0),true)
            }
        }
        assertNull(result); assertEquals(2,attempts)
        assertEquals(alert.copy(triaged=true),state.value.getValue("a"))
    }
    @Test fun `CAS retry preserves an unrelated concurrent record`() {
        val state=MutableStateFlow(mapOf("a" to alert))
        val other=alert.copy(alertId="b",sessionId="other")
        var attempts=0
        val result=commitAlertTransition(state) {
            if (attempts++==0) state.value=state.value+("b" to other)
            AlertOutcome(alert.copy(peakValue=8.0),false)
        }
        assertEquals(2,attempts); assertEquals(8.0,result!!.alert.peakValue)
        assertEquals(other,state.value["b"])
    }
    @Test fun `identical transition has no persistence or beep outcome`() {
        val state=MutableStateFlow(mapOf("a" to alert))
        assertNull(commitAlertTransition(state) { AlertOutcome(alert,true) })
    }
    @Test fun `extreme finite lower excursions remain ordered without infinity ties`() {
        val rule=ThresholdRule("x","x",minValue=Double.MAX_VALUE)
        assertEquals(-Double.MAX_VALUE,alertPeak(-Double.MAX_VALUE/2,-Double.MAX_VALUE,rule))
    }
    @Test fun `subnormal excursions do not lose ordering to unconditional scaling`() {
        val rule=ThresholdRule("x","x",minValue=0.0)
        assertEquals(-2*Double.MIN_VALUE,alertPeak(-Double.MIN_VALUE,-2*Double.MIN_VALUE,rule))
    }
    @Test fun `equal two sided excursions retain the earlier evidence`() {
        val rule=ThresholdRule("x","x",minValue=0.0,maxValue=10.0)
        assertEquals(-2.0,alertPeak(-2.0,12.0,rule))
    }
    @Test fun `resolved records remain unchanged while new occurrence gets its own identity`() {
        val closed=alert.copy(resolveTimestampMs=110,durationMs=10,triaged=true)
        val before=mapOf("a" to closed)
        val result=alertTransition(before,ThresholdRule("Audit/Value","low",minValue=10.0),
            "/Audit/Value","s",200,8.0,true)!!
        assertEquals(closed,before["a"]); assertNotEquals("a",result.alert.alertId)
        assertEquals(200L,result.alert.triggerTimestampMs); assertFalse(result.alert.triaged)
        assertTrue(result.shouldBeep)
    }
    @Test fun `old direct transition cannot produce negative duration`() {
        assertNull(alertTransition(mapOf("a" to alert),ThresholdRule("Audit/Value","low",minValue=10.0),
            "Audit/Value","s",99,12.0,false))
    }
}
