package com.areslib.controls

/**
 * Renders this canonical [ControlSchemeDocument] into idiomatic, copy-pasteable Kotlin Text DSL
 * code ([controlScheme]).
 *
 * Groups bindings under their assigned `controller(slot, profile, devicePort)` blocks and emits
 * fluent modifier chains (`deadband`, `curve`, `invert`, `scale`, `slewRate`, `debounce`,
 * `onPress`, `whileHeld`, `drive`).
 */
fun ControlSchemeDocument.toKotlinDsl(): String = buildString {
    val defaultName = defaultControlSchemeDisplayName(documentId)
    val headerArgs = buildList {
        add(documentId.quote())
        if (name != defaultName && name.isNotBlank()) add("name = ${name.quote()}")
        if (revision != 1) add("revision = $revision")
        if (!description.isNullOrBlank()) add("description = ${description.orEmpty().quote()}")
    }
    appendLine("controlScheme(${headerArgs.joinToString(", ")}) {")

    val bindingsBySlot = bindings.groupBy { it.source.controllerSlot }
    controllers.forEach { ctrl ->
        val defaultDisplay = ctrl.slot.replaceFirstChar(Char::titlecase)
        val ctrlArgs = buildList {
            add(ctrl.slot.quote())
            add("profile = ${ctrl.profileId.quote()}")
            add("devicePort = ${ctrl.devicePort}")
            if (ctrl.displayName != defaultDisplay && ctrl.displayName.isNotBlank()) {
                add("displayName = ${ctrl.displayName.quote()}")
            }
        }
        appendLine("    controller(${ctrlArgs.joinToString(", ")}) {")
        bindingsBySlot[ctrl.slot].orEmpty().forEach { binding ->
            renderBindingLine(this, binding, indent = "        ")
        }
        appendLine("    }")
    }

    append("}")
}

