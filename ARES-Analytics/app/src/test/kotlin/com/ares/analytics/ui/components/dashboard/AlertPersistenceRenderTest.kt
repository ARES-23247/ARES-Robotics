package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import com.ares.analytics.service.AlertEngineService
import com.ares.analytics.service.AlertPersistenceStatus
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.mockito.Mockito.*
import java.io.File
import kotlin.test.*

class AlertPersistenceRenderTest {
    @Test fun `alert panel renders pending failure stopped and saved states headlessly`() {
        val samples = mapOf(
            "retrying" to AlertPersistenceStatus(2, failed = true),
            "stopped" to AlertPersistenceStatus(2, failed = true, stopped = true),
            "saving" to AlertPersistenceStatus(2),
            "saved" to AlertPersistenceStatus(),
        )
        val output = File("build/diagnostics/alert-persistence-audit").apply { mkdirs() }
        for ((name, status) in samples) {
            val engine = mock(AlertEngineService::class.java)
            `when`(engine.alerts).thenReturn(MutableStateFlow<List<AlertRecord>>(emptyList()))
            `when`(engine.persistenceStatus).thenReturn(MutableStateFlow(status))
            val scene = ImageComposeScene(480, 300)
            try {
                scene.setContent {
                    AresTheme { AlertPanel(engine, Modifier.fillMaxSize().background(AresBackground)) }
                }
                scene.render().use { image ->
                    assertNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                        File(output, "$name.png").writeBytes(data.bytes)
                    }
                }
                assertTrue(File(output, "$name.png").length() > 1_000)
            } finally { scene.close() }
        }
    }
}
