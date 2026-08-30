package com.ares.analytics.controller

import com.ares.analytics.service.GamepadState
import kotlinx.serialization.Serializable

/** The physical surface on which a control is shown in the editor. */
@Serializable
enum class ControllerSurface { FRONT, REAR }

/** How a physical control reports its value to the operating system. */
@Serializable
enum class ControllerControlType { BUTTON, AXIS, STICK, DPAD }

/** A normalized location within a controller diagram. Both coordinates are in `[0, 1]`. */
@Serializable
data class ControllerAnchor(val x: Float, val y: Float)

/**
 * Describes one physical controller control without coupling its label to a guessed HID index.
 *
 * A raw index is deliberately nullable. Controllers such as the Vader 5 Pro can expose their
 * extra controls differently depending on firmware and connection mode, so the editor learns
 * those indices from live input before generating a binding.
 */
@Serializable
data class ControllerControlProfile(
    val id: String,
    val label: String,
    val type: ControllerControlType,
    val surface: ControllerSurface = ControllerSurface.FRONT,
    val anchor: ControllerAnchor,
    val rawButtonIndex: Int? = null,
    val rawAxisIndex: Int? = null,
    val aliases: Set<String> = emptySet(),
    val requiresLearning: Boolean = rawButtonIndex == null && rawAxisIndex == null
) {
    init {
        require(id.isNotBlank()) { "Controller control id cannot be blank" }
        require(anchor.x in 0f..1f && anchor.y in 0f..1f) {
            "Controller anchor must use normalized coordinates"
        }
        require(rawButtonIndex == null || rawButtonIndex >= 0) { "Raw button index cannot be negative" }
        require(rawAxisIndex == null || rawAxisIndex >= 0) { "Raw axis index cannot be negative" }
    }

    fun matches(bindingName: String): Boolean {
        val normalized = normalizeControlName(bindingName)
        return normalized == normalizeControlName(id) ||
            normalized == normalizeControlName(label) ||
            aliases.any { normalized == normalizeControlName(it) }
    }

    fun isActive(state: GamepadState, axisThreshold: Float = 0.55f): Boolean = when {
        rawButtonIndex != null -> state.rawButtons.getOrNull(rawButtonIndex) == true
        rawAxisIndex != null -> kotlin.math.abs(state.rawAxes.getOrElse(rawAxisIndex) { 0f }) >= axisThreshold
        else -> state.standardControlActive(id, axisThreshold)
    }
}

