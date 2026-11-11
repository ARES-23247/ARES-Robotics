package com.areslib.routine

import kotlin.math.round

/**
 * Renders this canonical [RoutineDocument] into idiomatic, copy-pasteable Kotlin Text DSL code
 * ([routine]).
 *
 * Emits clean step calls (`driveTo`, `action`, `waitSeconds`, `waitUntil`, `parallel`, `race`,
 * `deadline`, `call`, `repeatTimes`, `branch`) with degrees formatted cleanly at the UI boundary.
 */
fun RoutineDocument.toKotlinDsl(): String = buildString {
    val defaultName = defaultRoutineDisplayName(documentId)
    val headerArgs = buildList {
        add(documentId.quote())
        if (name != defaultName && name.isNotBlank()) add("name = ${name.quote()}")
        if (revision != 1) add("revision = $revision")
        if (!description.isNullOrBlank()) add("description = ${description.orEmpty().quote()}")
    }
    appendLine("routine(${headerArgs.joinToString(", ")}) {")
    steps.forEach { step ->
        renderRoutineStep(this, step, indent = "    ")
    }
    append("}")
    parentContentHash?.let { append(".copy(parentContentHash = ${it.quote()})") }
}

private fun renderRoutineStep(out: StringBuilder, step: RoutineStep, indent: String) {
    out.appendLine("${indent}identified(${step.stepId.quote()}) {")
    renderRoutineStepBody(out, step, "$indent    ")
    out.appendLine("$indent}")
}

