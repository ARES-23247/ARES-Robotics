package com.ares.analytics.viewmodel.controls

import com.ares.analytics.service.GamepadState
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.ControllerInputMappingDocument
import com.areslib.controls.ControllerInputPlatform
import kotlin.math.abs

internal object ControlsHardwareBindingCoordinator {
    fun beginDesktopLearning(current: ControlsEditorState, state: GamepadState): ControlsEditorState {
        val control = current.selectedControl ?: return current
        if (!state.connected) return current.copy(status = "Connect the controller to learn its desktop input.")
        return current.copy(
            learning = ControlLearningSession(control.controlId, state.rawButtons, state.rawAxes),
            status = "Move or press ${control.displayName}; only the Desktop GLFW mapping will change."
        )
    }

    fun observeDesktopInput(current: ControlsEditorState, state: GamepadState): ControlsEditorState {
        val learning = current.learning ?: return current
        if (!state.connected) return current
        val control = current.selectedProfile?.controls?.firstOrNull { it.controlId == learning.controlId }
            ?: return current
        val learnedIndex: Int? = when (control.type) {
            ControllerControlTypeDocument.BUTTON -> {
                val newButtons = state.rawButtons.indices.filter { index ->
                    state.rawButtons[index] && !learning.baselineButtons.getOrElse(index) { false }
                }
                if (newButtons.size == 1) newButtons.single() else null
            }
            ControllerControlTypeDocument.AXIS -> {
                val movedAxes = state.rawAxes.indices.filter { index ->
                    abs(state.rawAxes[index] - learning.baselineAxes.getOrElse(index) { 0f }) >= .35f
                }
                if (movedAxes.size == 1) movedAxes.single() else null
            }
        }
        return if (learnedIndex != null) {
            setMapping(current, learning.controlId, ControllerInputPlatform.DESKTOP_GLFW, learnedIndex)
        } else {
            current
        }
    }

    fun setMapping(
        current: ControlsEditorState,
        controlId: String,
        platform: ControllerInputPlatform,
        index: Int?,
    ): ControlsEditorState {
        if (index != null && index < 0) return current.copy(status = "Input indexes cannot be negative.")
        val profile = current.selectedProfile ?: return current
        val control = profile.controls.firstOrNull { it.controlId == controlId } ?: return current
        val mapping = index?.let {
            ControllerInputMappingDocument(
                platform = platform,
                buttonIndex = it.takeIf { control.type == ControllerControlTypeDocument.BUTTON },
                axisIndex = it.takeIf { control.type == ControllerControlTypeDocument.AXIS }
            )
        }
        val updated = profile.copy(controls = profile.controls.map { candidate ->
            if (candidate.controlId != controlId) candidate else candidate.copy(
                mappings = candidate.mappings.filterNot { it.platform == platform } + listOfNotNull(mapping)
            )
        })
        return current.copy(
            profiles = current.profiles.map { if (it.documentId == updated.documentId) updated else it },
            dirty = true,
            dirtyProfileIds = current.dirtyProfileIds + updated.documentId,
            learning = null,
            status = when (platform) {
                ControllerInputPlatform.DESKTOP_GLFW -> "Learned Desktop GLFW index $index. FTC/FRC mappings were not changed."
                else -> "Set ${platform.name} index $index. Verify it on that platform before competition."
            }
        ).revalidated()
    }
}
