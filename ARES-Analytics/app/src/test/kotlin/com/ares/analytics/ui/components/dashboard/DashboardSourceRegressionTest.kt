package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.shared.models.ConsoleMessage
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.ui.theme.AresTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.mockito.Mockito.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardSourceRegressionTest {
    @Test fun `replay cancels live gamepad writer and returning to live restores one subscriber`() = runTest {
        val frames = MutableSharedFlow<TelemetryFrame>()
        val service = mock(Nt4ClientService::class.java)
        `when`(service.uiTelemetryFlow).thenReturn(frames)
        val replay = mutableStateOf<ReplayFrame?>(null)
        val scene = ImageComposeScene(500, 300, coroutineContext = StandardTestDispatcher(testScheduler))
        fun pump() { repeat(3) { runCurrent(); scene.render(testScheduler.currentTime * 1_000_000).close() } }
        try {
            scene.setContent {
                AresTheme { SingleGamepadVisualizer("Gamepad", "Gamepad1", replay.value, service, false, null, null) }
            }
            pump()
            assertEquals(1, frames.subscriptionCount.value)
            replay.value = ReplayFrame(10L, mapOf("Gamepad1/A" to 1.0))
            pump()
            assertEquals(0, frames.subscriptionCount.value, "Live telemetry must not overwrite the replay source")
            replay.value = null
            pump()
            assertEquals(1, frames.subscriptionCount.value)
        } finally { scene.close(); runCurrent() }
        assertEquals(0, frames.subscriptionCount.value)
    }

    @Test fun `same sized console buffer and replay replacements invalidate filtered snapshots`() = runTest {
        val messages = mutableStateListOf(
            ConsoleMessage(timestampMs = 10L, severity = "INFO", text = "first"),
            ConsoleMessage(timestampMs = 20L, severity = "INFO", text = "second"),
        )
        val playhead = mutableStateOf<Long?>(null)
        var visible = emptyList<String>()
        val scene = ImageComposeScene(10, 10, coroutineContext = StandardTestDispatcher(testScheduler))
        fun pump() { repeat(3) { runCurrent(); scene.render(testScheduler.currentTime * 1_000_000).close() } }
        try {
            scene.setContent {
                val selected = rememberConsoleDisplayMessages(messages, playhead.value)
                val filtered = remember(selected) { selected.map { it.text } }
                SideEffect { visible = filtered }
            }
            pump(); assertEquals(listOf("first", "second"), visible)
            messages.removeAt(0)
            messages.add(ConsoleMessage(timestampMs = 30L, severity = "INFO", text = "third"))
            pump(); assertEquals(listOf("second", "third"), visible)
            playhead.value = 25L
            pump(); assertEquals(listOf("second"), visible)
            messages[0] = messages[0].copy(text = "replacement session")
            pump(); assertEquals(listOf("replacement session"), visible)
        } finally { scene.close(); runCurrent() }
    }
}
