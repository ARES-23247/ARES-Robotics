package com.areslib.superstructure

import com.areslib.subsystem.InterlockComparison

/**
 * Renders this canonical [SuperstructureDocument] into idiomatic, copy-pasteable Kotlin Text DSL
 * code ([superstructure]).
 *
 * Default arguments are omitted where possible so the resulting snippet reads like hand-authored
 * Kotlin code.
 */
fun SuperstructureDocument.toKotlinDsl(): String = buildString {
    val defaultDisplayName = defaultSuperstructureDisplayName(superstructureId)
    val headerArgs = buildList {
        add("id = ${superstructureId.quote()}")
        if (displayName != defaultDisplayName) add("displayName = ${displayName.quote()}")
        if (initialStateId != "IDLE") add("initialStateId = ${initialStateId.quote()}")
        if (faultStateId != "SAFE_NEUTRAL") add("faultStateId = ${faultStateId.quote()}")
        if (disabledStateId != faultStateId) add("disabledStateId = ${disabledStateId.quote()}")
        if (disabledPolicy != SuperstructureDisabledPolicy.FORCE_SAFE_AND_REJECT_REQUESTS) {
            add("disabledPolicy = SuperstructureDisabledPolicy.${disabledPolicy.name}")
        }
    }
    if (headerArgs.size <= 2) {
        appendLine("superstructure(${headerArgs.joinToString(", ")}) {")
    } else {
        appendLine("superstructure(")
        headerArgs.forEach { appendLine("    $it,") }
        appendLine(") {")
    }

    val defaultDesc = "$displayName coordinated state machine."
    if (description != defaultDesc && description.isNotBlank()) {
        appendLine("    description = ${description.quote()}")
    }

    luts.forEach { lut ->
        val lutArgs = buildList {
            add(lut.lutId.quote())
            if (lut.displayName != defaultSuperstructureDisplayName(lut.lutId)) {
                add("displayName = ${lut.displayName.quote()}")
            }
            if (lut.inputUnit.isNotEmpty()) add("inputUnit = ${lut.inputUnit.quote()}")
            if (lut.outputUnit.isNotEmpty()) add("outputUnit = ${lut.outputUnit.quote()}")
            if (lut.interpolation != LutInterpolationMethod.LINEAR) {
                add("interpolation = LutInterpolationMethod.${lut.interpolation.name}")
            }
        }
        appendLine("    lut(${lutArgs.joinToString(", ")}) {")
        lut.controlPoints.forEach { pt ->
            appendLine("        point(${pt.inputX}, ${pt.outputY})")
        }
        appendLine("    }")
    }

    states.forEach { preset ->
        val stateArgs = buildList {
            add(preset.stateId.quote())
            if (preset.displayName != preset.stateId) add("displayName = ${preset.displayName.quote()}")
            if (preset.description.isNotEmpty()) add("description = ${preset.description.quote()}")
        }
        appendLine("    state(${stateArgs.joinToString(", ")}) {")
        preset.subsystemTargets.forEach { target ->
            val targetRef = target.target.toPortExpr()
            when (target.targetMode) {
                SuperstructureTargetMode.CONSTANT -> {
                    when {
                        target.constantDoubleValue != null ->
                            appendLine("        targetDouble($targetRef, ${target.constantDoubleValue})")
                        target.constantBooleanValue != null ->
                            appendLine("        targetBoolean($targetRef, ${target.constantBooleanValue})")
                        target.constantStringValue != null ->
                            appendLine("        targetString($targetRef, ${target.constantStringValue.quote()})")
                    }
                }
                SuperstructureTargetMode.DYNAMIC_LUT -> {
                    val srcRef = target.source?.toPortExpr() ?: "port(\"\", \"\")"
                    appendLine("        targetLut($targetRef, ${(target.lutId ?: "").quote()}, source = $srcRef)")
                }
                SuperstructureTargetMode.PASS_THROUGH -> {
                    val srcRef = target.source?.toPortExpr() ?: "port(\"\", \"\")"
                    appendLine("        targetPassThrough($targetRef, source = $srcRef)")
                }
            }
        }
        if (preset.onEntryActionKeys.isNotEmpty()) {
            appendLine("        onEntry(${preset.onEntryActionKeys.joinToString(", ") { it.quote() }})")
        }
        if (preset.onExitActionKeys.isNotEmpty()) {
            appendLine("        onExit(${preset.onExitActionKeys.joinToString(", ") { it.quote() }})")
        }
        if (preset.timeoutSeconds != null && preset.timeoutTargetStateId != null) {
            appendLine("        timeout(${preset.timeoutSeconds}, ${preset.timeoutTargetStateId.quote()})")
        }
        appendLine("    }")
    }

    transitions.forEach { edge ->
        renderTransitionEdge(this, edge)
    }

    interlocks.forEach { rule ->
        val args = buildList {
            add(rule.ruleId.quote())
            add("primary = ${rule.primary.toPortExpr()}")
            add("constrained = ${rule.constrained.toPortExpr()}")
            if (rule.conditionComparison != InterlockComparison.LESS_THAN) {
                add("comparison = InterlockComparison.${rule.conditionComparison.name}")
            }
            if (rule.conditionThreshold != 0.0) add("threshold = ${rule.conditionThreshold}")
            if (rule.clampMinimum != null) add("clampMinimum = ${rule.clampMinimum}")
            if (rule.clampMaximum != null) add("clampMaximum = ${rule.clampMaximum}")
            if (rule.description.isNotEmpty()) add("description = ${rule.description.quote()}")
        }
        appendLine("    interlock(${args.joinToString(", ")})")
    }

    healthFallbacks.forEach { policy ->
        val args = buildList {
            add(policy.policyId.quote())
            add("source = ${policy.source.toPortExpr()}")
            if (policy.fallbackStateId != faultStateId) {
                add("fallbackStateId = ${policy.fallbackStateId.quote()}")
            }
            if (!policy.latchFault) add("latchFault = false")
            if (policy.description.isNotEmpty()) add("description = ${policy.description.quote()}")
        }
        appendLine("    healthFallback(${args.joinToString(", ")})")
    }

    nodeLayouts.forEach { (stateId, layout) ->
        appendLine("    layout(${stateId.quote()}, x = ${layout.x}, y = ${layout.y})")
    }

    append("}")
}

