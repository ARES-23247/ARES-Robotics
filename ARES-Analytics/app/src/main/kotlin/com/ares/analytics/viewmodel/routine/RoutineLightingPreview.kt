package com.ares.analytics.viewmodel.routine

import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.hardware.actuator.PrismPwmPreset
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemVisualAnchor
import com.areslib.subsystem.subsystemIndicatorCycleBackwardActionKey
import com.areslib.subsystem.subsystemIndicatorCycleForwardActionKey
import com.areslib.subsystem.subsystemTargetActionKey
import java.io.File

internal data class RoutineIndicatorPreview(
    val position: Double,
    val forwardFraction: Double,
    val leftFraction: Double,
)

internal data class RoutineLightingPreview(
    val indicators: List<RoutineIndicatorPreview> = emptyList(),
    val prismPulseWidthUs: Double? = null,
)

private data class LightingTarget(
    val subsystemId: String,
    val fieldId: String,
    val hardwareId: String,
    val kind: SubsystemHardwareKind,
    val defaultValue: Double,
    val forwardFraction: Double,
    val leftFraction: Double,
)

/** Immutable descriptor-derived model for previewing generated lighting actions without I/O. */
internal class RoutineLightingPreviewModel private constructor(
    private val targets: List<LightingTarget>,
) {
    fun at(actions: List<RoutinePreviewAction>, timeSeconds: Double): RoutineLightingPreview {
        val values = targets.associateWithTo(linkedMapOf()) { it.defaultValue }
        actions.asSequence()
            .filter { it.timeSeconds <= timeSeconds + 1e-9 }
            .sortedBy(RoutinePreviewAction::timeSeconds)
            .forEach { action ->
                targets.forEach { target ->
                    val current = values.getValue(target)
                    values[target] = when (action.actionKey) {
                        subsystemTargetActionKey(target.subsystemId, target.fieldId) ->
                            action.arguments["value"]?.let { target.namedValue(it) } ?: current
                        subsystemIndicatorCycleForwardActionKey(target.subsystemId, target.fieldId) ->
                            target.cycle(current, 1)
                        subsystemIndicatorCycleBackwardActionKey(target.subsystemId, target.fieldId) ->
                            target.cycle(current, -1)
                        else -> current
                    }
                }
            }
        return RoutineLightingPreview(
            indicators = targets.asSequence()
                .filter { it.kind == SubsystemHardwareKind.INDICATOR_LIGHT }
                .sortedBy { "${it.subsystemId}/${it.hardwareId}" }
                .map { target ->
                    RoutineIndicatorPreview(
                        position = values.getValue(target),
                        forwardFraction = target.forwardFraction,
                        leftFraction = target.leftFraction,
                    )
                }
                .toList(),
            prismPulseWidthUs = targets.asSequence()
                .filter { it.kind == SubsystemHardwareKind.PRISM_DRIVER }
                .sortedBy { "${it.subsystemId}/${it.hardwareId}" }
                .firstOrNull()
                ?.let(values::getValue),
        )
    }

    companion object {
        val Empty = RoutineLightingPreviewModel(emptyList())

        fun load(projectPath: String?): RoutineLightingPreviewModel {
            val root = projectPath?.takeIf(String::isNotBlank)?.let(::File) ?: return Empty
            val files = File(root, ".ares/subsystems")
                .listFiles { file -> file.isFile && file.extension.equals("aressubsystem", true) }
                .orEmpty()
            val targets = files.sortedBy(File::getName).flatMap { file ->
                val document = runCatching { SubsystemDocumentCodec.decode(file.readText()) }.getOrNull()
                    ?: return@flatMap emptyList()
                document.stateFields.mapNotNull { field ->
                    val loop = document.controlLoops.firstOrNull { it.targetFieldId == field.fieldId }
                        ?: return@mapNotNull null
                    val hardware = document.hardware.firstOrNull { it.hardwareId == loop.actuatorId }
                        ?: return@mapNotNull null
                    if (hardware.kind != SubsystemHardwareKind.INDICATOR_LIGHT &&
                        hardware.kind != SubsystemHardwareKind.PRISM_DRIVER
                    ) return@mapNotNull null
                    val placement = hardware.visualPlacement
                    LightingTarget(
                        subsystemId = document.documentId,
                        fieldId = field.fieldId,
                        hardwareId = hardware.hardwareId,
                        kind = hardware.kind,
                        defaultValue = field.defaultNumber
                            ?: field.defaultInt?.toDouble()
                            ?: hardware.safeOutput
                            ?: 0.0,
                        forwardFraction = placement?.forwardFraction ?: 0.0,
                        leftFraction = placement?.leftFraction ?: if (
                            placement?.anchor == SubsystemVisualAnchor.RIGHT_SIDE
                        ) -0.5 else 0.5,
                    )
                }
            }
            return RoutineLightingPreviewModel(targets)
        }
    }
}

private fun LightingTarget.namedValue(raw: String): Double? = when (kind) {
    SubsystemHardwareKind.INDICATOR_LIGHT ->
        IndicatorLightColor.entries.firstOrNull { it.name == raw }?.position
    SubsystemHardwareKind.PRISM_DRIVER ->
        PrismPwmPreset.entries.firstOrNull { it.name == raw }?.pulseWidthUs?.toDouble()
    else -> null
} ?: raw.toDoubleOrNull()

private fun LightingTarget.cycle(current: Double, direction: Int): Double {
    if (kind != SubsystemHardwareKind.INDICATOR_LIGHT) return current
    val colors = IndicatorLightColor.entries
        .filter { it != IndicatorLightColor.OFF && it != IndicatorLightColor.RAINBOW }
        .distinctBy(IndicatorLightColor::position)
    if (colors.isEmpty()) return current
    val index = colors.indices.minByOrNull { kotlin.math.abs(colors[it].position - current) } ?: 0
    return colors[Math.floorMod(index + direction, colors.size)].position
}
