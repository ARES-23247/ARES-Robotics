package com.areslib.codegen

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityContext
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.catalog.CatalogValidationSeverity
import com.areslib.catalog.ConditionDescriptor
import com.areslib.catalog.ResourceAccess
import com.areslib.catalog.validateCapabilityCatalog
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetKind
import com.areslib.routine.CapabilityArgumentReader
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind
import com.areslib.routine.RoutineValidationContext
import com.areslib.routine.RoutineValidationIssue
import com.areslib.routine.RoutineValidationSeverity
import com.areslib.routine.validateAutonomousCatalog
import com.areslib.routine.validateRoutineSet
import com.areslib.project.validateAresProjectMetadata

/** Validates the complete typed project before any source is rendered. */
internal fun validateKotlinProjectCodegenRequest(request: KotlinProjectCodegenRequest) {
    require(request.packageName.split('.').all { it.isKotlinIdentifier() }) {
        "Generated package '${request.packageName}' is not a valid Kotlin package"
    }
    require(request.objectName.isKotlinIdentifier()) { "Generated object name is not a valid Kotlin identifier" }
    require(request.registryInterfaceName.isKotlinIdentifier()) {
        "Generated registry interface name is not a valid Kotlin identifier"
    }
    require(request.objectName != request.registryInterfaceName) {
        "Generated object and registry interface names must differ"
    }
    request.projectMetadata?.let { metadata ->
        val metadataIssues = validateAresProjectMetadata(metadata)
        require(metadataIssues.isEmpty()) { metadataIssues.joinToString("; ") }
        require(metadata.projectId == request.catalog.projectId) {
            "Project metadata ID '${metadata.projectId}' does not match catalog '${request.catalog.projectId}'"
        }
    }

    val catalogErrors = validateCapabilityCatalog(request.catalog)
        .filter { it.severity == CatalogValidationSeverity.ERROR }
    require(catalogErrors.isEmpty()) {
        catalogErrors.joinToString(separator = "; ") { "${it.path}: ${it.message}" }
    }
    val subsystemActionKeys = request.subsystemActions.map { it.descriptor.key }
    require(subsystemActionKeys.distinct().size == subsystemActionKeys.size) {
        "Generated subsystem action keys must be unique"
    }
    require(request.subsystemActions.isEmpty() || !request.subsystemRegistryFqn.isNullOrBlank()) {
        "Generated subsystem actions require a subsystem registry FQN"
    }
    request.subsystemActions.forEach { capability ->
        require(request.catalog.actions.singleOrNull { it == capability.descriptor } != null) {
            "Subsystem action '${capability.descriptor.key}' is missing or differs in the merged catalog"
        }
    }
    require(request.generatedActionRegistryBindings.keys.intersect(subsystemActionKeys.toSet()).isEmpty()) {
        "An action cannot be implemented by both subsystem and orchestration registries"
    }
    request.generatedActionRegistryBindings.forEach { (actionKey, registryFqn) ->
        require(registryFqn.matches(GENERATED_REGISTRY_FQN)) {
            "Generated action '$actionKey' has invalid registry FQN '$registryFqn'"
        }
        val descriptor = request.catalog.actions.singleOrNull { it.key == actionKey }
        require(descriptor != null) { "Generated action '$actionKey' is absent from the capability catalog" }
        require(descriptor.parameters.isEmpty()) {
            "Generated orchestration action '$actionKey' must be parameterless"
        }
    }

    val actions = request.catalog.actions.associateBy { it.key }
    val conditions = request.catalog.conditions.associateBy { it.key }
    val resources = actions.mapValues { (_, descriptor) -> exclusiveResourceKeys(descriptor).toSet() }
    val routineContext = RoutineValidationContext(
        hasAction = actions::containsKey,
        hasCondition = conditions::containsKey,
        resourcesForAction = { resources[it].orEmpty() },
    )
    val routineErrors = validateRoutineSet(request.routines, routineContext)
        .filter { it.severity == RoutineValidationSeverity.ERROR }
        .toMutableList()
    request.routines.forEach { routine ->
        validateStepArguments(routine.steps, actions, conditions, "${routine.documentId}.steps", routineErrors)
    }
    require(routineErrors.isEmpty()) {
        routineErrors.joinToString(separator = "; ") { "${it.documentId}:${it.path}: ${it.message}" }
    }

    val routineIds = request.routines.mapTo(mutableSetOf()) { it.documentId }
    request.autonomousCatalog?.let { autonomousCatalog ->
        val errors = validateAutonomousCatalog(autonomousCatalog, routineIds)
            .filter { it.severity == RoutineValidationSeverity.ERROR }
        require(errors.isEmpty()) {
            errors.joinToString(separator = "; ") { "${it.path}: ${it.message}" }
        }
        val routinesById = request.routines.associateBy { it.documentId }
        autonomousCatalog.entries.filter { it.enabled }.forEach { entry ->
            require(routineSupportsAutonomous(entry.routineId, routinesById, actions, mutableSetOf())) {
                "Autonomous entry '${entry.entryId}' reaches an action that is not allowed in autonomous"
            }
        }
    }
    validateGeneratedControls(request, actions, routineIds)
    validateAnalogSubsystemBindings(request, subsystemActionKeys.toSet())
}

