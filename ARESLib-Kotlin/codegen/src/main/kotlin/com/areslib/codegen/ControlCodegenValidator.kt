package com.areslib.codegen

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityContext
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControlValidationContext
import com.areslib.controls.ControlValidationSeverity
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileDocument
import com.areslib.controls.DriveAxisKeys
import com.areslib.controls.learnedControlIds
import com.areslib.controls.validateControlScheme
import com.areslib.controls.validateControllerProfile
import com.areslib.routine.CapabilityArgumentReader

/** Validates the controller-specific portion of a project before any Kotlin is rendered. */
internal fun validateGeneratedControls(
    request: KotlinProjectCodegenRequest,
    actions: Map<String, ActionDescriptor>,
    routineIds: Set<String>,
) {
    val platform = request.targetInputPlatform
    require(request.controlSchemes.isEmpty() || platform != null) {
        "A targetInputPlatform is required when generating controller bindings"
    }
    require(request.controlSchemes.size <= 1) {
        "Robot runtime requires exactly one active control scheme; keep one .arescontrols document until explicit scheme selection is configured"
    }
    val profiles = linkedMapOf<String, ControllerProfileDocument>()
    request.controllerProfiles.forEach { profile ->
        require(profiles.putIfAbsent(profile.documentId, profile) == null) {
            "Controller profile '${profile.documentId}' is duplicated"
        }
        val errors = validateControllerProfile(profile)
            .filter { it.severity == ControlValidationSeverity.ERROR }
        require(errors.isEmpty()) {
            errors.joinToString(separator = "; ") { "${profile.documentId}:${it.path}: ${it.message}" }
        }
    }
    val profileControls = profiles.mapValues { (_, profile) ->
        if (platform == null) emptySet() else profile.learnedControlIds(platform)
    }
    val context = ControlValidationContext.fromCatalog(request.catalog, routineIds, profileControls)
    val schemeIds = mutableSetOf<String>()
    request.controlSchemes.forEach { scheme ->
        require(schemeIds.add(scheme.documentId)) { "Control scheme '${scheme.documentId}' is duplicated" }
        val errors = validateControlScheme(scheme, context)
            .filter { it.severity == ControlValidationSeverity.ERROR }
        require(errors.isEmpty()) {
            errors.joinToString(separator = "; ") { "${scheme.documentId}:${it.path}: ${it.message}" }
        }
        val profileBySlot = scheme.controllers.associate { assignment ->
            val profile = profiles[assignment.profileId]
                ?: throw IllegalArgumentException(
                    "Control scheme '${scheme.documentId}' references missing profile '${assignment.profileId}'",
                )
            assignment.slot to profile
        }
        scheme.controllers.forEach { assignment ->
            val port = requireNotNull(assignment.devicePort) {
                "Control scheme '${scheme.documentId}' controller '${assignment.slot}' is missing its Driver Station port"
            }
            val supportedRange = when (platform) {
                ControllerInputPlatform.FTC -> 0..1
                ControllerInputPlatform.FRC -> 0..5
                ControllerInputPlatform.DESKTOP_GLFW -> 0..15
                null -> error("Controller platform is required")
            }
            require(port in supportedRange) {
                "Control scheme '${scheme.documentId}' controller '${assignment.slot}' uses port $port; " +
                    "$platform supports ${supportedRange.first}..${supportedRange.last}"
            }
        }
        scheme.bindings.filter { it.enabled }.forEach { binding ->
            val profile = profileBySlot.getValue(binding.source.controllerSlot)
            binding.source.controlIds.forEach { controlId ->
                val control = profile.controls.firstOrNull { it.controlId == controlId }
                    ?: throw IllegalArgumentException(
                        "Binding '${binding.bindingId}' references unknown control '$controlId'",
                    )
                val expectedType = when (binding.source.kind) {
                    ControlSourceKind.BUTTON,
                    ControlSourceKind.CHORD,
                    -> ControllerControlTypeDocument.BUTTON

                    ControlSourceKind.AXIS_THRESHOLD,
                    ControlSourceKind.AXIS_VALUE,
                    ControlSourceKind.AXIS_ZONE,
                    -> ControllerControlTypeDocument.AXIS
                }
                require(control.type == expectedType) {
                    "Binding '${binding.bindingId}' uses ${control.type} control '$controlId' as $expectedType"
                }
                require(control.mappings.any { it.platform == platform }) {
                    "Binding '${binding.bindingId}' references control '$controlId' without a $platform mapping"
                }
            }
            binding.source.transform?.let { transform ->
                require(transform.outputMinimum <= 0.0 && transform.outputMaximum >= 0.0) {
                    "Binding '${binding.bindingId}' transform output must span zero"
                }
            }
            require(!binding.suppressConstituentBindings || binding.source.kind == ControlSourceKind.CHORD) {
                "Binding '${binding.bindingId}' can suppress constituents only when its source is a chord"
            }
            validateGeneratedControlTarget(binding, actions)
            listOf(
                binding.timing.pressDebounceSeconds,
                binding.timing.releaseDebounceSeconds,
                binding.timing.holdAfterSeconds,
                binding.timing.repeatAfterSeconds,
                binding.timing.repeatEverySeconds,
                binding.timing.cooldownSeconds,
                binding.timing.maximumActiveSeconds,
                binding.source.chordWindowSeconds,
            ).filterNotNull().forEach(::validateDurationSeconds)
        }
    }
}

