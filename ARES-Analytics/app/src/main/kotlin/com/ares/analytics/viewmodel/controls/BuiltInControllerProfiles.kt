package com.ares.analytics.viewmodel.controls

import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControllerAnchorDocument
import com.areslib.controls.ControllerAssignment
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.ControllerDeviceMatcherDocument
import com.areslib.controls.ControllerInputMappingDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileDocument
import com.areslib.controls.ControllerSurfaceDocument

internal fun newControlScheme(profileId: String) = ControlSchemeDocument(
    documentId = "competition-controls",
    name = "Competition controls",
    controllers = listOf(
        ControllerAssignment("driver", "Driver", profileId, devicePort = 0),
        ControllerAssignment("operator", "Operator", profileId, devicePort = 1),
    ),
    bindings = emptyList(),
)

internal fun defaultControlEvent(kind: ControlSourceKind) = when (kind) {
    ControlSourceKind.AXIS_VALUE -> ControlEvent.VALUE
    ControlSourceKind.AXIS_ZONE -> ControlEvent.ZONE_ENTER
    else -> ControlEvent.PRESS
}

internal fun controlBindingIdFromPath(path: String, scheme: ControlSchemeDocument): String? {
    val index = Regex("bindings\\[(\\d+)]").find(path)?.groupValues?.get(1)?.toIntOrNull()
    return index?.let { scheme.bindings.getOrNull(it)?.bindingId }
}

internal fun mergeControllerProfiles(
    projectProfiles: List<ControllerProfileDocument>,
): List<ControllerProfileDocument> {
    val projectIds = projectProfiles.mapTo(hashSetOf()) { it.documentId }
    return (projectProfiles + builtInControllerProfiles().filterNot { it.documentId in projectIds })
        .sortedBy { it.displayName.lowercase() }
}

internal fun builtInControllerProfiles(): List<ControllerProfileDocument> = listOf(
    vader5ProProfile(),
    xboxProfile(),
    genericProfile(),
)

private fun genericProfile() = ControllerProfileDocument(
    documentId = "generic-gamepad",
    displayName = "Generic gamepad",
    controls = standardControls(),
)

private fun xboxProfile() = ControllerProfileDocument(
    documentId = "xbox-standard",
    displayName = "Xbox standard controller",
    deviceMatchers = listOf(
        ControllerDeviceMatcherDocument(nameContains = "Xbox"),
        ControllerDeviceMatcherDocument(nameContains = "XInput"),
    ),
    controls = standardControls(),
)

private fun vader5ProProfile() = ControllerProfileDocument(
    documentId = "flydigi-vader-5-pro",
    displayName = "Flydigi Vader 5 Pro",
    deviceMatchers = listOf(ControllerDeviceMatcherDocument(nameContains = "Vader 5 Pro")),
    controls = standardControls() + listOf(
        button("c", "C", .73, .54, ftcIndex = 18),
        button("z", "Z", .87, .54, ftcIndex = 19),
        button("lm", "LM", .23, .08),
        button("rm", "RM", .77, .08),
        button("m1", "M1", .32, .34, ControllerSurfaceDocument.REAR, ftcIndex = 20),
        button("m2", "M2", .68, .34, ControllerSurfaceDocument.REAR, ftcIndex = 21),
        button("m3", "M3", .36, .66, ControllerSurfaceDocument.REAR, ftcIndex = 22),
        button("m4", "M4", .64, .66, ControllerSurfaceDocument.REAR, ftcIndex = 23),
    ),
)

