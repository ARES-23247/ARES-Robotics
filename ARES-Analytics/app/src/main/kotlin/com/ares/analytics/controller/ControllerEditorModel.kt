package com.ares.analytics.controller

import com.ares.analytics.service.GamepadState

/** Pure editor state; Compose owns only presentation concerns around this model. */
data class ControllerEditorState(
    val profile: ControllerProfile,
    val surface: ControllerSurface = ControllerSurface.FRONT,
    val selectedControlId: String? = null,
    val learningControlId: String? = null,
    val search: String = ""
) {
    val selectedControl: ControllerControlProfile?
        get() = profile.controls.firstOrNull { it.id == selectedControlId }

    fun select(controlId: String): ControllerEditorState {
        val control = profile.controls.firstOrNull { it.id == controlId } ?: return this
        return copy(selectedControlId = controlId, surface = control.surface, learningControlId = null)
    }

    fun show(surface: ControllerSurface): ControllerEditorState =
        copy(surface = surface, learningControlId = null)

    fun beginLearning(controlId: String): ControllerEditorState =
        if (profile.controls.any { it.id == controlId }) {
            copy(selectedControlId = controlId, learningControlId = controlId)
        } else {
            this
        }

    fun acceptLearnedButton(rawButtonIndex: Int): ControllerEditorState {
        val target = learningControlId ?: return this
        return copy(
            profile = profile.withLearnedButton(target, rawButtonIndex),
            learningControlId = null
        )
    }
}

/** Returns the only newly pressed raw button, or `null` while the observation is ambiguous. */
fun newlyPressedRawButton(previous: GamepadState, current: GamepadState): Int? {
    val newlyPressed = current.rawButtons.indices.filter { index ->
        current.rawButtons[index] && previous.rawButtons.getOrElse(index) { false }.not()
    }
    return newlyPressed.singleOrNull()
}