private fun validateAnalogSubsystemBindings(
    request: KotlinProjectCodegenRequest,
    subsystemActionKeys: Set<String>,
) {
    val unsafeBindings = request.controlSchemes.asSequence()
        .flatMap { it.bindings.asSequence() }
        .filter { it.enabled && it.source.kind in ANALOG_SOURCE_KINDS }
        .filter { it.target.kind == ControlTargetKind.ACTION && it.target.key in subsystemActionKeys }
        .filter { binding ->
            val policy = binding.analogPolicy
            policy == null || !policy.emitOnlyOnChange || policy.changeEpsilon <= 0.0
        }
        .map { it.bindingId }
        .toList()
    require(unsafeBindings.isEmpty()) {
        "Generated subsystem analog bindings must emit only on meaningful changes so Redux tasks cannot flood the robot loop: " +
            unsafeBindings.joinToString()
    }
}

private fun validateStepArguments(
    steps: List<RoutineStep>,
    actions: Map<String, ActionDescriptor>,
    conditions: Map<String, ConditionDescriptor>,
    path: String,
    issues: MutableList<RoutineValidationIssue>,
) {
    steps.forEachIndexed { index, step ->
        val stepPath = "$path[$index]"
        when (step.kind) {
            RoutineStepKind.ACTION -> validateArguments(
                requireNotNull(step.actionKey),
                actions[step.actionKey]?.parameters.orEmpty(),
                step.arguments,
                stepPath,
                issues,
            )

            RoutineStepKind.WAIT_UNTIL,
            RoutineStepKind.BRANCH,
            -> validateArguments(
                requireNotNull(step.conditionKey),
                conditions[step.conditionKey]?.parameters.orEmpty(),
                step.arguments,
                stepPath,
                issues,
            )

            RoutineStepKind.DRIVE_TO -> step.drive?.let { drive ->
                val actionKeys = drive.markers.map { it.actionKey } +
                    drive.duringActionKeys + drive.arrivalActionKeys
                actionKeys.forEach { actionKey ->
                    validateArguments(
                        actionKey,
                        actions[actionKey]?.parameters.orEmpty(),
                        emptyMap(),
                        "$stepPath.drive[$actionKey]",
                        issues,
                    )
                }
            }

            else -> Unit
        }
        step.deadline?.let {
            validateStepArguments(listOf(it), actions, conditions, "$stepPath.deadline", issues)
        }
        validateStepArguments(step.children, actions, conditions, "$stepPath.children", issues)
        validateStepArguments(step.elseChildren, actions, conditions, "$stepPath.elseChildren", issues)
    }
}

private fun validateArguments(
    descriptorKey: String,
    parameters: List<CapabilityParameterDescriptor>,
    arguments: Map<String, String>,
    path: String,
    issues: MutableList<RoutineValidationIssue>,
) {
    try {
        val reader = CapabilityArgumentReader(descriptorKey, arguments, parameters.mapTo(mutableSetOf()) { it.key })
        parameters.forEach { parameter -> reader.read(parameter) }
    } catch (error: IllegalArgumentException) {
        issues += RoutineValidationIssue(
            RoutineValidationSeverity.ERROR,
            path.substringBefore('.'),
            path,
            "invalid_capability_arguments",
            error.message ?: "Capability arguments are invalid",
        )
    }
}

private fun CapabilityArgumentReader.read(parameter: CapabilityParameterDescriptor): Any? = when (parameter.type) {
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

internal fun CapabilityParameterDescriptor.isEffectivelyRequired(): Boolean = required || when (type) {
    CapabilityParameterType.NUMBER -> defaultNumber != null
    CapabilityParameterType.BOOLEAN -> defaultBoolean != null
    CapabilityParameterType.TEXT,
    CapabilityParameterType.ENUM,
    -> defaultText != null
}

private fun routineSupportsAutonomous(
    routineId: String,
    routines: Map<String, RoutineDocument>,
    actions: Map<String, ActionDescriptor>,
    visited: MutableSet<String>,
): Boolean {
    if (!visited.add(routineId)) return true
    val supported = routines[routineId]?.steps.orEmpty().all { step ->
        stepSupportsAutonomous(step, routines, actions, visited)
    }
    visited.remove(routineId)
    return supported
}

private fun stepSupportsAutonomous(
    step: RoutineStep,
    routines: Map<String, RoutineDocument>,
    actions: Map<String, ActionDescriptor>,
    visited: MutableSet<String>,
): Boolean {
    val directKeys = buildList {
        step.actionKey?.let(::add)
        step.drive?.let { drive ->
            drive.markers.forEach { add(it.actionKey) }
            addAll(drive.duringActionKeys)
            addAll(drive.arrivalActionKeys)
        }
    }
    if (directKeys.any { CapabilityContext.AUTONOMOUS !in actions.getValue(it).allowedContexts }) return false
    val routineId = step.routineId
    if (routineId != null && !routineSupportsAutonomous(routineId, routines, actions, visited)) return false
    return step.deadline?.let { stepSupportsAutonomous(it, routines, actions, visited) } != false &&
        step.children.all { stepSupportsAutonomous(it, routines, actions, visited) } &&
        step.elseChildren.all { stepSupportsAutonomous(it, routines, actions, visited) }
}

internal fun exclusiveResourceKeys(descriptor: ActionDescriptor): List<String> =
    descriptor.resources.filter { it.access == ResourceAccess.EXCLUSIVE }.map { it.resourceKey }

private val GENERATED_REGISTRY_FQN = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
internal val ANALOG_SOURCE_KINDS = setOf(ControlSourceKind.AXIS_VALUE, ControlSourceKind.AXIS_ZONE)
