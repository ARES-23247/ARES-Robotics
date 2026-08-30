package com.areslib.controls

import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.catalog.CatalogValidationSeverity
import com.areslib.catalog.validateCapabilityCatalog
import com.areslib.util.sha256Hex
import com.google.gson.GsonBuilder

private val CONTROL_STABLE_KEY = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
private val CONTROL_ARGUMENT_KEY = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")

enum class ControlValidationSeverity { WARNING, ERROR }

data class ControlValidationIssue(
    val severity: ControlValidationSeverity,
    val path: String,
    val code: String,
    val message: String
)

/** References available while validating an offline project. */
data class ControlValidationContext(
    val actionKeys: Set<String> = emptySet(),
    val routineIds: Set<String> = emptySet(),
    val profileControls: Map<String, Set<String>> = emptyMap()
) {
    companion object {
        fun fromCatalog(
            catalog: CapabilityCatalogDocument,
            routineIds: Set<String>,
            profileControls: Map<String, Set<String>> = emptyMap()
        ): ControlValidationContext {
            require(validateCapabilityCatalog(catalog).none { it.severity == CatalogValidationSeverity.ERROR }) {
                "Cannot validate controls against an invalid capability catalog"
            }
            return ControlValidationContext(catalog.actions.mapTo(linkedSetOf()) { it.key }, routineIds, profileControls)
        }
    }
}

/** Fail-closed validation shared by Analytics, code generation, and robot-side tests. */
fun validateControlScheme(
    document: ControlSchemeDocument,
    context: ControlValidationContext = ControlValidationContext()
): List<ControlValidationIssue> {
    val issues = mutableListOf<ControlValidationIssue>()
    if (document.schemaVersion != ARES_CONTROL_SCHEME_SCHEMA_VERSION) {
        issues += controlError("document", "unsupported_schema", "Unsupported control schema ${document.schemaVersion}")
    }
    validateStableKey(document.documentId, "documentId", issues)
    if (document.revision < 1) issues += controlError("revision", "invalid_revision", "Revision must be at least 1")
    if (document.name.isBlank()) issues += controlError("name", "missing_name", "Control scheme name is required")
    if (document.controllers.isEmpty()) {
        issues += controlError("controllers", "missing_controllers", "At least one controller assignment is required")
    }

    val slots = linkedSetOf<String>()
    val devicePorts = linkedSetOf<Int>()
    val profileBySlot = linkedMapOf<String, String>()
    document.controllers.forEachIndexed { index, controller ->
        val path = "controllers[$index]"
        validateStableKey(controller.slot, "$path.slot", issues)
        if (!slots.add(controller.slot)) {
            issues += controlError(path, "duplicate_controller_slot", "Controller slot '${controller.slot}' is duplicated")
        }
        if (controller.displayName.isBlank()) {
            issues += controlError(path, "missing_controller_name", "Controller display name is required")
        }
        validateStableKey(controller.profileId, "$path.profileId", issues)
        val port = controller.devicePort
        if (port == null) {
            issues += controlError(path, "missing_device_port", "Controller port is required")
        } else if (port !in 0..MAX_CONTROLLER_DEVICE_PORT) {
            issues += controlError(
                path,
                "invalid_device_port",
                "Controller port must be between 0 and $MAX_CONTROLLER_DEVICE_PORT",
            )
        } else if (!devicePorts.add(port)) {
            issues += controlError(path, "duplicate_device_port", "Controller port $port is assigned more than once")
        }
        profileBySlot[controller.slot] = controller.profileId
    }

    val bindingIds = linkedSetOf<String>()
    val enabledSignatures = linkedMapOf<String, String>()
    val boundDriveAxes = linkedMapOf<String, String>()
    document.bindings.forEachIndexed { index, binding ->
        val path = "bindings[$index]"
        validateStableKey(binding.bindingId, "$path.bindingId", issues)
        if (!bindingIds.add(binding.bindingId)) {
            issues += controlError(path, "duplicate_binding", "Binding '${binding.bindingId}' is duplicated")
        }
        if (binding.displayName.isBlank()) {
            issues += controlError(path, "missing_binding_name", "Binding display name is required")
        }
        if (binding.priority !in -1_000_000..1_000_000) {
            issues += controlError(path, "invalid_priority", "Binding priority is outside the supported range")
        }
        validateSource(binding, path, slots, profileBySlot, context, issues)
        validateTiming(binding.timing, "$path.timing", issues)
        validateTarget(binding, path, context, issues)
        validateEventCompatibility(binding, path, issues)
        if (binding.enabled && binding.target.kind == ControlTargetKind.DRIVE) {
            boundDriveAxes.putIfAbsent(binding.target.key, binding.bindingId)?.let { existing ->
                issues += controlError(
                    path,
                    "duplicate_drive_axis",
                    "Drive axis '${binding.target.key}' is already bound by '$existing'; " +
                        "each axis accepts exactly one binding so control is never ambiguous",
                )
            }
        }

        if (binding.enabled) {
            val signature = sourceSignature(binding)
            enabledSignatures.putIfAbsent(signature, binding.bindingId)?.let { existing ->
                issues += controlError(
                    path,
                    "ambiguous_binding",
                    "Binding '${binding.bindingId}' conflicts with '$existing' on the same input and event"
                )
            }
        }
    }
    if (document.bindings.isEmpty()) {
        issues += ControlValidationIssue(
            ControlValidationSeverity.WARNING,
            "bindings",
            "empty_bindings",
            "This control scheme has no bindings"
        )
    }
    return issues
}