private fun renderTransitionEdge(out: StringBuilder, edge: StateTransitionEdge) {
    val hasExtras = edge.guards.isNotEmpty() || edge.debounceMs != 0L ||
        (edge.triggerKind == TransitionTriggerKind.ACTION_REQUEST &&
            (edge.priority != 0 || edge.timeoutSeconds != null || edge.timeoutTargetStateId != null))

    when {
        edge.triggerKind == TransitionTriggerKind.ACTION_REQUEST && !hasExtras -> {
            out.appendLine(
                "    onActionTransition(${edge.transitionId.quote()}, " +
                    "from = ${edge.sourceStateId.quote()}, to = ${edge.targetStateId.quote()}, " +
                    "actionKey = ${(edge.actionKey ?: "").quote()})"
            )
        }
        edge.triggerKind == TransitionTriggerKind.TIME_ELAPSED && edge.guards.isEmpty() -> {
            val args = buildList {
                add(edge.transitionId.quote())
                add("from = ${edge.sourceStateId.quote()}")
                add("to = ${edge.targetStateId.quote()}")
                add("timeoutSeconds = ${edge.timeoutSeconds ?: 0.0}")
                if (edge.priority != 0) add("priority = ${edge.priority}")
                if (edge.debounceMs != 0L) add("debounceMs = ${edge.debounceMs}L")
            }
            out.appendLine("    timedTransition(${args.joinToString(", ")})")
        }
        edge.triggerKind == TransitionTriggerKind.SENSOR_CONDITION_AUTO && edge.debounceMs == 0L &&
            edge.timeoutSeconds == null -> {
            val prioArg = if (edge.priority != 0) ", priority = ${edge.priority}" else ""
            out.appendLine(
                "    sensorTransition(${edge.transitionId.quote()}, " +
                    "from = ${edge.sourceStateId.quote()}, to = ${edge.targetStateId.quote()}$prioArg) {"
            )
            edge.guards.forEach { renderGuardLine(out, it) }
            out.appendLine("    }")
        }
        else -> {
            out.appendLine(
                "    transition(${edge.transitionId.quote()}, " +
                    "from = ${edge.sourceStateId.quote()}, to = ${edge.targetStateId.quote()}) {"
            )
            when (edge.triggerKind) {
                TransitionTriggerKind.ACTION_REQUEST -> {
                    val timeoutArgs = buildList {
                        add((edge.actionKey ?: "").quote())
                        if (edge.timeoutSeconds != null) add("timeoutSeconds = ${edge.timeoutSeconds}")
                        if (edge.timeoutTargetStateId != null) {
                            add("timeoutTargetStateId = ${edge.timeoutTargetStateId.quote()}")
                        }
                    }
                    out.appendLine("        onAction(${timeoutArgs.joinToString(", ")})")
                    if (edge.priority != 0) out.appendLine("        priority = ${edge.priority}")
                }
                TransitionTriggerKind.SENSOR_CONDITION_AUTO -> {
                    val prioArg = if (edge.priority != 0) "priority = ${edge.priority}" else ""
                    out.appendLine("        onSensorCondition($prioArg)")
                }
                TransitionTriggerKind.TIME_ELAPSED -> {
                    val prioArg = if (edge.priority != 0) ", priority = ${edge.priority}" else ""
                    out.appendLine("        afterSeconds(${edge.timeoutSeconds ?: 0.0}$prioArg)")
                }
            }
            if (edge.debounceMs != 0L) out.appendLine("        debounceMs = ${edge.debounceMs}L")
            edge.guards.forEach { renderGuardLine(out, it) }
            out.appendLine("    }")
        }
    }
}

