@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.ares.analytics.ui.components.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunFilterHeaderTest {
    @Test
    fun `analyst toggle stays readable and clickable when the panel takes space`() = runComposeUiTest {
        setContent {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.width(if (open) 610.dp else 970.dp)) {
                RunFilterHeader(isAiAnalystOpen = open, onToggleAiAnalyst = { open = !open })
            }
        }
        onNodeWithText("AI Run Analyst").performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText("Hide AI Analyst", useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(1, layouts.single().lineCount)
        assertFalse(layouts.single().hasVisualOverflow)
        onNodeWithText("Hide AI Analyst").performClick()
        onNodeWithText("AI Run Analyst").assertIsDisplayed()
    }
}
