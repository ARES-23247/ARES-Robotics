package com.ares.analytics.ui.components.routine

import kotlin.test.Test
import kotlin.test.assertEquals

class RoutineBuilderResponsiveLayoutTest {
    @Test
    fun `wide workspace keeps header actions inline and both authoring panes visible`() {
        assertEquals(
            RoutineBuilderLayoutPresentation(stackHeaderActions = false, useTabbedBody = false),
            routineBuilderLayoutPresentation(1_400f, largeText = false),
        )
    }

    @Test
    fun `medium workspace stacks header actions without hiding the field`() {
        assertEquals(
            RoutineBuilderLayoutPresentation(stackHeaderActions = true, useTabbedBody = false),
            routineBuilderLayoutPresentation(1_100f, largeText = false),
        )
    }

    @Test
    fun `compact workspace switches the editor and field to tabs`() {
        assertEquals(
            RoutineBuilderLayoutPresentation(stackHeaderActions = true, useTabbedBody = true),
            routineBuilderLayoutPresentation(900f, largeText = false),
        )
        assertEquals(
            RoutineBuilderLayoutPresentation(stackHeaderActions = true, useTabbedBody = true),
            routineBuilderLayoutPresentation(1_100f, largeText = true),
        )
    }
}