private fun renderGuardLine(out: StringBuilder, guard: TransitionGuard) {
    val src = guard.source.toPortExpr()
    val stale = guard.maxStalenessMs?.let { ", maxStalenessMs = ${it}L" }.orEmpty()
    when {
        guard.expectedDoubleValue != null -> {
            val cmp = if (guard.comparison != InterlockComparison.GREATER_THAN) {
                ", comparison = InterlockComparison.${guard.comparison.name}"
            } else {
                ""
            }
            val tol = if (guard.tolerance != 1e-4) ", tolerance = ${guard.tolerance}" else ""
            out.appendLine(
                "        guardDouble(${guard.guardId.quote()}, source = $src$cmp, " +
                    "expected = ${guard.expectedDoubleValue}$tol$stale)"
            )
        }
        guard.expectedBooleanValue != null -> {
            val exp = if (!guard.expectedBooleanValue) ", expected = false" else ""
            out.appendLine("        guardBoolean(${guard.guardId.quote()}, source = $src$exp$stale)")
        }
        guard.expectedStringValue != null -> {
            out.appendLine(
                "        guardString(${guard.guardId.quote()}, source = $src, " +
                    "expected = ${guard.expectedStringValue.quote()}$stale)"
            )
        }
    }
}

private fun SuperstructureFieldReference.toPortExpr(): String =
    if (healthRequirement == SuperstructurePortHealthRequirement.CONTROL_READY) {
        "SuperstructureFieldReference(${subsystemUid.quote()}, ${fieldUid.quote()})"
    } else {
        "SuperstructureFieldReference(${subsystemUid.quote()}, ${fieldUid.quote()}, SuperstructurePortHealthRequirement.${healthRequirement.name})"
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