private fun validateSource(
    binding: ControlBindingDocument,
    path: String,
    slots: Set<String>,
    profileBySlot: Map<String, String>,
    context: ControlValidationContext,
    issues: MutableList<ControlValidationIssue>
) {
    val source = binding.source
    val sourcePath = "$path.source"
    if (source.controllerSlot !in slots) {
        issues += controlError(sourcePath, "unknown_controller", "Unknown controller slot '${source.controllerSlot}'")
    }
    val expectedControls = if (source.kind == ControlSourceKind.CHORD) 2 else 1
    if (source.controlIds.size < expectedControls ||
        source.kind != ControlSourceKind.CHORD && source.controlIds.size != 1
    ) {
        issues += controlError(
            sourcePath,
            "invalid_control_count",
            if (source.kind == ControlSourceKind.CHORD) "A chord requires at least two controls" else "This source requires exactly one control"
        )
    }
    if (source.controlIds.any { !it.matches(CONTROL_STABLE_KEY) } || source.controlIds.distinct().size != source.controlIds.size) {
        issues += controlError(sourcePath, "invalid_controls", "Control IDs must be stable and unique")
    }
    val profileId = profileBySlot[source.controllerSlot]
    val knownControls = profileId?.let(context.profileControls::get)
    if (knownControls != null) {
        source.controlIds.filterNot(knownControls::contains).forEach { controlId ->
            issues += controlError(sourcePath, "unknown_control", "Profile '$profileId' has no control '$controlId'")
        }
    }

    when (source.kind) {
        ControlSourceKind.BUTTON -> rejectAnalogSourceFields(source, sourcePath, issues)
        ControlSourceKind.CHORD -> {
            rejectAnalogSourceFields(source, sourcePath, issues)
            requireFiniteNonNegative(source.chordWindowSeconds, sourcePath, "invalid_chord_window", issues)
        }
        ControlSourceKind.AXIS_THRESHOLD -> {
            validateTransform(source.transform, "$sourcePath.transform", issues)
            val press = source.pressThreshold
            val release = source.releaseThreshold
            if (press == null || release == null || !press.isFinite() || !release.isFinite()) {
                issues += controlError(sourcePath, "missing_thresholds", "Axis threshold requires finite press and release thresholds")
            } else if (source.thresholdDirection == ControlThresholdDirection.ABOVE && press <= release ||
                source.thresholdDirection == ControlThresholdDirection.BELOW && press >= release
            ) {
                issues += controlError(sourcePath, "invalid_hysteresis", "Press and release thresholds must define hysteresis")
            }
        }
        ControlSourceKind.AXIS_VALUE -> validateTransform(source.transform, "$sourcePath.transform", issues)
        ControlSourceKind.AXIS_ZONE -> {
            validateTransform(source.transform, "$sourcePath.transform", issues)
            val minimum = source.zoneMinimum
            val maximum = source.zoneMaximum
            if (minimum == null || maximum == null || !minimum.isFinite() || !maximum.isFinite() || minimum > maximum) {
                issues += controlError(sourcePath, "invalid_zone", "Axis zone requires finite minimum <= maximum")
            }
            requireFiniteNonNegative(source.zoneHysteresis, sourcePath, "invalid_zone_hysteresis", issues)
        }
    }
}

private fun rejectAnalogSourceFields(
    source: ControlSourceDocument,
    path: String,
    issues: MutableList<ControlValidationIssue>
) {
    if (source.transform != null || source.pressThreshold != null || source.releaseThreshold != null ||
        source.zoneMinimum != null || source.zoneMaximum != null || source.zoneHysteresis != 0.0
    ) {
        issues += controlError(path, "unexpected_analog_settings", "Button and chord sources cannot contain analog settings")
    }
}

