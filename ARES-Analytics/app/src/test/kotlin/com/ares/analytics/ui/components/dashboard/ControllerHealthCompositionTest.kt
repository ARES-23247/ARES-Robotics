package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import com.ares.analytics.service.MonotonicClock
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.TelemetryStore
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.mockito.Mockito.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerHealthCompositionTest {
    @Test fun `composition switches sources without carrying values or leaking subscriptions`() = runTest {
        val frames=MutableSharedFlow<TelemetryFrame>(replay=1)
        val store=TelemetryStore()
        val service=mock(Nt4ClientService::class.java)
        `when`(service.uiTelemetryFlow).thenReturn(frames)
        `when`(service.telemetryStore).thenReturn(store)
        val connected=mutableStateOf(true)
        val selected=mutableStateOf(false)
        val replay=mutableStateOf<ReplayFrame?>(null)
        var observed=ControllerHealthObservation()
        val clock=MonotonicClock { testScheduler.currentTime*1_000_000 }
        val scene=ImageComposeScene(10,10,coroutineContext=StandardTestDispatcher(testScheduler))
        fun pump() {
            runCurrent()
            scene.render(testScheduler.currentTime*1_000_000).close()
            runCurrent()
            scene.render(testScheduler.currentTime*1_000_000).close()
            runCurrent()
        }
        suspend fun publish(value:Double) {
            val frame=TelemetryFrame(0,"live","Robot/LoopTimeMs",value)
            store.accept(frame); frames.emit(frame)
        }
        try {
            scene.setContent {
                val health=rememberControllerHealth(service,replay.value,selected.value,connected.value,clock)
                SideEffect { observed=health }
            }
            pump(); assertEquals(1,frames.subscriptionCount.value)
            publish(20.0); advanceTimeBy(100); pump()
            assertEquals(20.0,observed.snapshot.loopTimeMs)
            selected.value=true; replay.value=ReplayFrame(0,mapOf("Robot/LoopTimeMs" to 30.0),sequence=1)
            pump(); assertEquals(0,frames.subscriptionCount.value)
            publish(90.0); advanceTimeBy(100); pump()
            assertEquals(30.0,observed.snapshot.loopTimeMs)
            replay.value=ReplayFrame(0,mapOf("Robot/LoopTimeMs" to 40.0),sequence=1)
            pump(); assertEquals(40.0,observed.snapshot.loopTimeMs)
            replay.value=null; pump()
            assertEquals(ControllerHealthSource.REPLAY,observed.source); assertNull(observed.snapshot.loopTimeMs)
            selected.value=false; pump()
            assertEquals(1,frames.subscriptionCount.value); assertNull(observed.snapshot.loopTimeMs)
            publish(25.0); advanceTimeBy(100); pump()
            assertEquals(25.0,observed.snapshot.loopTimeMs)
            connected.value=false; pump()
            assertEquals(ControllerHealthSource.OFFLINE,observed.source); assertNull(observed.snapshot.loopTimeMs)
            assertEquals(0,frames.subscriptionCount.value)
        } finally { scene.close(); runCurrent() }
        assertEquals(0,frames.subscriptionCount.value)
    }
}