private fun renderBindingLine(out: StringBuilder, binding: ControlBindingDocument, indent: String) {
    val src = binding.source
    val isAnalogSource = src.kind in setOf(ControlSourceKind.AXIS_VALUE, ControlSourceKind.AXIS_ZONE)
    val chain = buildList {
        when (src.kind) {
            ControlSourceKind.BUTTON -> {
                val id = src.controlIds.firstOrNull().orEmpty()
                add("button(${id.quote()})")
            }
            ControlSourceKind.CHORD -> {
                val controls = src.controlIds.joinToString(", ") { it.quote() }
                add("chord($controls)")
                if (src.chordWindowSeconds != 0.075) {
                    add("chordWindow(${src.chordWindowSeconds})")
                }
            }
            ControlSourceKind.AXIS_THRESHOLD -> {
                val id = src.controlIds.firstOrNull().orEmpty()
                val trigArgs = buildList {
                    add(id.quote())
                    if (src.pressThreshold != 0.65) add("pressAt = ${src.pressThreshold}")
                    if (src.releaseThreshold != 0.55) add("releaseAt = ${src.releaseThreshold}")
                    if (src.thresholdDirection != ControlThresholdDirection.ABOVE) {
                        add("direction = ControlThresholdDirection.${src.thresholdDirection.name}")
                    }
                }
                add("trigger(${trigArgs.joinToString(", ")})")
            }
            ControlSourceKind.AXIS_VALUE -> {
                val id = src.controlIds.firstOrNull().orEmpty()
                add("axis(${id.quote()})")
            }
            ControlSourceKind.AXIS_ZONE -> {
                val id = src.controlIds.firstOrNull().orEmpty()
                val zoneArgs = buildList {
                    add(id.quote())
                    add("minimum = ${src.zoneMinimum ?: -1.0}")
                    add("maximum = ${src.zoneMaximum ?: 1.0}")
                    if (src.zoneHysteresis != 0.05) add("hysteresis = ${src.zoneHysteresis}")
                }
                add("zone(${zoneArgs.joinToString(", ")})")
            }
        }

        src.transform?.let { tf ->
            val defaultMin = if (src.kind == ControlSourceKind.AXIS_THRESHOLD) 0.0 else -1.0
            val defaultCenter = 0.0
            if (tf.inputMinimum != defaultMin || tf.inputCenter != defaultCenter || tf.inputMaximum != 1.0) {
                add("inputRange(minimum = ${tf.inputMinimum}, center = ${tf.inputCenter}, maximum = ${tf.inputMaximum})")
            }
            if (tf.deadband != 0.0) add("deadband(${tf.deadband})")
            if (tf.exponent != 1.0) add("curve(${tf.exponent})")
            if (tf.inverted) add("invert()")
            if (tf.outputMinimum == -tf.outputMaximum && tf.outputMaximum != 1.0 && !isTriggerZeroMin(src, tf)) {
                add("scale(${tf.outputMaximum})")
            } else if (isTriggerZeroMin(src, tf) && tf.outputMaximum != 1.0) {
                add("scale(${tf.outputMaximum})")
            } else if (tf.outputMinimum != (if (src.kind == ControlSourceKind.AXIS_THRESHOLD) 0.0 else -1.0) ||
                tf.outputMaximum != 1.0
            ) {
                add("outputRange(${tf.outputMinimum}, ${tf.outputMaximum})")
            }
        }

        if (!isAnalogSource) {
            val t = binding.timing
            if (t.pressDebounceSeconds > 0.0 || t.releaseDebounceSeconds > 0.0) {
                if (t.pressDebounceSeconds == t.releaseDebounceSeconds) {
                    add("debounce(${t.pressDebounceSeconds})")
                } else {
                    add("debounce(${t.pressDebounceSeconds}, ${t.releaseDebounceSeconds})")
                }
            }
            if (t.cooldownSeconds > 0.0) add("cooldown(${t.cooldownSeconds})")
            val holdAfter = t.holdAfterSeconds
            if (holdAfter != null && holdAfter != 0.35 && binding.event == ControlEvent.HOLD) {
                add("holdAfter($holdAfter)")
            }
            val repAfter = t.repeatAfterSeconds
            val repEvery = t.repeatEverySeconds
            if (repAfter != null && repEvery != null && (repAfter != 0.40 || repEvery != 0.20)) {
                if (binding.event == ControlEvent.REPEAT) {
                    add("repeat(afterSeconds = $repAfter, everySeconds = $repEvery)")
                }
            }
            t.maximumActiveSeconds?.let { add("maximumActive($it)") }
            if (src.kind != ControlSourceKind.CHORD) {
                if (binding.priority != 0) add("priority(${binding.priority})")
                if (binding.suppressConstituentBindings) add("suppressSingles()")
            }
        } else {
            val p = binding.analogPolicy ?: AnalogControlPolicyDocument()
            if (p.emitOnlyOnChange) {
                if (p.changeEpsilon == 1e-6) add("onlyOnChange()") else add("onlyOnChange(${p.changeEpsilon})")
            }
            val rise = p.riseRatePerSecond
            val fall = p.fallRatePerSecond
            if (rise != null) {
                if (fall == null || fall == rise) {
                    add("slewRate($rise)")
                } else {
                    add("slewRate($rise, $fall)")
                }
            }
            if (p.rearmNeutralThreshold != 0.05) {
                add("rearmNearNeutral(${p.rearmNeutralThreshold})")
            }
        }
    }

    val prefix = chain.joinToString(".")
    val target = binding.target
    if (isAnalogSource && binding.event == ControlEvent.VALUE && target.kind == ControlTargetKind.DRIVE) {
        val axisExpr = when (target.key) {
            DriveAxisKeys.VX -> "DriveAxisKeys.VX"
            DriveAxisKeys.VY -> "DriveAxisKeys.VY"
            DriveAxisKeys.OMEGA -> "DriveAxisKeys.OMEGA"
            else -> target.key.quote()
        }
        out.appendLine("$indent$prefix.drive($axisExpr)")
        return
    }

    val eventMethod = when (binding.event) {
        ControlEvent.PRESS -> "onPress"
        ControlEvent.RELEASE -> "onRelease"
        ControlEvent.HELD -> "whileHeld"
        ControlEvent.HOLD -> "onHold"
        ControlEvent.REPEAT -> "onRepeat"
        ControlEvent.VALUE -> {
            val argKey = binding.analogPolicy?.valueArgumentKey ?: "value"
            if (argKey == "value") "onValue" else "onValue(${argKey.quote()})"
        }
        ControlEvent.ZONE_ENTER -> "onEnter"
        ControlEvent.ZONE_ACTIVE -> "whileInside"
        ControlEvent.ZONE_EXIT -> "onExit"
    }

    val targetExpr = renderTargetExpr(target)
    out.appendLine("$indent$prefix.$eventMethod { $targetExpr }")
}

private fun isTriggerZeroMin(src: ControlSourceDocument, tf: AxisTransformDocument): Boolean =
    src.kind == ControlSourceKind.AXIS_THRESHOLD && tf.inputMinimum >= 0.0 && tf.outputMinimum == 0.0

private fun renderTargetExpr(target: ControlTargetDocument): String = when (target.kind) {
    ControlTargetKind.ACTION -> {
        if (target.arguments.isEmpty()) {
            "action(${target.key.quote()})"
        } else {
            val args = target.arguments.entries.joinToString("; ") { (k, v) ->
                val d = v.toDoubleOrNull()
                val l = v.toLongOrNull()
                when {
                    v.equals("true", ignoreCase = true) || v.equals("false", ignoreCase = true) ->
                        "boolean(${k.quote()}, ${v.lowercase()})"
                    l != null && !v.contains('.') ->
                        "integer(${k.quote()}, ${l}L)"
                    d != null && d.isFinite() ->
                        "number(${k.quote()}, $d)"
                    else ->
                        "text(${k.quote()}, ${v.quote()})"
                }
            }
            "action(${target.key.quote()}) { $args }"
        }
    }
    ControlTargetKind.ROUTINE -> {
        if (target.routinePolicy == RoutineInvocationPolicy.RESTART_EXISTING) {
            "routine(${target.key.quote()})"
        } else {
            "routine(${target.key.quote()}, RoutineInvocationPolicy.${target.routinePolicy.name})"
        }
    }
    ControlTargetKind.CANCEL_ROUTINE -> "cancelRoutine(${target.key.quote()})"
    ControlTargetKind.DRIVE -> "drive(${target.key.quote()})"
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
