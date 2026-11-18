@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ares.analytics.ui.components.dashboard.analysis

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.*
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.mockito.Mockito.*
import kotlin.test.*

class AnalysisTopicPickerTest {
    @Test
    fun `search selects a topic beyond the first eighty`() = runComposeUiTest {
        var selected = ""
        val topics = (1..100).map { "Drive/Signal" + it.toString().padStart(3, '0') }
        setContent { AnalysisTopicSelectorChip("Signal", null, topics, { selected = it }) }
        onNodeWithText("None").performClick()
        onNodeWithText("Search topics").performTextInput("081")
        onNodeWithText("Drive/Signal081").performClick()
        assertEquals("Drive/Signal081", selected)
    }

    @Test
    fun `open live catalog receives successive samples and newly announced topics`() = runTest {
        val database = mock(DatabaseService::class.java)
        val nt = mock(Nt4ClientService::class.java)
        val frames = MutableSharedFlow<TelemetryFrame>(replay = 1)
        `when`(nt.uiTelemetryFlow).thenReturn(frames)
        `when`(nt.getActiveTopics()).thenReturn(listOf("A"), listOf("A"), listOf("A", "B"))
        var observed = SessionTopicCatalog()
        val scene = ImageComposeScene(10, 10, coroutineContext = StandardTestDispatcher(testScheduler))
        fun pump() {
            repeat(2) { runCurrent(); scene.render(testScheduler.currentTime * 1_000_000).close() }
            runCurrent()
        }
        try {
            scene.setContent {
                val catalog = rememberSessionTopicCatalog(database, "live-telemetry", nt)
                SideEffect { observed = catalog }
            }
            pump()
            for ((time, key) in listOf(10L to "A", 20L to "B")) {
                frames.emit(TelemetryFrame(time, "live-telemetry", key, 1.0))
                advanceTimeBy(501)
                pump()
                assertEquals(time, observed.latestSampleMs)
            }
            assertEquals(listOf("A", "B"), observed.topics)
            assertEquals(2L, observed.generation)
        } finally {
            scene.close()
            runCurrent()
        }
        assertEquals(0, frames.subscriptionCount.value)
    }
}