private fun validateTransform(
    transform: AxisTransformDocument?,
    path: String,
    issues: MutableList<ControlValidationIssue>
) {
    val value = transform ?: return
    val numbers = listOf(
        value.inputMinimum,
        value.inputCenter,
        value.inputMaximum,
        value.outputMinimum,
        value.outputMaximum,
        value.deadband,
        value.exponent
    )
    if (numbers.any { !it.isFinite() } || value.inputMinimum > value.inputCenter ||
        value.inputCenter >= value.inputMaximum || value.outputMinimum > 0.0 || value.outputMaximum < 0.0 ||
        value.deadband < 0.0 || value.deadband >= 1.0 || value.exponent <= 0.0
    ) {
        issues += controlError(path, "invalid_axis_transform", "Axis transform ranges, deadband, and exponent are invalid")
    }
}

private fun validateTiming(
    timing: ControlTimingDocument,
    path: String,
    issues: MutableList<ControlValidationIssue>
) {
    listOf(
        "pressDebounceSeconds" to timing.pressDebounceSeconds,
        "releaseDebounceSeconds" to timing.releaseDebounceSeconds,
        "cooldownSeconds" to timing.cooldownSeconds
    ).forEach { (name, value) -> requireFiniteNonNegative(value, "$path.$name", "invalid_duration", issues) }
    listOf(
        "holdAfterSeconds" to timing.holdAfterSeconds,
        "repeatAfterSeconds" to timing.repeatAfterSeconds,
        "repeatEverySeconds" to timing.repeatEverySeconds,
        "maximumActiveSeconds" to timing.maximumActiveSeconds
    ).forEach { (name, value) ->
        if (value != null) requireFiniteNonNegative(value, "$path.$name", "invalid_duration", issues)
    }
    if ((timing.repeatAfterSeconds == null) != (timing.repeatEverySeconds == null)) {
        issues += controlError(path, "incomplete_repeat", "Repeat delay and interval must be configured together")
    }
    if (timing.repeatEverySeconds == 0.0) {
        issues += controlError(path, "zero_repeat_interval", "Repeat interval must be greater than zero")
    }
}

private fun validateTarget(
    binding: ControlBindingDocument,
    path: String,
    context: ControlValidationContext,
    issues: MutableList<ControlValidationIssue>
) {
    val target = binding.target
    validateStableKey(target.key, "$path.target.key", issues)
    if (target.arguments.keys.any { !it.matches(CONTROL_ARGUMENT_KEY) }) {
        issues += controlError("$path.target.arguments", "invalid_argument", "Argument keys must be stable Kotlin identifiers")
    }
    when (target.kind) {
        ControlTargetKind.ACTION -> if (context.actionKeys.isNotEmpty() && target.key !in context.actionKeys) {
            issues += controlError("$path.target", "unknown_action", "Unknown action '${target.key}'")
        }
        ControlTargetKind.ROUTINE, ControlTargetKind.CANCEL_ROUTINE ->
            if (context.routineIds.isNotEmpty() && target.key !in context.routineIds) {
                issues += controlError("$path.target", "unknown_routine", "Unknown routine '${target.key}'")
            }
        ControlTargetKind.DRIVE -> {
            if (target.key !in DriveAxisKeys.ALL) {
                issues += controlError(
                    "$path.target",
                    "unknown_drive_axis",
                    "Drivetrain axis must be one of ${DriveAxisKeys.ALL.sorted().joinToString(", ")}",
                )
            }
            if (target.arguments.isNotEmpty()) {
                issues += controlError("$path.target", "invalid_drive_arguments", "Drivetrain axis targets take no arguments")
            }
            if (binding.source.kind != ControlSourceKind.AXIS_VALUE || binding.event != ControlEvent.VALUE) {
                issues += controlError(
                    path,
                    "invalid_drive_binding",
                    "Drivetrain axis '${target.key}' requires an AXIS_VALUE source with a VALUE event",
                )
            }
        }
    }
}

