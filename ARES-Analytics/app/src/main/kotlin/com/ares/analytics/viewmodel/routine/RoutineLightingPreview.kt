package com.ares.analytics.viewmodel.routine

import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.hardware.actuator.PrismPwmPreset
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemVisualAnchor
import com.areslib.subsystem.isAresGenerated
import com.areslib.subsystem.subsystemIndicatorCycleBackwardActionKey
import com.areslib.subsystem.subsystemIndicatorCycleForwardActionKey
import com.areslib.subsystem.subsystemTargetActionKey
import com.areslib.subsystem.subsystemTargetCapabilities
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

/** Owned snapshots indexed once at compilation; sampling does not replay actions or allocate. */
internal class RoutineLightingTimeline(
    private val initial: RoutineLightingPreview,
    private val times: DoubleArray,
    private val frames: List<RoutineLightingPreview>,
) {
    fun at(timeSeconds: Double): RoutineLightingPreview {
        if (!timeSeconds.isFinite()) return initial
        var low = 0
        var high = times.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (times[middle] <= timeSeconds) low = middle + 1 else high = middle
        }
        return if (low == 0) initial else frames[low - 1]
    }
}

private data class LightingTarget(
    val subsystemId: String,
    val fieldId: String,
    val hardwareId: String,
    val kind: SubsystemHardwareKind,
    val defaultValue: Double,
    val forwardFraction: Double,
    val leftFraction: Double,
    val namedValues: Map<String, Double>,
    val cyclePositions: DoubleArray,
)

private enum class LightingOperation { SET, FORWARD, BACKWARD }
private data class LightingBinding(val targetIndex: Int, val operation: LightingOperation)