private fun standardControls() = listOf(
    button("a", "A", .81, .44, ftcIndex = 0, frcIndex = 0, desktopIndex = 0),
    button("b", "B", .88, .35, ftcIndex = 1, frcIndex = 1, desktopIndex = 1),
    button("x", "X", .74, .35, ftcIndex = 2, frcIndex = 2, desktopIndex = 2),
    button("y", "Y", .81, .26, ftcIndex = 3, frcIndex = 3, desktopIndex = 3),
    button("left_bumper", "LB", .23, .10, ftcIndex = 4, frcIndex = 4, desktopIndex = 4),
    button("right_bumper", "RB", .77, .10, ftcIndex = 5, frcIndex = 5, desktopIndex = 5),
    button("back", "Back", .43, .40, ftcIndex = 6, frcIndex = 6, desktopIndex = 6),
    button("start", "Start", .57, .40, ftcIndex = 7, frcIndex = 7, desktopIndex = 7),
    button("left_stick_button", "L3", .35, .66, ftcIndex = 9, frcIndex = 8, desktopIndex = 9),
    button("right_stick_button", "R3", .65, .66, ftcIndex = 10, frcIndex = 9, desktopIndex = 10),
    button("dpad_up", "D-pad up", .23, .43, ftcIndex = 11, frcIndex = 120, desktopIndex = 11),
    button("dpad_down", "D-pad down", .23, .57, ftcIndex = 13, frcIndex = 122, desktopIndex = 13),
    button("dpad_left", "D-pad left", .16, .50, ftcIndex = 14, frcIndex = 123, desktopIndex = 14),
    button("dpad_right", "D-pad right", .30, .50, ftcIndex = 12, frcIndex = 121, desktopIndex = 12),
    axis("left_stick_x", "Left stick X", .35, .62, ftcIndex = 0, frcIndex = 0, desktopIndex = 0),
    axis("left_stick_y", "Left stick Y", .35, .72, ftcIndex = 1, frcIndex = 1, desktopIndex = 1),
    axis("right_stick_x", "Right stick X", .65, .62, ftcIndex = 2, frcIndex = 4, desktopIndex = 2),
    axis("right_stick_y", "Right stick Y", .65, .72, ftcIndex = 3, frcIndex = 5, desktopIndex = 3),
    axis("left_trigger", "LT", .17, .02, ftcIndex = 4, frcIndex = 2, desktopIndex = 4),
    axis("right_trigger", "RT", .83, .02, ftcIndex = 5, frcIndex = 3, desktopIndex = 5),
)

private fun button(
    id: String,
    name: String,
    x: Double,
    y: Double,
    surface: ControllerSurfaceDocument = ControllerSurfaceDocument.FRONT,
    ftcIndex: Int? = null,
    frcIndex: Int? = null,
    desktopIndex: Int? = null,
) = ControllerControlDocument(
    id,
    name,
    ControllerControlTypeDocument.BUTTON,
    surface,
    ControllerAnchorDocument(x, y),
    listOfNotNull(
        ftcIndex?.let { ControllerInputMappingDocument(ControllerInputPlatform.FTC, buttonIndex = it) },
        frcIndex?.let { ControllerInputMappingDocument(ControllerInputPlatform.FRC, buttonIndex = it) },
        desktopIndex?.let {
            ControllerInputMappingDocument(ControllerInputPlatform.DESKTOP_GLFW, buttonIndex = it)
        },
    ),
)

private fun axis(
    id: String,
    name: String,
    x: Double,
    y: Double,
    ftcIndex: Int? = null,
    frcIndex: Int? = null,
    desktopIndex: Int? = null,
) = ControllerControlDocument(
    id,
    name,
    ControllerControlTypeDocument.AXIS,
    ControllerSurfaceDocument.FRONT,
    ControllerAnchorDocument(x, y),
    listOfNotNull(
        ftcIndex?.let { ControllerInputMappingDocument(ControllerInputPlatform.FTC, axisIndex = it) },
        frcIndex?.let { ControllerInputMappingDocument(ControllerInputPlatform.FRC, axisIndex = it) },
        desktopIndex?.let {
            ControllerInputMappingDocument(ControllerInputPlatform.DESKTOP_GLFW, axisIndex = it)
        },
    ),
)
