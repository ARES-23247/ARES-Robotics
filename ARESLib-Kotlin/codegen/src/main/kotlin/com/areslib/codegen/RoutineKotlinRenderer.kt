package com.areslib.codegen

import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep

/**
 * Renders canonical routine documents into deterministic generated Kotlin.
 *
 * This renderer owns routine syntax only. Validation and typed project lowering remain in the
 * compiler pipeline, which keeps serialized document semantics out of the project orchestrator.
 */
internal object RoutineKotlinRenderer {
    fun renderRoutine(routine: RoutineDocument, indent: Int): String = buildString {
        append("RoutineDocument(\n")
        appendIndent(indent + 1, "schemaVersion = ${routine.schemaVersion},\n")
        appendIndent(indent + 1, "documentId = ${stringLiteral(routine.documentId)},\n")
        appendIndent(indent + 1, "revision = ${routine.revision},\n")
        appendIndent(indent + 1, "parentContentHash = ${nullableString(routine.parentContentHash)},\n")
        appendIndent(indent + 1, "name = ${stringLiteral(routine.name)},\n")
        appendIndent(indent + 1, "description = ${nullableString(routine.description)},\n")
        appendIndent(indent + 1, "steps = ${renderStepList(routine.steps, indent + 1)},\n")
        appendIndent(indent, ")")
    }

    fun renderAutonomousEntry(entry: AutonomousCatalogEntry, indent: Int): String = buildString {
        append("AutonomousCatalogEntry(\n")
        appendIndent(indent + 1, "entryId = ${stringLiteral(entry.entryId)},\n")
        appendIndent(indent + 1, "displayName = ${stringLiteral(entry.displayName)},\n")
        appendIndent(indent + 1, "description = ${nullableString(entry.description)},\n")
        appendIndent(indent + 1, "routineId = ${stringLiteral(entry.routineId)},\n")
        appendIndent(indent + 1, "startingPose = ${renderPose(entry.startingPose, indent + 1)},\n")
        appendIndent(indent + 1, "authoredAlliance = com.areslib.routine.RoutineAlliance.${entry.authoredAlliance.name},\n")
        appendIndent(indent + 1, "mirrorForOppositeAlliance = ${entry.mirrorForOppositeAlliance},\n")
        appendIndent(indent + 1, "sortOrder = ${entry.sortOrder},\n")
        appendIndent(indent + 1, "enabled = ${entry.enabled},\n")
        appendIndent(indent, ")")
    }

    private fun renderStepList(steps: List<RoutineStep>, indent: Int): String {
        if (steps.isEmpty()) return "emptyList()"
        return buildString {
            append("listOf(\n")
            steps.forEach { step ->
                appendIndent(indent + 1, renderStep(step, indent + 1))
                append(",\n")
            }
            appendIndent(indent, ")")
        }
    }

    private fun renderStep(step: RoutineStep, indent: Int): String = buildString {
        append("RoutineStep(\n")
        appendIndent(indent + 1, "kind = RoutineStepKind.${step.kind.name},\n")
        appendIndent(indent + 1, "stepId = ${stringLiteral(step.stepId)},\n")
        step.actionKey?.let { appendIndent(indent + 1, "actionKey = ${stringLiteral(it)},\n") }
        if (step.arguments.isNotEmpty()) {
            appendIndent(indent + 1, "arguments = ${renderStringMap(step.arguments, indent + 1)},\n")
        }
        step.drive?.let { appendIndent(indent + 1, "drive = ${renderDrive(it, indent + 1)},\n") }
        step.durationSeconds?.let { appendIndent(indent + 1, "durationSeconds = ${doubleLiteral(it)},\n") }
        step.timeoutSeconds?.let { appendIndent(indent + 1, "timeoutSeconds = ${doubleLiteral(it)},\n") }
        step.conditionKey?.let { appendIndent(indent + 1, "conditionKey = ${stringLiteral(it)},\n") }
        step.routineId?.let { appendIndent(indent + 1, "routineId = ${stringLiteral(it)},\n") }
        step.repeatCount?.let { appendIndent(indent + 1, "repeatCount = $it,\n") }
        if (step.children.isNotEmpty()) {
            appendIndent(indent + 1, "children = ${renderStepList(step.children, indent + 1)},\n")
        }
        step.deadline?.let { appendIndent(indent + 1, "deadline = ${renderStep(it, indent + 1)},\n") }
        if (step.elseChildren.isNotEmpty()) {
            appendIndent(indent + 1, "elseChildren = ${renderStepList(step.elseChildren, indent + 1)},\n")
        }
        appendIndent(indent, ")")
    }

    private fun renderDrive(drive: RoutineDriveStep, indent: Int): String = buildString {
        append("RoutineDriveStep(\n")
        appendIndent(indent + 1, "target = ${renderPose(drive.target, indent + 1)},\n")
        appendIndent(indent + 1, "motionPresetKey = ${stringLiteral(drive.motionPresetKey)},\n")
        appendIndent(indent + 1, "preferredEngineKey = ${nullableString(drive.preferredEngineKey)},\n")
        if (drive.markers.isNotEmpty()) {
            appendIndent(indent + 1, "markers = listOf(\n")
            drive.markers.forEach { marker ->
                appendIndent(
                    indent + 2,
                    "RoutineDriveMarker(progress = ${doubleLiteral(marker.progress)}, " +
                        "actionKey = ${stringLiteral(marker.actionKey)}),\n",
                )
            }
            appendIndent(indent + 1, "),\n")
        }
        if (drive.duringActionKeys.isNotEmpty()) {
            appendIndent(indent + 1, "duringActionKeys = ${renderStringList(drive.duringActionKeys, indent + 1)},\n")
        }
        if (drive.arrivalActionKeys.isNotEmpty()) {
            appendIndent(indent + 1, "arrivalActionKeys = ${renderStringList(drive.arrivalActionKeys, indent + 1)},\n")
        }
        appendIndent(indent, ")")
    }

    private fun renderPose(pose: RoutinePose, indent: Int): String = buildString {
        append("RoutinePose(\n")
        appendIndent(indent + 1, "xMeters = ${doubleLiteral(pose.xMeters)},\n")
        appendIndent(indent + 1, "yMeters = ${doubleLiteral(pose.yMeters)},\n")
        appendIndent(indent + 1, "headingRadians = ${doubleLiteral(pose.headingRadians)},\n")
        appendIndent(indent, ")")
    }

    private fun renderStringMap(values: Map<String, String>, indent: Int): String {
        if (values.isEmpty()) return "emptyMap()"
        return buildString {
            append("linkedMapOf(\n")
            values.toSortedMap().forEach { (key, value) ->
                appendIndent(indent + 1, "${stringLiteral(key)} to ${stringLiteral(value)},\n")
            }
            appendIndent(indent, ")")
        }
    }

    private fun renderStringList(values: List<String>, indent: Int): String {
        if (values.isEmpty()) return "emptyList()"
        if (values.size <= 3) return values.joinToString(prefix = "listOf(", postfix = ")") { stringLiteral(it) }
        return buildString {
            append("listOf(\n")
            values.forEach { appendIndent(indent + 1, "${stringLiteral(it)},\n") }
            appendIndent(indent, ")")
        }
    }

    private fun nullableString(value: String?): String = value?.let(::stringLiteral) ?: "null"

    private fun doubleLiteral(value: Double): String {
        return value.kotlinDoubleLiteral()
    }

    private fun stringLiteral(value: String): String = value.kotlinStringLiteral()

    private fun StringBuilder.appendIndent(level: Int, value: String) {
        repeat(level) { append("    ") }
        append(value)
    }
}