/** Immutable descriptor-derived model of generated lighting action targets, without hardware I/O. */
internal class RoutineLightingPreviewModel private constructor(
    private val targets: List<LightingTarget>,
) {
    private val indicatorIndices = targets.indices.filter { targets[it].kind == SubsystemHardwareKind.INDICATOR_LIGHT }.toIntArray()
    private val prismIndex = targets.indexOfFirst { it.kind == SubsystemHardwareKind.PRISM_DRIVER }
    private val bindings = buildMap {
        targets.forEachIndexed { index, target ->
            put(subsystemTargetActionKey(target.subsystemId, target.fieldId), LightingBinding(index, LightingOperation.SET))
            if (target.cyclePositions.isNotEmpty()) {
                put(subsystemIndicatorCycleForwardActionKey(target.subsystemId, target.fieldId), LightingBinding(index, LightingOperation.FORWARD))
                put(subsystemIndicatorCycleBackwardActionKey(target.subsystemId, target.fieldId), LightingBinding(index, LightingOperation.BACKWARD))
            }
        }
    }

    fun compile(actions: List<RoutinePreviewAction>): RoutineLightingTimeline {
        val values = DoubleArray(targets.size) { targets[it].defaultValue }
        fun snapshot() = RoutineLightingPreview(
            indicators = indicatorIndices.map { index ->
                val target = targets[index]
                RoutineIndicatorPreview(values[index], target.forwardFraction, target.leftFraction)
            },
            prismPulseWidthUs = if (prismIndex < 0) null else values[prismIndex],
        )
        val initial = snapshot()
        if (targets.isEmpty()) return RoutineLightingTimeline(initial, DoubleArray(0), emptyList())
        // Kotlin's stable sort retains source order for simultaneous actions.
        val ordered = actions.filter { it.timeSeconds.isFinite() && it.timeSeconds >= 0.0 }
            .sortedBy { if (it.timeSeconds == 0.0) 0.0 else it.timeSeconds }
        val times = mutableListOf<Double>()
        val frames = mutableListOf<RoutineLightingPreview>()
        var cursor = 0
        while (cursor < ordered.size) {
            val time = ordered[cursor].timeSeconds
            var changed = false
            do {
                val action = ordered[cursor++]
                val binding = bindings[action.actionKey] ?: continue
                val target = targets[binding.targetIndex]
                val current = values[binding.targetIndex]
                val next = when (binding.operation) {
                    LightingOperation.SET -> target.namedValues[action.arguments["value"]] ?: current
                    LightingOperation.FORWARD -> target.cycle(current, forward = true)
                    LightingOperation.BACKWARD -> target.cycle(current, forward = false)
                }
                if (next != current) {
                    values[binding.targetIndex] = next
                    changed = true
                }
            } while (cursor < ordered.size && ordered[cursor].timeSeconds == time)
            if (changed) {
                val frame = snapshot()
                if (frame != (frames.lastOrNull() ?: initial)) {
                    times += time
                    frames += frame
                }
            }
        }
        return RoutineLightingTimeline(initial, times.toDoubleArray(), frames)
    }

    companion object {
        val Empty = RoutineLightingPreviewModel(emptyList())

        fun load(projectPath: String?): RoutineLightingPreviewModel {
            val root = projectPath?.takeIf(String::isNotBlank)?.let(::File) ?: return Empty
            val files = File(root, ".ares/subsystems")
                .listFiles { file -> file.isFile && file.extension.equals("aressubsystem", true) }
                .orEmpty()
            val documents = files.sortedBy(File::getName).mapNotNull { file ->
                try { SubsystemDocumentCodec.decode(file.readText()) }
                catch (_: Exception) { null }
            }
            val counts = documents.groupingBy { it.documentId }.eachCount()
            val targets = documents.filter { counts[it.documentId] == 1 && it.implementation.kind.isAresGenerated() }
                .flatMap { document ->
                    val capabilities = subsystemTargetCapabilities(listOf(document)).associateBy { it.descriptor.key }
                    document.stateFields.mapNotNull { field ->
                        val key = subsystemTargetActionKey(document.documentId, field.fieldId)
                        val capability = capabilities[key] ?: return@mapNotNull null
                        val loop = document.controlLoops.firstOrNull { it.targetFieldId == field.fieldId }
                            ?: return@mapNotNull null
                        val hardware = document.hardware.firstOrNull { it.hardwareId == loop.actuatorId }
                            ?: return@mapNotNull null
                        val presets = when (hardware.kind) {
                            SubsystemHardwareKind.INDICATOR_LIGHT -> INDICATOR_VALUES
                            SubsystemHardwareKind.PRISM_DRIVER -> PRISM_VALUES
                            else -> return@mapNotNull null
                        }
                        val options = capability.descriptor.parameters.single().options.toSet()
                        val namedValues = presets.filterKeys { it in options }
                        val cycleKey = subsystemIndicatorCycleForwardActionKey(document.documentId, field.fieldId)
                        val cycles = if (cycleKey in capabilities) IndicatorLightColor.entries.asSequence()
                            .filter { it != IndicatorLightColor.OFF && it != IndicatorLightColor.RAINBOW && it.name in options }
                            .map { it.position }.distinct().toList().toDoubleArray() else DoubleArray(0)
                        val placement = hardware.visualPlacement
                        LightingTarget(
                            subsystemId = document.documentId,
                            fieldId = field.fieldId,
                            hardwareId = hardware.hardwareId,
                            kind = hardware.kind,
                            defaultValue = field.defaultNumber ?: field.defaultInt?.toDouble() ?: hardware.safeOutput ?: 0.0,
                            forwardFraction = placement?.forwardFraction ?: 0.0,
                            leftFraction = placement?.leftFraction ?: if (placement?.anchor == SubsystemVisualAnchor.RIGHT_SIDE) -0.5 else 0.5,
                            namedValues = namedValues,
                            cyclePositions = cycles,
                        )
                    }
                }
                .sortedWith(compareBy(LightingTarget::subsystemId, LightingTarget::hardwareId, LightingTarget::fieldId))
            return RoutineLightingPreviewModel(targets)
        }
    }
}

private val INDICATOR_VALUES = IndicatorLightColor.entries.associate { it.name to it.position }
private val PRISM_VALUES = PrismPwmPreset.entries.associate { it.name to it.pulseWidthUs.toDouble() }

/** Matches generated ordered comparisons, including off, gaps, aliases and field bounds. */
private fun LightingTarget.cycle(current: Double, forward: Boolean): Double {
    if (cyclePositions.isEmpty()) return current
    if (forward) {
        for (position in cyclePositions) if (current < position) return position
        return cyclePositions.first()
    }
    for (index in cyclePositions.indices.reversed()) if (current > cyclePositions[index]) return cyclePositions[index]
    return cyclePositions.last()
}
