package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.TelemetryStore
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.mockito.Mockito.*
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SubsystemHealthCompositionTest {
    @Test
    fun `card owns one observation and releases it on service replacement and disposal`() = runTest {
        val firstFrames = MutableSharedFlow<TelemetryFrame>(replay = 10)
        val secondFrames = MutableSharedFlow<TelemetryFrame>(replay = 10)
        fun service(frames: MutableSharedFlow<TelemetryFrame>) = mock(Nt4ClientService::class.java).also {
            `when`(it.uiTelemetryFlow).thenReturn(frames)
            `when`(it.telemetryStore).thenReturn(TelemetryStore())
        }
        val selected = mutableStateOf(service(firstFrames))
        val scene = ImageComposeScene(480, 400, coroutineContext = StandardTestDispatcher(testScheduler))
        fun pump() {
            repeat(2) {
                runCurrent()
                scene.render(testScheduler.currentTime * 1_000_000L).close()
            }
            runCurrent()
        }
        try {
            scene.setContent { SubsystemHealthCard(selected.value) }
            pump()
            assertEquals(1, firstFrames.subscriptionCount.value)
            selected.value = service(secondFrames)
            pump()
            assertEquals(0, firstFrames.subscriptionCount.value)
            assertEquals(1, secondFrames.subscriptionCount.value)
        } finally {
            scene.close()
            runCurrent()
        }
        assertEquals(0, firstFrames.subscriptionCount.value)
        assertEquals(0, secondFrames.subscriptionCount.value)
    }
}