private fun validateGeneratedControlTarget(
    binding: ControlBindingDocument,
    actions: Map<String, ActionDescriptor>,
) {
    val target = binding.target
    if (target.kind == ControlTargetKind.DRIVE) {
        require(target.arguments.isEmpty()) {
            "Binding '${binding.bindingId}' supplies arguments to a drivetrain axis"
        }
        require(target.key in DriveAxisKeys.ALL) {
            "Binding '${binding.bindingId}' targets unknown drivetrain axis '${target.key}'"
        }
        require(binding.source.kind == ControlSourceKind.AXIS_VALUE && binding.event == ControlEvent.VALUE) {
            "Drivetrain axis binding '${binding.bindingId}' must be an AXIS_VALUE source with a VALUE event"
        }
        return
    }
    if (target.kind != ControlTargetKind.ACTION) {
        require(target.arguments.isEmpty()) {
            "Binding '${binding.bindingId}' supplies arguments to a routine target"
        }
        require(binding.source.kind != ControlSourceKind.AXIS_VALUE) {
            "Continuous axis binding '${binding.bindingId}' must target an action, not a routine"
        }
        require(binding.event != ControlEvent.ZONE_ACTIVE) {
            "Continuously active zone binding '${binding.bindingId}' must target an action, not a routine"
        }
        return
    }
    val descriptor = actions.getValue(target.key)
    require(CapabilityContext.TELEOP in descriptor.allowedContexts) {
        "Binding '${binding.bindingId}' targets action '${target.key}', which is not allowed in teleop"
    }
    val dynamicKey = if (binding.source.kind in ANALOG_CONTROL_SOURCE_KINDS) {
        requireNotNull(binding.analogPolicy).valueArgumentKey
    } else {
        null
    }
    if (dynamicKey != null) {
        val parameter = descriptor.parameters.firstOrNull { it.key == dynamicKey }
            ?: throw IllegalArgumentException(
                "Binding '${binding.bindingId}' writes analog value to missing action argument '$dynamicKey'",
            )
        require(parameter.type == CapabilityParameterType.NUMBER) {
            "Binding '${binding.bindingId}' analog value argument '$dynamicKey' must be numeric"
        }
        require(dynamicKey !in target.arguments) {
            "Binding '${binding.bindingId}' declares both a live and static value for '$dynamicKey'"
        }
    }
    val validationArguments = if (dynamicKey == null) {
        target.arguments
    } else {
        target.arguments + (dynamicKey to "0.0")
    }
    val reader = CapabilityArgumentReader(
        target.key,
        validationArguments,
        descriptor.parameters.mapTo(mutableSetOf()) { it.key },
    )
    descriptor.parameters.forEach { reader.readGeneratedParameter(it) }
}

private fun validateDurationSeconds(seconds: Double) {
    require(seconds.isFinite() && seconds >= 0.0 && seconds <= MAX_CONTROL_DURATION_SECONDS) {
        "Duration $seconds seconds cannot be represented in monotonic nanoseconds"
    }
}

private fun CapabilityArgumentReader.readGeneratedParameter(parameter: CapabilityParameterDescriptor): Any? =
    when (parameter.type) {
        CapabilityParameterType.NUMBER -> if (parameter.isEffectivelyRequired()) {
            requiredNumber(parameter.key, parameter.defaultNumber, parameter.minimum, parameter.maximum)
        } else {
            optionalNumber(parameter.key, parameter.defaultNumber, parameter.minimum, parameter.maximum)
        }

        CapabilityParameterType.BOOLEAN -> if (parameter.isEffectivelyRequired()) {
            requiredBoolean(parameter.key, parameter.defaultBoolean)
        } else {
            optionalBoolean(parameter.key, parameter.defaultBoolean)
        }

        CapabilityParameterType.TEXT -> if (parameter.isEffectivelyRequired()) {
            requiredText(parameter.key, parameter.defaultText)
        } else {
            optionalText(parameter.key, parameter.defaultText)
        }

        CapabilityParameterType.ENUM -> if (parameter.isEffectivelyRequired()) {
            requiredEnum(parameter.key, parameter.options.toSet(), parameter.defaultText)
        } else {
            optionalEnum(parameter.key, parameter.options.toSet(), parameter.defaultText)
        }
    }

private fun CapabilityParameterDescriptor.isEffectivelyRequired(): Boolean = required || when (type) {
    CapabilityParameterType.NUMBER -> defaultNumber != null
    CapabilityParameterType.BOOLEAN -> defaultBoolean != null
    CapabilityParameterType.TEXT,
    CapabilityParameterType.ENUM,
    -> defaultText != null
}

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val MAX_CONTROL_DURATION_SECONDS = Long.MAX_VALUE / NANOS_PER_SECOND
private val ANALOG_CONTROL_SOURCE_KINDS = setOf(ControlSourceKind.AXIS_VALUE, ControlSourceKind.AXIS_ZONE)
