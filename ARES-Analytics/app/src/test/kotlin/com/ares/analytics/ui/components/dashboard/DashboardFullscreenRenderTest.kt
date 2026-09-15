package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.ares.analytics.service.WidgetConfig
import kotlin.test.*

class DashboardFullscreenRenderTest {
    @Test
    fun `expand and restore preserve widget instances and scrolled layout at both responsive widths`() {
        for (width in listOf(600, 1200)) {
            val scene = ImageComposeScene(width, 700)
            val widgets = listOf(
                WidgetConfig("field", "field_viewer", 3, 0, 6, 6),
                WidgetConfig("chart", "telemetry_chart", 9, 6, 6, 6),
            )
            val scroll = ScrollState(240)
            var fullscreen by mutableStateOf<String?>(null)
            var sample by mutableIntStateOf(1)
            val identities = mutableMapOf<String, Any>()
            val observedSamples = mutableMapOf<String, Int>()
            val bounds = mutableMapOf<String, Rect>()
            var created = 0
            var disposed = 0
            val builders: Map<String, @Composable (WidgetConfig, Modifier) -> Unit> =
                widgets.associate { widget ->
                    widget.type to @Composable { config: WidgetConfig, modifier: Modifier ->
                        val instance = remember { Any() }
                        DisposableEffect(Unit) {
                            created++
                            onDispose { disposed++ }
                        }
                        val latestSample = sample
                        SideEffect {
                            identities[config.id] = instance
                            observedSamples[config.id] = latestSample
                        }
                        Box(modifier.background(Color.Cyan).onGloballyPositioned {
                            bounds[config.id] = it.boundsInRoot()
                        })
                    }
                }
            fun render() {
                Snapshot.sendApplyNotifications()
                scene.render().close()
                scene.render().close()
            }
            try {
                scene.setContent {
                    DashboardWidgetGrid(
                        widgets = widgets,
                        isEditing = false,
                        onLayoutChanged = { fail("Fullscreen must not save or rearrange the grid") },
                        onRemoveWidget = { fail("Fullscreen must not remove a widget") },
                        widgetBuilders = builders,
                        fullscreenWidgetId = fullscreen,
                        scrollState = scroll,
                    )
                }
                render()
                val originalBounds = bounds.toMap()
                val originalInstances = identities.toMap()
                val originalScroll = scroll.value
                assertTrue(originalScroll > 0, "Exercise expansion from a scrolled dashboard")

                for (id in listOf("field", "chart", "field")) {
                    fullscreen = id
                    sample++
                    render()
                    assertEquals(Rect(0f, 0f, width.toFloat(), 700f), bounds[id])
                    assertEquals(originalScroll, scroll.value)
                    assertEquals(sample, observedSamples[id], "Expanded card must keep receiving updates")
                    assertEquals(originalInstances, identities)
                    assertEquals(2, created)
                    assertEquals(0, disposed)
                }

                fullscreen = null
                render()
                assertEquals(originalBounds, bounds)
                assertEquals(originalScroll, scroll.value)
                assertEquals(originalInstances, identities)
                assertEquals(0, disposed)
            } finally {
                scene.close()
            }
            assertEquals(2, disposed, "Each retained widget must still clean up on dashboard close")
        }
    }
}