/** Serializable, project-local representation of a physical controller and its diagram. */
@Serializable
data class ControllerProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val displayName: String,
    val deviceNameMatchers: List<String> = emptyList(),
    val controls: List<ControllerControlProfile>
) {
    init {
        require(schemaVersion > 0) { "Controller profile schema version must be positive" }
        require(controls.map { it.id }.distinct().size == controls.size) {
            "Controller profile control ids must be unique"
        }
    }

    fun controlFor(bindingName: String): ControllerControlProfile? =
        controls.firstOrNull { it.matches(bindingName) }

    fun withLearnedButton(controlId: String, rawButtonIndex: Int): ControllerProfile = copy(
        controls = controls.map { control ->
            if (control.id == controlId) {
                control.copy(rawButtonIndex = rawButtonIndex, rawAxisIndex = null, requiresLearning = false)
            } else {
                control
            }
        }
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** Built-in profiles are visual templates; learned HID mappings belong to the project copy. */
object ControllerProfiles {
    val genericGamepad: ControllerProfile = ControllerProfile(
        id = "generic-gamepad",
        displayName = "Generic gamepad",
        controls = standardFrontControls()
    )

    val xboxStandard: ControllerProfile = ControllerProfile(
        id = "xbox-standard",
        displayName = "Xbox standard controller",
        deviceNameMatchers = listOf("xbox", "xinput"),
        controls = standardFrontControls()
    )

    /**
     * Vader 5 Pro visual template.
     *
     * M1-M4, LM/RM, and C/Z intentionally have no raw indices. The profile wizard records what
     * the connected controller actually reports, including duplicate/remapped inputs.
     */
    val vader5Pro: ControllerProfile = ControllerProfile(
        id = "flydigi-vader-5-pro",
        displayName = "Flydigi Vader 5 Pro",
        deviceNameMatchers = listOf("vader 5 pro", "flydigi vader5 pro", "flydigi vader 5 pro"),
        controls = standardFrontControls() + listOf(
            control("c", "C", ControllerControlType.BUTTON, .73f, .54f, aliases = setOf("face_c"), requiresLearning = true),
            control("z", "Z", ControllerControlType.BUTTON, .87f, .54f, aliases = setOf("face_z"), requiresLearning = true),
            control("lm", "LM", ControllerControlType.BUTTON, .23f, .08f, aliases = setOf("left_extra_bumper"), requiresLearning = true),
            control("rm", "RM", ControllerControlType.BUTTON, .77f, .08f, aliases = setOf("right_extra_bumper"), requiresLearning = true),
            control("m1", "M1", ControllerControlType.BUTTON, .32f, .34f, ControllerSurface.REAR, requiresLearning = true),
            control("m2", "M2", ControllerControlType.BUTTON, .68f, .34f, ControllerSurface.REAR, requiresLearning = true),
            control("m3", "M3", ControllerControlType.BUTTON, .36f, .66f, ControllerSurface.REAR, requiresLearning = true),
            control("m4", "M4", ControllerControlType.BUTTON, .64f, .66f, ControllerSurface.REAR, requiresLearning = true)
        )
    )

    val builtIns: List<ControllerProfile> = listOf(vader5Pro, xboxStandard, genericGamepad)

    fun forDevice(deviceName: String): ControllerProfile = builtIns.firstOrNull { profile ->
        profile.deviceNameMatchers.any { matcher -> deviceName.contains(matcher, ignoreCase = true) }
    } ?: genericGamepad

    private fun standardFrontControls(): List<ControllerControlProfile> = listOf(
        control("a", "A", ControllerControlType.BUTTON, .81f, .44f, aliases = setOf("cross")),
        control("b", "B", ControllerControlType.BUTTON, .88f, .35f, aliases = setOf("circle")),
        control("x", "X", ControllerControlType.BUTTON, .74f, .35f, aliases = setOf("square")),
        control("y", "Y", ControllerControlType.BUTTON, .81f, .26f, aliases = setOf("triangle")),
        control("dpad_up", "D-pad up", ControllerControlType.DPAD, .23f, .43f, aliases = setOf("dpadup")),
        control("dpad_down", "D-pad down", ControllerControlType.DPAD, .23f, .57f, aliases = setOf("dpaddown")),
        control("dpad_left", "D-pad left", ControllerControlType.DPAD, .16f, .50f, aliases = setOf("dpadleft")),
        control("dpad_right", "D-pad right", ControllerControlType.DPAD, .30f, .50f, aliases = setOf("dpadright")),
        control("left_bumper", "LB", ControllerControlType.BUTTON, .23f, .10f, aliases = setOf("leftbumper", "lb")),
        control("right_bumper", "RB", ControllerControlType.BUTTON, .77f, .10f, aliases = setOf("rightbumper", "rb")),
        control("left_trigger", "LT", ControllerControlType.AXIS, .17f, .02f, aliases = setOf("lefttrigger", "lt")),
        control("right_trigger", "RT", ControllerControlType.AXIS, .83f, .02f, aliases = setOf("righttrigger", "rt")),
        control("left_stick", "Left stick", ControllerControlType.STICK, .35f, .66f, aliases = setOf("leftstick", "left_stick_button")),
        control("right_stick", "Right stick", ControllerControlType.STICK, .65f, .66f, aliases = setOf("rightstick", "right_stick_button")),
        control("back", "Back", ControllerControlType.BUTTON, .43f, .40f, aliases = setOf("share")),
        control("start", "Start", ControllerControlType.BUTTON, .57f, .40f, aliases = setOf("options"))
    )

    private fun control(
        id: String,
        label: String,
        type: ControllerControlType,
        x: Float,
        y: Float,
        surface: ControllerSurface = ControllerSurface.FRONT,
        aliases: Set<String> = emptySet(),
        requiresLearning: Boolean = false
    ) = ControllerControlProfile(
        id = id,
        label = label,
        type = type,
        surface = surface,
        anchor = ControllerAnchor(x, y),
        aliases = aliases,
        requiresLearning = requiresLearning
    )
}

internal fun normalizeControlName(value: String): String =
    value.lowercase().filter { it.isLetterOrDigit() }

private fun GamepadState.standardControlActive(controlId: String, axisThreshold: Float): Boolean = when (controlId) {
    "a" -> a
    "b" -> b
    "x" -> x
    "y" -> y
    "left_bumper" -> leftBumper
    "right_bumper" -> rightBumper
    "dpad_up" -> dpadUp
    "dpad_down" -> dpadDown
    "dpad_left" -> dpadLeft
    "dpad_right" -> dpadRight
    "left_trigger" -> leftTrigger >= axisThreshold
    "right_trigger" -> rightTrigger >= axisThreshold
    "left_stick" -> kotlin.math.abs(leftStickX) >= axisThreshold || kotlin.math.abs(leftStickY) >= axisThreshold
    "right_stick" -> kotlin.math.abs(rightStickX) >= axisThreshold || kotlin.math.abs(rightStickY) >= axisThreshold
    else -> false
}
