package com.ares.analytics.controller

import com.ares.analytics.service.GamepadState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControllerProfileTest {
    @Test
    fun `Vader template includes eight unlabeled raw mappings`() {
        val profile = ControllerProfiles.vader5Pro
        val extras = profile.controls.filter { it.id in setOf("m1", "m2", "m3", "m4", "lm", "rm", "c", "z") }

        assertEquals(8, extras.size)
        assertTrue(extras.all { it.requiresLearning })
        assertTrue(extras.all { it.rawButtonIndex == null && it.rawAxisIndex == null })
        assertEquals(ControllerSurface.REAR, profile.controlFor("M3")?.surface)
        assertEquals("lm", profile.controlFor("left_extra_bumper")?.id)
    }

    @Test
    fun `learned profile maps a physical extra without mutating the template`() {
        val learned = ControllerProfiles.vader5Pro.withLearnedButton("m2", 19)
        val active = GamepadState(rawButtons = List(20) { index -> index == 19 })

        assertEquals(19, learned.controlFor("m2")?.rawButtonIndex)
        assertFalse(learned.controlFor("m2")!!.requiresLearning)
        assertTrue(learned.controlFor("m2")!!.isActive(active))
        assertNull(ControllerProfiles.vader5Pro.controlFor("m2")?.rawButtonIndex)
    }

    @Test
    fun `profile is serializable for project-local persistence`() {
        val learned = ControllerProfiles.vader5Pro.withLearnedButton("c", 16)
        val encoded = Json.encodeToString(learned)
        val decoded = Json.decodeFromString<ControllerProfile>(encoded)

        assertEquals(learned, decoded)
    }

    @Test
    fun `device matching selects Vader profile without assuming exact capitalization`() {
        assertEquals(
            ControllerProfiles.vader5Pro.id,
            ControllerProfiles.forDevice("FLYDIGI Vader 5 Pro Controller").id
        )
        assertEquals(ControllerProfiles.genericGamepad.id, ControllerProfiles.forDevice("Unknown USB Pad").id)
    }

    @Test
    fun `device matching selects the explicit Xbox visual profile`() {
        assertEquals(
            ControllerProfiles.xboxStandard.id,
            ControllerProfiles.forDevice("Xbox Wireless Controller").id,
        )
        assertEquals(
            ControllerProfiles.xboxStandard.id,
            ControllerProfiles.forDevice("XInput Controller #1").id,
        )
    }

    @Test
    fun `learning accepts only a single new raw button`() {
        val previous = GamepadState(rawButtons = listOf(false, false, true))
        assertEquals(1, newlyPressedRawButton(previous, GamepadState(rawButtons = listOf(false, true, true))))
        assertNull(newlyPressedRawButton(previous, GamepadState(rawButtons = listOf(true, true, true))))

        val editor = ControllerEditorState(ControllerProfiles.vader5Pro).beginLearning("m1")
            .acceptLearnedButton(17)
        assertEquals(17, editor.profile.controlFor("m1")?.rawButtonIndex)
        assertNull(editor.learningControlId)
    }
}
