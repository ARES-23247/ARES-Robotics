package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.TelemetryStore
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.ui.theme.AresTheme
import com.areslib.telemetry.schema.HardwareTopology
import com.areslib.telemetry.schema.TopologyNode
import com.areslib.telemetry.schema.TopologyNodeType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.jetbrains.skia.EncodedImageFormat
import org.mockito.Mockito.*
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class HardwareTopologyRenderAuditTest {
    @Test fun `nested controller topology renders without duplicate lazy keys`() {
        val nt = mock(Nt4ClientService::class.java)
        val db = mock(DatabaseService::class.java)
        `when`(nt.latestTopology).thenReturn(MutableStateFlow(HardwareTopology("robot", listOf(
            TopologyNode("control", TopologyNodeType.CONTROL_HUB, "Control hub"),
            TopologyNode("expansion", TopologyNodeType.EXPANSION_HUB, "Expansion hub", parentId = "control"),
            TopologyNode("Motors/arm", TopologyNodeType.MOTOR, "Arm", parentId = "expansion", port = 0),
        ))))
        `when`(nt.isConnected).thenReturn(MutableStateFlow(true))
        val store = TelemetryStore()
        `when`(nt.latestValues).thenReturn(store.latestFrames)
        `when`(nt.telemetryStore).thenReturn(store)
        `when`(nt.uiTelemetryFlow).thenReturn(store.updates)
        val scene = ImageComposeScene(760, 560)
        try {
            scene.setContent { CompositionLocalProvider(LocalClipboardManager provides mock(ClipboardManager::class.java)) {
                AresTheme { HardwareTopologyCard(nt, db, null, Modifier.fillMaxSize()) }
            } }
            val image = scene.render()
            image.use { snapshot ->
                assertNotNull(snapshot.encodeToData(EncodedImageFormat.PNG)).use { data ->
                    val destination = File("build/diagnostics/topology-audit/nested.png")
                    destination.parentFile.mkdirs()
                    destination.writeBytes(data.bytes)
                }
            }
        } finally { scene.close() }
    }

    @Test fun `live telemetry updates invalidate the rendered card without topology changes`() = runTest {
        val nt = mock(Nt4ClientService::class.java)
        val db = mock(DatabaseService::class.java)
        val topology = HardwareTopology("robot", listOf(TopologyNode("Motors/arm", TopologyNodeType.MOTOR, "arm")))
        val store = TelemetryStore()
        `when`(nt.latestTopology).thenReturn(MutableStateFlow(topology))
        `when`(nt.isConnected).thenReturn(MutableStateFlow(true))
        `when`(nt.latestValues).thenReturn(store.latestFrames)
        `when`(nt.telemetryStore).thenReturn(store)
        `when`(nt.uiTelemetryFlow).thenReturn(store.updates)
        val clipboard = mock(ClipboardManager::class.java)
        val scene = ImageComposeScene(760, 400, coroutineContext = StandardTestDispatcher(testScheduler))
        fun pump(): ByteArray {
            runCurrent()
            scene.render(testScheduler.currentTime * 1_000_000).close()
            runCurrent()
            return scene.render(testScheduler.currentTime * 1_000_000).use { snapshot ->
                assertNotNull(snapshot.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
            }
        }
        try {
            scene.setContent { CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                AresTheme { HardwareTopologyCard(nt, db, null, Modifier.fillMaxSize()) }
            } }
            pump()
            store.accept(TelemetryFrame(0, "live-telemetry", "Hardware/Motors/arm/CurrentAmps", 1.0))
            advanceTimeBy(250)
            val first = pump()
            store.accept(TelemetryFrame(1, "live-telemetry", "Hardware/Motors/arm/CurrentAmps", 35.0))
            advanceTimeBy(250)
            val second = pump()
            val output = File("build/diagnostics/topology-audit").apply { mkdirs() }
            File(output, "live-current-before.png").writeBytes(first)
            File(output, "live-current-after.png").writeBytes(second)
            assertFalse(first.contentEquals(second), "Changing current must update the displayed value")
        } finally { scene.close(); runCurrent() }
    }

    @Test fun cachedCardNeverSubscribesToOrDisplaysLiveMotorReadings() = runTest {
        val nt = mock(Nt4ClientService::class.java)
        val db = mock(DatabaseService::class.java)
        val saved = HardwareTopology("saved-robot", listOf(TopologyNode("Motors/arm", TopologyNodeType.MOTOR, "Saved arm")))
        val store = TelemetryStore()
        val frames = kotlinx.coroutines.flow.MutableSharedFlow<TelemetryFrame>(replay = 1)
        store.accept(TelemetryFrame(0, "live-telemetry", "Hardware/Motors/arm/CurrentAmps", 77.0))
        org.mockito.BDDMockito.given(nt.latestTopology).willReturn(MutableStateFlow(HardwareTopology("live-robot")))
        org.mockito.BDDMockito.given(nt.isConnected).willReturn(MutableStateFlow(true))
        org.mockito.BDDMockito.given(nt.latestValues).willReturn(store.latestFrames)
        org.mockito.BDDMockito.given(nt.telemetryStore).willReturn(store)
        org.mockito.BDDMockito.given(nt.uiTelemetryFlow).willReturn(frames)
        org.mockito.BDDMockito.given(db.getSessionSummary("saved")).willReturn(
            com.ares.analytics.shared.models.SessionSummary("saved", "team", "season", "saved-robot", 0))
        org.mockito.BDDMockito.given(db.getTopology("saved-robot")).willReturn(saved)
        val clipboard = mock(ClipboardManager::class.java)
        val scene = ImageComposeScene(760, 400, coroutineContext = StandardTestDispatcher(testScheduler))
        try {
            scene.setContent { CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                AresTheme { HardwareTopologyCard(nt, db, "saved", Modifier.fillMaxSize()) }
            } }
            repeat(3) { runCurrent(); scene.render(testScheduler.currentTime * 1_000_000).close() }
            scene.render().use { snapshot ->
                assertNotNull(snapshot.encodeToData(EncodedImageFormat.PNG)).use { data ->
                    val destination = File("build/diagnostics/topology-audit/cached.png")
                    destination.parentFile.mkdirs(); destination.writeBytes(data.bytes)
                }
            }
            verify(nt, never()).latestValues
            verify(nt, never()).telemetryStore
            verify(nt, never()).uiTelemetryFlow
            assertEquals(0, frames.subscriptionCount.value)
        } finally { scene.close(); runCurrent() }
    }
}
