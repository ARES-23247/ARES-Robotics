package com.ares.analytics.viewmodel.field

import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemVisualAnchor
import com.areslib.subsystem.SubsystemVisualPlacementDocument
import java.io.File

internal data class RobotLightingPlacement(
    val forwardFraction: Double,
    val leftFraction: Double,
    val underbody: Boolean,
)

/** Loads only semantic light placement metadata; malformed/unrelated descriptors are isolated. */
internal fun loadRobotLightingPlacements(projectPath: String?): Map<String, RobotLightingPlacement> {
    val root = projectPath?.takeIf(String::isNotBlank)?.let(::File) ?: return emptyMap()
    val directory = File(root, ".ares/subsystems")
    val documents = directory.listFiles { file -> file.isFile && file.extension.equals("aressubsystem", true) }
        ?: return emptyMap()
    return buildMap {
        documents.sortedBy(File::getName).forEach { file ->
            runCatching { SubsystemDocumentCodec.decode(file.readText()) }.getOrNull()?.let { document ->
                document.hardware.forEach { hardware ->
                    if (hardware.kind != SubsystemHardwareKind.INDICATOR_LIGHT &&
                        hardware.kind != SubsystemHardwareKind.PRISM_DRIVER
                    ) return@forEach
                    val placement = hardware.visualPlacement ?: defaultPlacement(hardware.kind)
                    put(
                        "${document.documentId}/${hardware.hardwareId}",
                        RobotLightingPlacement(
                            forwardFraction = placement.forwardFraction,
                            leftFraction = placement.leftFraction,
                            underbody = placement.anchor == SubsystemVisualAnchor.UNDERBODY,
                        ),
                    )
                }
            }
        }
    }
}

private fun defaultPlacement(kind: SubsystemHardwareKind): SubsystemVisualPlacementDocument =
    if (kind == SubsystemHardwareKind.PRISM_DRIVER) {
        SubsystemVisualPlacementDocument(SubsystemVisualAnchor.UNDERBODY)
    } else {
        SubsystemVisualPlacementDocument(SubsystemVisualAnchor.LEFT_SIDE, leftFraction = 0.5)
    }