private fun validateEventCompatibility(
    binding: ControlBindingDocument,
    path: String,
    issues: MutableList<ControlValidationIssue>
) {
    val allowed = when (binding.source.kind) {
        ControlSourceKind.BUTTON, ControlSourceKind.CHORD, ControlSourceKind.AXIS_THRESHOLD ->
            setOf(ControlEvent.PRESS, ControlEvent.RELEASE, ControlEvent.HELD, ControlEvent.HOLD, ControlEvent.REPEAT)
        ControlSourceKind.AXIS_VALUE -> setOf(ControlEvent.VALUE)
        ControlSourceKind.AXIS_ZONE -> setOf(ControlEvent.ZONE_ENTER, ControlEvent.ZONE_ACTIVE, ControlEvent.ZONE_EXIT)
    }
    if (binding.event !in allowed) {
        issues += controlError(path, "incompatible_event", "${binding.event} is not valid for ${binding.source.kind}")
    }
    val analog = binding.source.kind == ControlSourceKind.AXIS_VALUE || binding.source.kind == ControlSourceKind.AXIS_ZONE
    if (analog && binding.analogPolicy == null) {
        issues += controlError(path, "missing_analog_policy", "Analog bindings require an analog policy")
    }
    if (!analog && binding.analogPolicy != null) {
        issues += controlError(path, "unexpected_analog_policy", "Digital bindings cannot contain an analog policy")
    }
    binding.analogPolicy?.let { policy ->
        val rates = listOf(policy.riseRatePerSecond, policy.fallRatePerSecond).filterNotNull()
        if (!policy.changeEpsilon.isFinite() || policy.changeEpsilon < 0.0 ||
            !policy.rearmNeutralThreshold.isFinite() || policy.rearmNeutralThreshold < 0.0 ||
            rates.any { !it.isFinite() || it <= 0.0 } || !policy.valueArgumentKey.matches(CONTROL_ARGUMENT_KEY)
        ) {
            issues += controlError(path, "invalid_analog_policy", "Analog emission, rate, or value argument settings are invalid")
        }
    }
}

private fun sourceSignature(binding: ControlBindingDocument): String = buildString {
    append(binding.source.controllerSlot).append('|').append(binding.source.kind).append('|')
    binding.source.controlIds.sorted().forEach { append(it).append(',') }
    append('|').append(binding.event).append('|')
    append(binding.source.pressThreshold).append('|').append(binding.source.releaseThreshold).append('|')
    append(binding.source.zoneMinimum).append('|').append(binding.source.zoneMaximum)
}

private fun validateStableKey(value: String, path: String, issues: MutableList<ControlValidationIssue>) {
    if (!value.matches(CONTROL_STABLE_KEY)) {
        issues += controlError(path, "invalid_key", "'$value' is not a stable project key")
    }
}

private fun requireFiniteNonNegative(
    value: Double,
    path: String,
    code: String,
    issues: MutableList<ControlValidationIssue>
) {
    if (!value.isFinite() || value < 0.0) {
        issues += controlError(path, code, "Value must be finite and non-negative")
    }
}

private fun controlError(path: String, code: String, message: String) =
    ControlValidationIssue(ControlValidationSeverity.ERROR, path, code, message)

private const val MAX_CONTROLLER_DEVICE_PORT: Int = 15

/** Deterministic JSON and SHA-256 codec for codegen, revision history, and Analytics. */
object ControlSchemeCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(document: ControlSchemeDocument): String {
        val canonical = document.canonicalized()
        requireValid(canonical)
        return gson.toJson(canonical)
    }

    fun decode(json: String): ControlSchemeDocument {
        val document = try {
            gson.fromJson(json, ControlSchemeDocument::class.java)
        } catch (error: Exception) {
            throw IllegalArgumentException("Control scheme is not valid JSON: ${error.message}", error)
        } ?: throw IllegalArgumentException("Control scheme is empty")
        try {
            requireValid(document)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Control scheme is missing or contains invalid fields", error)
        }
        return document.canonicalized()
    }

    fun contentHash(document: ControlSchemeDocument): String = sha256Hex(encode(document))

    private fun requireValid(document: ControlSchemeDocument) {
        val errors = validateControlScheme(document).filter { it.severity == ControlValidationSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString(separator = "; ") { it.message } }
    }
}

private fun ControlSchemeDocument.canonicalized(): ControlSchemeDocument = copy(
    controllers = controllers.sortedBy { it.slot },
    bindings = bindings.sortedWith(compareByDescending<ControlBindingDocument> { it.priority }.thenBy { it.bindingId })
        .map { binding ->
            binding.copy(
                source = binding.source.copy(
                    controlIds = if (binding.source.kind == ControlSourceKind.CHORD) {
                        binding.source.controlIds.sorted()
                    } else {
                        binding.source.controlIds
                    }
                ),
                target = binding.target.copy(arguments = binding.target.arguments.toSortedMap())
            )
        }
)
