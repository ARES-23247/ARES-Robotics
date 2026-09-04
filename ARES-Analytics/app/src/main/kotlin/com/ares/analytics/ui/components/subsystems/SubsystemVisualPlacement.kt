package com.ares.analytics.ui.components.subsystems

import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemVisualAnchor
import com.areslib.subsystem.SubsystemVisualPlacementDocument

internal fun defaultVisualPlacement(kind: SubsystemHardwareKind): SubsystemVisualPlacementDocument =
    placementForAnchor(
        if (kind == SubsystemHardwareKind.PRISM_DRIVER) SubsystemVisualAnchor.UNDERBODY
        else SubsystemVisualAnchor.LEFT_SIDE,
    )

internal fun placementForAnchor(anchor: SubsystemVisualAnchor): SubsystemVisualPlacementDocument = when (anchor) {
    SubsystemVisualAnchor.LEFT_SIDE -> SubsystemVisualPlacementDocument(anchor, leftFraction = 0.5)
    SubsystemVisualAnchor.RIGHT_SIDE -> SubsystemVisualPlacementDocument(anchor, leftFraction = -0.5)
    SubsystemVisualAnchor.FRONT -> SubsystemVisualPlacementDocument(anchor, forwardFraction = 0.5)
    SubsystemVisualAnchor.REAR -> SubsystemVisualPlacementDocument(anchor, forwardFraction = -0.5)
    SubsystemVisualAnchor.CENTER,
    SubsystemVisualAnchor.UNDERBODY,
    SubsystemVisualAnchor.UNSPECIFIED -> SubsystemVisualPlacementDocument(anchor)
}
