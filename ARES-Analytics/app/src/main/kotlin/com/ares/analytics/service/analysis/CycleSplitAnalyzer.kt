package com.ares.analytics.service.analysis

import com.ares.analytics.shared.models.RobotActionRecord
import com.ares.analytics.shared.models.TelemetryFrame
import kotlin.math.ceil
import kotlin.math.floor

data class PhaseSplitInstance(
    val sourceTopic: String,
    val stateName: String,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class PhaseSplitAggregate(
    val sourceTopic: String,
    val stateName: String,
    val count: Int,
    val meanDurationMs: Double,
    val p50DurationMs: Double,
    val p95DurationMs: Double,
    val bestDurationMs: Double,
    /** Primary minus Compare mean duration (`< 0` means Primary was faster). */
    val compareDeltaMeanMs: Double? = null,
)

data class CompletedCycleLap(
    val cycleIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val visitedStates: List<String>,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class CycleCadenceSummary(
    val cycleCount: Int,
    val meanMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val bestMs: Double,
    val compareDeltaP50Ms: Double? = null,
)

data class CycleSplitReport(
    val selectedTopic: String?,
    val availableStateTopics: List<String>,
    val instances: List<PhaseSplitInstance>,
    val phaseSplits: List<PhaseSplitAggregate>,
    val completedCycles: List<CompletedCycleLap>,
    val cycleSummary: CycleCadenceSummary?,
)

/**
 * Segments Autonomous routine step progressions and TeleOp Superstructure state-machine cycles
 * (`Intake -> Travel -> Score`) into statistical split distributions (`p50`, `p95`, `best`) and A/B run deltas.
 */
object CycleSplitAnalyzer {

    private val TERMINAL_OR_IDLE_STATES = setOf(
        "IDLE", "COMPLETE", "COMPLETED", "STOPPED", "DISABLED", "NONE", "STOW", "STOWED", "PARKED", "READY",
    )

    fun isStateTopic(key: String): Boolean {
        val clean = key.trim().trimStart('/')
        return clean.endsWith("/State", ignoreCase = true) ||
            clean.endsWith("/CurrentState", ignoreCase = true) ||
            clean.endsWith("/CurrentStep", ignoreCase = true) ||
            clean.endsWith("/Phase", ignoreCase = true) ||
            clean.startsWith("Routine/", ignoreCase = true) ||
            clean.startsWith("Autonomous/", ignoreCase = true) ||
            clean.startsWith("Superstructure/", ignoreCase = true)
    }

    fun analyze(
        primaryFrames: List<TelemetryFrame>,
        compareFrames: List<TelemetryFrame> = emptyList(),
        primaryActions: List<RobotActionRecord> = emptyList(),
        preferredTopic: String? = null,
    ): CycleSplitReport {
        val primaryStateFrames = extractStateTransitions(primaryFrames, primaryActions)
        if (primaryStateFrames.isEmpty()) {
            return CycleSplitReport(
                selectedTopic = null,
                availableStateTopics = emptyList(),
                instances = emptyList(),
                phaseSplits = emptyList(),
                completedCycles = emptyList(),
                cycleSummary = null,
            )
        }

        val topicsByRichness = primaryStateFrames
            .groupBy { it.first }
            .entries
            .sortedByDescending { it.value.size }
            .map { it.key }
        val activeTopic = preferredTopic?.takeIf { it in topicsByRichness } ?: topicsByRichness.first()

        val primaryInstances = buildInstancesForTopic(
            activeTopic,
            primaryStateFrames.filter { it.first == activeTopic }.map { it.second },
        )
        val compareStateFrames = extractStateTransitions(compareFrames, emptyList())
        val compareInstances = buildInstancesForTopic(
            activeTopic,
            compareStateFrames.filter { it.first == activeTopic }.map { it.second },
        )

        val compareMeanByState = compareInstances
            .groupBy { it.stateName }
            .mapValues { (_, list) -> list.map { it.durationMs.toDouble() }.average() }

        // Preserve first-appearance order so Autonomous step sequences read chronologically
        val stateOrder = LinkedHashSet<String>()
        primaryInstances.forEach { if (!isIdleOrTerminal(it.stateName)) stateOrder += it.stateName }
        if (stateOrder.isEmpty()) {
            primaryInstances.forEach { stateOrder += it.stateName }
        }

        val aggregates = stateOrder.mapNotNull { stateName ->
            val durations = primaryInstances
                .filter { it.stateName == stateName && it.durationMs > 0L }
                .map { it.durationMs.toDouble() }
                .sorted()
            if (durations.isEmpty()) return@mapNotNull null
            val mean = durations.average()
            val compareMean = compareMeanByState[stateName]
            PhaseSplitAggregate(
                sourceTopic = activeTopic,
                stateName = stateName,
                count = durations.size,
                meanDurationMs = mean,
                p50DurationMs = percentile(durations, 0.50),
                p95DurationMs = percentile(durations, 0.95),
                bestDurationMs = durations.first(),
                compareDeltaMeanMs = compareMean?.let { mean - it },
            )
        }

        val primaryCycles = detectScoringCycles(primaryInstances)
        val compareCycles = detectScoringCycles(compareInstances)
        val cycleSummary = summarizeCycles(primaryCycles, compareCycles)

        return CycleSplitReport(
            selectedTopic = activeTopic,
            availableStateTopics = topicsByRichness,
            instances = primaryInstances,
            phaseSplits = aggregates,
            completedCycles = primaryCycles,
            cycleSummary = cycleSummary,
        )
    }

    private data class StateSample(val timestampMs: Long, val state: String)

    private fun extractStateTransitions(
        frames: List<TelemetryFrame>,
        actions: List<RobotActionRecord>,
    ): List<Pair<String, StateSample>> {
        val extracted = ArrayList<Pair<String, StateSample>>()
        for (frame in frames) {
            val stateText = frame.stringValue?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val cleanKey = frame.key.trim().trimStart('/')
            if (isStateTopic(cleanKey)) {
                extracted += cleanKey to StateSample(frame.timestampMs, stateText)
            }
        }
        if (extracted.isEmpty() && actions.isNotEmpty()) {
            for (action in actions) {
                val label = action.actionType.trim().takeIf { it.isNotEmpty() } ?: continue
                extracted += "Actions/RobotAction" to StateSample(action.timestampMs, label)
            }
        }
        return extracted.sortedBy { it.second.timestampMs }
    }

    private fun buildInstancesForTopic(
        topic: String,
        samples: List<StateSample>,
    ): List<PhaseSplitInstance> {
        if (samples.size < 2) return emptyList()
        val deduped = ArrayList<StateSample>(samples.size)
        for (sample in samples) {
            if (deduped.isEmpty() || deduped.last().state != sample.state) {
                deduped += sample
            }
        }
        if (deduped.size < 2) {
            val first = samples.first()
            val last = samples.last()
            return if (last.timestampMs > first.timestampMs) {
                listOf(PhaseSplitInstance(topic, first.state, first.timestampMs, last.timestampMs))
            } else {
                emptyList()
            }
        }
        val instances = ArrayList<PhaseSplitInstance>(deduped.size - 1)
        for (i in 0 until deduped.size - 1) {
            val cur = deduped[i]
            val next = deduped[i + 1]
            if (next.timestampMs > cur.timestampMs) {
                instances += PhaseSplitInstance(topic, cur.state, cur.timestampMs, next.timestampMs)
            }
        }
        // Include trailing duration if more samples exist in the final state
        val finalTransition = deduped.last()
        val lastSample = samples.last()
        if (lastSample.timestampMs > finalTransition.timestampMs) {
            instances += PhaseSplitInstance(
                topic,
                finalTransition.state,
                finalTransition.timestampMs,
                lastSample.timestampMs,
            )
        }
        return instances
    }

    /**
     * Identifies full `Intake/Acquire -> ... -> Score/Shoot/Deposit` cycles. Also falls back to
     * non-idle active excursions (`IDLE -> active states -> IDLE`) when explicit Intake/Score names differ.
     */
    private fun detectScoringCycles(instances: List<PhaseSplitInstance>): List<CompletedCycleLap> {
        if (instances.isEmpty()) return emptyList()
        val cycles = mutableListOf<CompletedCycleLap>()
        var cycleStartMs: Long? = null
        var sawScoreInCycle = false
        val visited = mutableListOf<String>()

        for (inst in instances) {
            val upper = inst.stateName.uppercase()
            val isIntake = upper.contains("INTAKE") || upper.contains("INTAKING") || upper.contains("ACQUIRE") || upper.contains("COLLECT")
            val isScore = upper.contains("SCORE") || upper.contains("SCORING") || upper.contains("SHOOT") || upper.contains("DEPOSIT") || upper.contains("PLACE")
            val isIdle = isIdleOrTerminal(upper)

            if (isIntake && (cycleStartMs == null || sawScoreInCycle)) {
                if (cycleStartMs != null && sawScoreInCycle) {
                    cycles += CompletedCycleLap(cycles.size + 1, cycleStartMs, inst.startMs, visited.toList())
                }
                cycleStartMs = inst.startMs
                sawScoreInCycle = false
                visited.clear()
                visited += inst.stateName
            } else if (cycleStartMs != null) {
                if (!isIdle) visited += inst.stateName
                if (isScore) {
                    sawScoreInCycle = true
                } else if (isIdle && sawScoreInCycle) {
                    cycles += CompletedCycleLap(cycles.size + 1, cycleStartMs, inst.startMs, visited.toList())
                    cycleStartMs = null
                    sawScoreInCycle = false
                    visited.clear()
                }
            }
        }
        if (cycleStartMs != null && sawScoreInCycle) {
            val lastScore = instances.lastOrNull {
                val u = it.stateName.uppercase()
                u.contains("SCORE") || u.contains("SCORING") || u.contains("SHOOT") || u.contains("DEPOSIT") || u.contains("PLACE")
            }
            if (lastScore != null && lastScore.endMs > cycleStartMs) {
                cycles += CompletedCycleLap(cycles.size + 1, cycleStartMs, lastScore.endMs, visited.toList())
            }
        }
        return cycles
    }

    private fun summarizeCycles(
        primary: List<CompletedCycleLap>,
        compare: List<CompletedCycleLap>,
    ): CycleCadenceSummary? {
        if (primary.isEmpty()) return null
        val durations = primary.map { it.durationMs.toDouble() }.sorted()
        val p50 = percentile(durations, 0.50)
        val compareP50 = compare.takeIf { it.isNotEmpty() }
            ?.map { it.durationMs.toDouble() }
            ?.sorted()
            ?.let { percentile(it, 0.50) }
        return CycleCadenceSummary(
            cycleCount = durations.size,
            meanMs = durations.average(),
            p50Ms = p50,
            p95Ms = percentile(durations, 0.95),
            bestMs = durations.first(),
            compareDeltaP50Ms = compareP50?.let { p50 - it },
        )
    }

    private fun isIdleOrTerminal(stateName: String): Boolean =
        stateName.trim().uppercase() in TERMINAL_OR_IDLE_STATES

    internal fun percentile(sortedValues: List<Double>, fraction: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        if (sortedValues.size == 1) return sortedValues[0]
        val pos = fraction.coerceIn(0.0, 1.0) * (sortedValues.size - 1)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        val alpha = pos - lo
        return sortedValues[lo] + alpha * (sortedValues[hi] - sortedValues[lo])
    }
}