private fun renderRoutineStepBody(out: StringBuilder, step: RoutineStep, indent: String) {
    when (step.kind) {
        RoutineStepKind.ACTION -> {
            val key = (step.actionKey ?: "").quote()
            if (step.arguments.isEmpty()) {
                out.appendLine("${indent}action($key)")
            } else {
                out.appendLine("${indent}action($key) {")
                renderArguments(out, step.arguments, "$indent    ")
                out.appendLine("$indent}")
            }
        }
        RoutineStepKind.DRIVE_TO -> {
            val drive = step.drive ?: return
            val deg = round(Math.toDegrees(drive.target.headingRadians) * 100.0) / 100.0
            val engine = drive.preferredEngineKey
            val hasExtras = drive.motionPresetKey != "balanced" ||
                engine != null ||
                drive.markers.isNotEmpty() ||
                drive.duringActionKeys.isNotEmpty() ||
                drive.arrivalActionKeys.isNotEmpty() ||
                drive.headingMode != RoutineHeadingMode.LINEAR ||
                drive.lookAtPoint != null ||
                drive.blendHandoff
            val poseArgs = "xMeters = ${drive.target.xMeters}, yMeters = ${drive.target.yMeters}, headingDegrees = $deg"
            if (!hasExtras) {
                out.appendLine("${indent}driveTo($poseArgs)")
            } else {
                out.appendLine("${indent}driveTo($poseArgs) {")
                if (drive.motionPresetKey != "balanced") {
                    out.appendLine("$indent    motionPreset(${drive.motionPresetKey.quote()})")
                }
                if (engine != null) {
                    out.appendLine("$indent    preferEngine(${engine.quote()})")
                }
                if (drive.headingMode == RoutineHeadingMode.POINT_TOWARDS && drive.lookAtPoint != null) {
                    out.appendLine("$indent    pointTowards(${drive.lookAtPoint!!.xMeters}, ${drive.lookAtPoint!!.yMeters})")
                } else if (drive.headingMode != RoutineHeadingMode.LINEAR) {
                    out.appendLine("$indent    headingMode(RoutineHeadingMode.${drive.headingMode.name})")
                }
                if (drive.blendHandoff) {
                    out.appendLine("$indent    blendIntoNext(true)")
                }
                drive.duringActionKeys.forEach { key ->
                    out.appendLine("$indent    whileDriving(${key.quote()})")
                }
                drive.markers.forEach { marker ->
                    out.appendLine("$indent    atProgress(${marker.progress}, ${marker.actionKey.quote()})")
                }
                drive.arrivalActionKeys.forEach { key ->
                    out.appendLine("$indent    onArrival(${key.quote()})")
                }
                out.appendLine("$indent}")
            }
        }
        RoutineStepKind.WAIT -> {
            out.appendLine("${indent}waitSeconds(${step.durationSeconds ?: 0.0})")
        }
        RoutineStepKind.WAIT_UNTIL -> {
            val cond = (step.conditionKey ?: "").quote()
            val timeout = step.timeoutSeconds ?: 1.0
            if (step.arguments.isEmpty()) {
                out.appendLine("${indent}waitUntil(condition = $cond, timeoutSeconds = $timeout)")
            } else {
                out.appendLine("${indent}waitUntil(condition = $cond, timeoutSeconds = $timeout) {")
                renderArguments(out, step.arguments, "$indent    ")
                out.appendLine("$indent}")
            }
        }
        RoutineStepKind.TOGETHER -> {
            out.appendLine("${indent}parallel {")
            step.children.forEach { renderRoutineStep(out, it, "$indent    ") }
            out.appendLine("$indent}")
        }
        RoutineStepKind.FIRST_TO_FINISH -> {
            out.appendLine("${indent}race {")
            step.children.forEach { renderRoutineStep(out, it, "$indent    ") }
            out.appendLine("$indent}")
        }
        RoutineStepKind.DEADLINE -> {
            out.appendLine("${indent}deadline(")
            out.appendLine("$indent    main = {")
            step.deadline?.let { renderRoutineStep(out, it, "$indent        ") }
            out.appendLine("$indent    },")
            out.appendLine("$indent    companions = {")
            step.children.forEach { renderRoutineStep(out, it, "$indent        ") }
            out.appendLine("$indent    },")
            out.appendLine("$indent)")
        }
        RoutineStepKind.CALL -> {
            out.appendLine("${indent}call(${(step.routineId ?: "").quote()})")
        }
        RoutineStepKind.REPEAT -> {
            out.appendLine("${indent}repeatTimes(${step.repeatCount ?: 1}) {")
            step.children.forEach { renderRoutineStep(out, it, "$indent    ") }
            out.appendLine("$indent}")
        }
        RoutineStepKind.BRANCH -> {
            val cond = (step.conditionKey ?: "").quote()
            if (step.arguments.isEmpty()) {
                out.appendLine("${indent}branch($cond) {")
            } else {
                out.appendLine("${indent}branch(")
                out.appendLine("$indent    condition = $cond,")
                out.appendLine("$indent    arguments = {")
                renderArguments(out, step.arguments, "$indent        ")
                out.appendLine("$indent    },")
                out.appendLine("$indent) {")
            }
            out.appendLine("$indent    then {")
            step.children.forEach { renderRoutineStep(out, it, "$indent        ") }
            out.appendLine("$indent    }")
            if (step.elseChildren.isNotEmpty()) {
                out.appendLine("$indent    otherwise {")
                step.elseChildren.forEach { renderRoutineStep(out, it, "$indent        ") }
                out.appendLine("$indent    }")
            }
            out.appendLine("$indent}")
        }
    }
}

private fun renderArguments(out: StringBuilder, arguments: Map<String, String>, indent: String) {
    arguments.forEach { (key, raw) ->
        val doubleVal = raw.toDoubleOrNull()
        val longVal = raw.toLongOrNull()
        when {
            raw.equals("true", ignoreCase = true) || raw.equals("false", ignoreCase = true) ->
                out.appendLine("${indent}boolean(${key.quote()}, ${raw.lowercase()})")
            longVal != null && !raw.contains('.') ->
                out.appendLine("${indent}integer(${key.quote()}, ${longVal}L)")
            doubleVal != null && doubleVal.isFinite() ->
                out.appendLine("${indent}number(${key.quote()}, $doubleVal)")
            else ->
                out.appendLine("${indent}text(${key.quote()}, ${raw.quote()})")
        }
    }
}

private fun String.quote(): String = buildString {
    append('"')
    for (ch in this@quote) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(ch)
        }
    }
    append('"')
}
