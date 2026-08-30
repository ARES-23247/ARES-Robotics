package com.areslib.codegen

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.catalog.CapabilityContext
import com.areslib.catalog.ResourceAccess
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.catalog.CatalogValidationSeverity
import com.areslib.catalog.ConditionDescriptor
import com.areslib.catalog.validateCapabilityCatalog
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControlThresholdDirection
import com.areslib.controls.ControlValidationContext
import com.areslib.controls.ControlValidationSeverity
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.DriveAxisKeys
import com.areslib.controls.ControllerProfileCodec
import com.areslib.controls.ControllerProfileDocument
import com.areslib.controls.RoutineInvocationPolicy
import com.areslib.controls.learnedControlIds
import com.areslib.controls.validateControlScheme
import com.areslib.controls.validateControllerProfile
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.CapabilityArgumentReader
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind
import com.areslib.routine.RoutineValidationContext
import com.areslib.routine.RoutineValidationSeverity
import com.areslib.routine.validateRoutineSet
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.compiler.RobotProjectIr
import com.areslib.project.requireFtcRuntimeOptions
import com.areslib.project.validateAresProjectMetadata
import com.areslib.subsystem.SubsystemTargetCapability
import com.areslib.subsystem.subsystemTargetCapabilities
import java.security.MessageDigest

/** Generator format version embedded in every emitted Kotlin source file. */
const val ARES_KOTLIN_CODEGEN_VERSION: Int = 8

/** Renderer layout applied only after an effective project has been lowered to typed compiler IR. */
data class KotlinProjectCompilationRequest(
    val project: RobotProjectIr,
    val packageName: String,
    val objectName: String = "GeneratedAresProject",
    val registryInterfaceName: String = "GeneratedAresProjectCapabilities",
    /** Fully-qualified generated registry that creates the corresponding Redux tasks. */
    val subsystemRegistryFqn: String? = null,
    /** Parameterless action keys implemented by generated orchestration registries. */
    val generatedActionRegistryBindings: Map<String, String> = emptyMap(),
)

/** Internal renderer input retained while focused renderers are extracted from the legacy file. */
internal data class KotlinProjectCodegenRequest(
    val packageName: String,
    val objectName: String = "GeneratedAresProject",
    val registryInterfaceName: String = "GeneratedAresProjectCapabilities",
    val catalog: CapabilityCatalogDocument,
    val routines: Collection<RoutineDocument>,
    val autonomousCatalog: AutonomousCatalogDocument? = null,
    val controlSchemes: Collection<ControlSchemeDocument> = emptyList(),
    val controllerProfiles: Collection<ControllerProfileDocument> = emptyList(),
    /** Robot-side InputFrame adapter whose learned HID indexes must be emitted. */
    val targetInputPlatform: ControllerInputPlatform? = null,
    /** Canonical project geometry. Build-time CLI projects require `.ares/project.json`. */
    val projectMetadata: AresProjectMetadataDocument? = null,
    /** Target setters derived from subsystem documents rather than hand-authored catalog entries. */
    val subsystemActions: Collection<SubsystemTargetCapability> = emptyList(),
    /** Fully-qualified generated registry that creates the corresponding Redux tasks. */
    val subsystemRegistryFqn: String? = null,
    /** Parameterless action keys implemented by generated orchestration registries. */
    val generatedActionRegistryBindings: Map<String, String> = emptyMap(),
)

/** Generated source and the hashes a build can use to verify deterministic disposable output. */
data class GeneratedKotlinSource(
    val source: String,
    val contentHash: String,
    val sourceHash: String
)

/**
 * Emits deterministic Kotlin without reflection or runtime file discovery.
 *
 * The generated registry is deliberately typed: numeric catalog parameters become [Double],
 * booleans become [Boolean], and text/enum parameters become [String]. Optional parameters with no
 * default become nullable. Dispatch stays key-based at the serialized boundary but every branch is
 * generated as a closed `when`, so an unknown capability can never fall through to arbitrary code.
 */
object AresKotlinProjectGenerator {
    fun generate(request: KotlinProjectCompilationRequest): GeneratedKotlinSource {
        val project = request.project
        return generate(
            KotlinProjectCodegenRequest(
                packageName = request.packageName,
                objectName = request.objectName,
                registryInterfaceName = request.registryInterfaceName,
                catalog = project.capabilityCatalog,
                routines = project.routines.map { it.document },
                autonomousCatalog = project.autonomousCatalog,
                controlSchemes = project.controlSchemes.map { it.document },
                controllerProfiles = project.controllerProfiles.map { it.document },
                targetInputPlatform = project.inputPlatform,
                projectMetadata = project.metadata,
                subsystemActions = subsystemTargetCapabilities(project.subsystems.map { it.document }),
                subsystemRegistryFqn = request.subsystemRegistryFqn,
                generatedActionRegistryBindings = request.generatedActionRegistryBindings,
            )
        )
    }

    internal fun generate(request: KotlinProjectCodegenRequest): GeneratedKotlinSource {
        validateRequest(request)
        val canonicalRoutines = request.routines.sortedBy { it.documentId }
        val canonicalActions = request.catalog.actions.sortedBy { it.key }
        val canonicalConditions = request.catalog.conditions.sortedBy { it.key }
        val subsystemActionKeys = request.subsystemActions.mapTo(linkedSetOf()) { it.descriptor.key }
        val generatedActionKeys = request.generatedActionRegistryBindings.keys
        val actionMethods = assignMethodNames("action", (subsystemActionKeys + generatedActionKeys).sorted())
        val continuousActionKeys = request.controlSchemes
            .asSequence()
            .flatMap { it.bindings.asSequence() }
            .filter { it.enabled && it.source.kind in ANALOG_SOURCE_KINDS }
            .filter { it.target.kind == ControlTargetKind.ACTION }
            .map { it.target.key }
            .filterNot(subsystemActionKeys::contains)
            .distinct()
            .sorted()
            .toList()
        val continuousActionMethods = assignMethodNames("controlAction", continuousActionKeys)
        val contentHash = contentHash(request, canonicalRoutines)

        val template = buildString {
            append("@file:Suppress(\"MagicNumber\", \"LongMethod\")\n\n")
            append("package ${request.packageName}\n\n")
            append("import com.areslib.routine.CapabilityArgumentReader\n")
            append("import com.areslib.routine.AutonomousCatalogEntry\n")
            append("import com.areslib.routine.RoutineDocument\n")
            append("import com.areslib.routine.RoutineDriveMarker\n")
            append("import com.areslib.routine.RoutineDriveStep\n")
            append("import com.areslib.routine.RoutinePose\n")
            append("import com.areslib.routine.RoutineRuntimeBindings\n")
            append("import com.areslib.routine.RoutineStep\n")
            append("import com.areslib.routine.RoutineStepKind\n")
            append("import com.areslib.routine.RoutineManager\n")
            append("import com.areslib.input.ControllerBindingRuntime\n")
            append("import com.areslib.runtime.GeneratedControlTaskSink\n")
            append("import com.areslib.runtime.GeneratedProjectDefinition\n")
            if (request.controlSchemes.isNotEmpty()) {
                append("import com.areslib.routine.RoutineStartPolicy\n")
                append("import com.areslib.input.AnalogBinding\n")
                append("import com.areslib.input.AnalogBindingListener\n")
                append("import com.areslib.input.AnalogEmissionPolicy\n")
                append("import com.areslib.input.AnalogZone\n")
                append("import com.areslib.input.AnalogZoneListener\n")
                append("import com.areslib.input.AxisThresholdSource\n")
                append("import com.areslib.input.AxisTransform\n")
                append("import com.areslib.input.BindingReleaseReason\n")
                append("import com.areslib.input.ButtonSuppressionState\n")
                append("import com.areslib.input.ChordSource\n")
                append("import com.areslib.input.DigitalBinding\n")
                append("import com.areslib.input.DigitalBindingListener\n")
                append("import com.areslib.input.DigitalBindingTiming\n")
                append("import com.areslib.input.RawButtonSource\n")
                append("import com.areslib.input.SuppressibleButtonSource\n")
                append("import com.areslib.input.SuppressingButtonChordSource\n")
                append("import com.areslib.input.ThresholdDirection\n")
            }
            append("import com.areslib.sequencer.Task\n")
            append("import com.areslib.state.RobotState\n\n")
            append(renderRegistryInterface(
                request,
                canonicalActions,
                actionMethods,
                continuousActionMethods,
                request.subsystemActions.associateBy { it.descriptor.key },
            ))
            append('\n')
            append("/** Generated from the project's canonical ARES documents. Do not edit by hand. */\n")
            append("object ${request.objectName} {\n")
            append("    const val GENERATOR_VERSION: Int = $ARES_KOTLIN_CODEGEN_VERSION\n")
            append("    const val CATALOG_SHA256: String = ${stringLiteral(CapabilityCatalogCodec.contentHash(request.catalog))}\n")
            append("    const val CONTENT_SHA256: String = ${stringLiteral(contentHash)}\n")
            append("    const val SOURCE_SHA256: String = ${stringLiteral(SOURCE_HASH_PLACEHOLDER)}\n\n")
            request.projectMetadata?.let { metadata ->
                append("    const val PROJECT_ID: String = ${stringLiteral(metadata.projectId)}\n")
                append("    const val PROJECT_LEAGUE: String = ${stringLiteral(metadata.league.name)}\n")
                append("    const val COORDINATE_CONVENTION: String = ${stringLiteral(metadata.coordinateConvention.name)}\n")
                append("    const val ROBOT_LENGTH_METERS: Double = ${doubleLiteral(metadata.robotLengthMeters)}\n")
                append("    const val ROBOT_WIDTH_METERS: Double = ${doubleLiteral(metadata.robotWidthMeters)}\n")
                append("    const val FIELD_LENGTH_METERS: Double = ${doubleLiteral(metadata.fieldLengthMeters)}\n")
                append("    const val FIELD_WIDTH_METERS: Double = ${doubleLiteral(metadata.fieldWidthMeters)}\n\n")
                val ftcRuntime = metadata.requireFtcRuntimeOptions()
                append("    /** Canonical runtime choices reviewed in .ares/project.json. */\n")
                append("    object RuntimeOptions {\n")
                append("        const val FTC_HUB_COMMAND_TRANSPORT: String = ${stringLiteral(ftcRuntime.hubCommandTransport.name)}\n")
                append("        const val FTC_LIMELIGHT_PROXY_ENABLED: Boolean = ${ftcRuntime.limelightProxyEnabled}\n")
                append("    }\n\n")
            }
            append("    val knownActionKeys: Set<String> = ${renderStringSet(canonicalActions.map { it.key }, 1)}\n")
            append("    val knownConditionKeys: Set<String> = ${renderStringSet(canonicalConditions.map { it.key }, 1)}\n\n")
            append("    val routines: Map<String, RoutineDocument> = linkedMapOf(")
            if (canonicalRoutines.isEmpty()) {
                append(")\n\n")
            } else {
                append('\n')
                canonicalRoutines.forEach { routine ->
                    append("        ${stringLiteral(routine.documentId)} to ")
                    append(RoutineKotlinRenderer.renderRoutine(routine, 2))
                    append(",\n")
                }
                append("    )\n\n")
            }
            val autonomousCatalog = request.autonomousCatalog
            append("    val autonomousEntries: List<AutonomousCatalogEntry> = ")
            if (autonomousCatalog == null || autonomousCatalog.entries.isEmpty()) {
                append("emptyList()\n")
            } else {
                append("listOf(\n")
                autonomousCatalog.entries
                    .sortedWith(compareBy<AutonomousCatalogEntry> { it.sortOrder }.thenBy { it.entryId })
                    .forEach { entry ->
                        append("        ${RoutineKotlinRenderer.renderAutonomousEntry(entry, 2)},\n")
                    }
                append("    )\n")
            }
            append(
                "    val DEFAULT_AUTONOMOUS_ENTRY_ID: String? = " +
                    "${renderNullableString(autonomousCatalog?.defaultEntryId)}\n\n"
            )
            append(renderRuntimeBindings(request, canonicalActions, canonicalConditions, actionMethods))
            append('\n')
            append(renderControlRuntimes(request, actionMethods, continuousActionMethods))
            append('\n')
            append("    /** Stable platform-neutral runtime boundary consumed by FTC/FRC league hosts. */\n")
            append("    val runtimeDefinition = GeneratedProjectDefinition(\n")
            append("        defaultControlSchemeId = DEFAULT_CONTROL_SCHEME_ID,\n")
            append("        contentSha256 = CONTENT_SHA256,\n")
            append("        hasGeneratedDriveBindings = HAS_GENERATED_DRIVE_BINDINGS,\n")
            append("        routines = routines,\n")
            append("        runtimeBindings = ::runtimeBindings,\n")
            append("        createControllerRuntimes = ::createControllerRuntimes,\n")
            append("        emitDriveCommand = ::emitDriveCommand,\n")
            append("    )\n")
            append("}\n")
        }
        val sourceHash = sha256(template)
        val source = template.replace(
            "const val SOURCE_SHA256: String = ${stringLiteral(SOURCE_HASH_PLACEHOLDER)}",
            "const val SOURCE_SHA256: String = ${stringLiteral(sourceHash)}"
        )
        check(calculateEmbeddedSourceHash(source) == sourceHash) { "Generated source hash is not self-consistent" }
        return GeneratedKotlinSource(source, contentHash, sourceHash)
    }

    /** Recomputes the hash after replacing the embedded hash value with its canonical marker. */
    fun calculateEmbeddedSourceHash(source: String): String {
        val matches = SOURCE_HASH_DECLARATION.findAll(source).toList()
        require(matches.size == 1) { "Generated source must contain exactly one SOURCE_SHA256 declaration" }
        val normalized = source.replaceRange(
            matches.single().range,
            "const val SOURCE_SHA256: String = ${stringLiteral(SOURCE_HASH_PLACEHOLDER)}"
        )
        return sha256(normalized)
    }

    fun hasValidEmbeddedSourceHash(source: String): Boolean {
        val match = SOURCE_HASH_DECLARATION.find(source) ?: return false
        val embedded = match.groupValues[1]
        return embedded.matches(SHA_256_REGEX) && runCatching {
            calculateEmbeddedSourceHash(source) == embedded
        }.getOrDefault(false)
    }

    private fun validateRequest(request: KotlinProjectCodegenRequest) {
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
            require(registryFqn.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))) {
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
        // Only EXCLUSIVE claims participate in concurrent-resource arbitration. Flattening READ
        // claims in too made document validation reject routines that parallelize, say, a shot
        // feed (READ on flywheel/cowl) with flywheel spin-up — a conflict the runtime bitmask
        // model correctly allows.
        val resources = actions.mapValues { (_, descriptor) -> exclusiveResourceKeys(descriptor).toSet() }
        val routineContext = RoutineValidationContext(
            hasAction = actions::containsKey,
            hasCondition = conditions::containsKey,
            resourcesForAction = { resources[it].orEmpty() }
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
            val errors = com.areslib.routine.validateAutonomousCatalog(autonomousCatalog, routineIds)
                .filter { it.severity == RoutineValidationSeverity.ERROR }
            require(errors.isEmpty()) {
                errors.joinToString(separator = "; ") { "${it.path}: ${it.message}" }
            }
            autonomousCatalog.entries.filter { it.enabled }.forEach { entry ->
                require(
                    routineSupportsContext(
                        entry.routineId,
                        request.routines.associateBy { it.documentId },
                        actions,
                        mutableSetOf()
                    )
                ) {
                    "Autonomous entry '${entry.entryId}' reaches an action that is not allowed in autonomous"
                }
            }
        }
        validateControls(request, actions, routineIds)
        val subsystemActionKeySet = subsystemActionKeys.toSet()
        val unsafeAnalogSubsystemBindings = request.controlSchemes.asSequence()
            .flatMap { it.bindings.asSequence() }
            .filter { it.enabled && it.source.kind in ANALOG_SOURCE_KINDS }
            .filter { it.target.kind == ControlTargetKind.ACTION && it.target.key in subsystemActionKeySet }
            .filter { binding ->
                val policy = binding.analogPolicy
                policy == null || !policy.emitOnlyOnChange || policy.changeEpsilon <= 0.0
            }
            .map { it.bindingId }
            .toList()
        require(unsafeAnalogSubsystemBindings.isEmpty()) {
            "Generated subsystem analog bindings must emit only on meaningful changes so Redux tasks cannot flood the robot loop: " +
                unsafeAnalogSubsystemBindings.joinToString()
        }
    }

    private fun validateStepArguments(
        steps: List<RoutineStep>,
        actions: Map<String, ActionDescriptor>,
        conditions: Map<String, ConditionDescriptor>,
        path: String,
        issues: MutableList<com.areslib.routine.RoutineValidationIssue>
    ) {
        steps.forEachIndexed { index, step ->
            val stepPath = "$path[$index]"
            when (step.kind) {
                RoutineStepKind.ACTION -> validateArguments(
                    descriptorKey = requireNotNull(step.actionKey),
                    parameters = actions[step.actionKey]?.parameters.orEmpty(),
                    arguments = step.arguments,
                    path = stepPath,
                    issues = issues
                )
                RoutineStepKind.WAIT_UNTIL,
                RoutineStepKind.BRANCH -> validateArguments(
                    descriptorKey = requireNotNull(step.conditionKey),
                    parameters = conditions[step.conditionKey]?.parameters.orEmpty(),
                    arguments = step.arguments,
                    path = stepPath,
                    issues = issues
                )
                RoutineStepKind.DRIVE_TO -> step.drive?.let { drive ->
                    val actionKeys = drive.markers.map { it.actionKey } +
                        drive.duringActionKeys + drive.arrivalActionKeys
                    actionKeys.forEach { actionKey ->
                        validateArguments(
                            descriptorKey = actionKey,
                            parameters = actions[actionKey]?.parameters.orEmpty(),
                            arguments = emptyMap(),
                            path = "$stepPath.drive[$actionKey]",
                            issues = issues
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
        issues: MutableList<com.areslib.routine.RoutineValidationIssue>
    ) {
        try {
            val reader = CapabilityArgumentReader(descriptorKey, arguments, parameters.mapTo(mutableSetOf()) { it.key })
            parameters.forEach { parameter -> reader.read(parameter) }
        } catch (error: IllegalArgumentException) {
            issues += com.areslib.routine.RoutineValidationIssue(
                RoutineValidationSeverity.ERROR,
                path.substringBefore('.'),
                path,
                "invalid_capability_arguments",
                error.message ?: "Capability arguments are invalid"
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

    private fun contentHash(request: KotlinProjectCodegenRequest, routines: List<RoutineDocument>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun record(label: String, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(label.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(bytes)
        }
        record("generator", ARES_KOTLIN_CODEGEN_VERSION.toString())
        record("subsystem-registry", request.subsystemRegistryFqn.orEmpty())
        request.generatedActionRegistryBindings.toSortedMap().forEach { (key, registry) ->
            record("generated-action:$key", registry)
        }
        request.projectMetadata?.let { record("project-metadata", AresProjectMetadataCodec.encode(it)) }
        record("catalog", CapabilityCatalogCodec.encode(request.catalog))
        routines.forEach { record("routine:${it.documentId}", AresRoutineCodec.encode(it)) }
        request.autonomousCatalog?.let { record("autonomous-catalog", AutonomousCatalogCodec.encode(it)) }
        request.controllerProfiles.sortedBy { it.documentId }.forEach {
            record("controller-profile:${it.documentId}", ControllerProfileCodec.encode(it))
        }
        record("controller-input-platform", request.targetInputPlatform?.name ?: "none")
        request.controlSchemes.sortedBy { it.documentId }.forEach {
            record("control-scheme:${it.documentId}", ControlSchemeCodec.encode(it))
        }
        return digest.digest().toHex()
    }

    private fun renderRegistryInterface(
        request: KotlinProjectCodegenRequest,
        actions: List<ActionDescriptor>,
        actionMethods: Map<String, String>,
        continuousActionMethods: Map<String, String>,
        subsystemActions: Map<String, SubsystemTargetCapability>,
    ): String = buildString {
        append("/** Stable robot boundary for capabilities referenced by generated project documents. */\n")
        append("interface ${request.registryInterfaceName} {\n")
        append("    /** Creates a hand-authored or season action by its catalog key, or null when unavailable. */\n")
        append("    fun createActionTask(actionKey: String, arguments: Map<String, String>): Task? = null\n\n")
        actions.filter { it.key in actionMethods }.forEach { descriptor ->
            append("    /** Implements action key ${descriptor.key}. */\n")
            append("    fun ${actionMethods.getValue(descriptor.key)}(")
            append(renderSignatureParameters(descriptor.parameters))
            if (descriptor.key in subsystemActions) {
                val registry = requireNotNull(request.subsystemRegistryFqn)
                append("): Task = requireNotNull($registry.createActionTask(")
                append(stringLiteral(descriptor.key))
                if (descriptor.parameters.isEmpty()) {
                    append(", null)) { ")
                } else {
                    require(descriptor.parameters.size == 1 && descriptor.parameters.single().key == "value") {
                        "Generated subsystem action '${descriptor.key}' must be parameterless or expose one 'value' parameter"
                    }
                    val valueName = assignParameterNames(descriptor.parameters).getValue("value")
                    append(", $valueName)) { ")
                }
                append(stringLiteral("Generated subsystem action '${descriptor.key}' rejected its value"))
                append(" }\n\n")
            } else {
                val registry = requireNotNull(request.generatedActionRegistryBindings[descriptor.key])
                append("): Task = requireNotNull($registry.createActionTask(")
                append(stringLiteral(descriptor.key))
                append(")) { ")
                append(stringLiteral("Generated orchestration action '${descriptor.key}' is unavailable"))
                append(" }\n\n")
            }
        }
        actions.filter { it.key in continuousActionMethods }.forEach { descriptor ->
            append("    /** Allocation-free continuous control for action key ${descriptor.key}. */\n")
            append("    fun ${continuousActionMethods.getValue(descriptor.key)}(")
            append(renderSignatureParameters(descriptor.parameters))
            append("): Unit\n\n")
        }
        append("    /**\n")
        append("     * Receives the combined teleop drivetrain command once per frame. Values are normalized\n")
        append("     * (-1..1) after each axis binding's transform, field-centric with CCW-positive rotation;\n")
        append("     * the implementation scales them by drivetrain limits and applies alliance mirroring.\n")
        append("     * [active] is false when the scheme has no drive bindings, so sinks without generated\n")
        append("     * drivetrain control stay inert.\n")
        append("     */\n")
        append("    fun onDriveCommand(vx: Double, vy: Double, omega: Double, active: Boolean) = Unit\n\n")
        append("    /** Creates a hand-authored condition predicate by its catalog key, or null when unavailable. */\n")
        append("    fun createCondition(conditionKey: String, arguments: Map<String, String>): ((RobotState) -> Boolean)? = null\n\n")
        append("    /** Platform trajectory adapter; returning null rejects a drive step safely. */\n")
        append("    fun createDriveTask(step: RoutineDriveStep): Task? = null\n")
        append("}\n")
    }

    private fun renderSignatureParameters(parameters: List<CapabilityParameterDescriptor>): String =
        assignParameterNames(parameters).let { names ->
            parameters.sortedBy { it.key }.joinToString(", ") { parameter ->
                "${names.getValue(parameter.key)}: ${parameter.kotlinType()}"
            }
        }

    private fun renderRuntimeBindings(
        request: KotlinProjectCodegenRequest,
        actions: List<ActionDescriptor>,
        conditions: List<ConditionDescriptor>,
        actionMethods: Map<String, String>,
    ): String = buildString {
        append("    fun runtimeBindings(registry: ${request.registryInterfaceName}): RoutineRuntimeBindings =\n")
        append("        RoutineRuntimeBindings(\n")
        if (actions.isEmpty()) {
            append("            createActionTask = { _, _ -> null },\n")
        } else {
            append("            createActionTask = { key, arguments ->\n")
            append("                when (key) {\n")
            actions.forEach { descriptor ->
                append("                    ${stringLiteral(descriptor.key)} -> {\n")
                append("                        ")
                if (descriptor.parameters.isNotEmpty()) append("val parsed = ")
                append("CapabilityArgumentReader(\n")
                append("                            capabilityKey = ${stringLiteral(descriptor.key)},\n")
                append("                            arguments = arguments,\n")
                append("                            allowedKeys = ${renderStringSet(descriptor.parameters.map { it.key }, 7)},\n")
                append("                        )\n")
                if (descriptor.key in actionMethods) {
                    append("                        registry.${actionMethods.getValue(descriptor.key)}(")
                    append(renderInvocationArguments(descriptor.parameters, 6))
                    append(")\n")
                } else {
                    descriptor.parameters.sortedBy { it.key }.forEach { parameter ->
                        append("                        ${renderArgumentRead(parameter)}\n")
                    }
                    append("                        registry.createActionTask(key, arguments)\n")
                }
                append("                    }\n")
            }
            append("                    else -> null\n")
            append("                }\n")
            append("            },\n")
        }
        if (conditions.isEmpty()) {
            append("            createCondition = { _, _ -> null },\n")
        } else {
            append("            createCondition = { key, arguments ->\n")
            append("                when (key) {\n")
            conditions.forEach { descriptor ->
                append("                    ${stringLiteral(descriptor.key)} -> {\n")
                append("                        ")
                if (descriptor.parameters.isNotEmpty()) append("val parsed = ")
                append("CapabilityArgumentReader(\n")
                append("                            capabilityKey = ${stringLiteral(descriptor.key)},\n")
                append("                            arguments = arguments,\n")
                append("                            allowedKeys = ${renderStringSet(descriptor.parameters.map { it.key }, 7)},\n")
                append("                        )\n")
                descriptor.parameters.sortedBy { it.key }.forEach { parameter ->
                    append("                        ${renderArgumentRead(parameter)}\n")
                }
                append("                        registry.createCondition(key, arguments)\n")
                append("                    }\n")
            }
            append("                    else -> null\n")
            append("                }\n")
            append("            },\n")
        }
        append("            createDriveTask = registry::createDriveTask,\n")
        append("            isActionKnown = knownActionKeys::contains,\n")
        append("            isConditionKnown = knownConditionKeys::contains,\n")
        if (actions.isEmpty()) {
            append("            resourcesForAction = { emptySet() },\n")
        } else {
            append("            resourcesForAction = { key ->\n")
            append("                when (key) {\n")
            actions.forEach { descriptor ->
                append("                    ${stringLiteral(descriptor.key)} -> ")
                append(renderStringSet(exclusiveResourceKeys(descriptor), 5))
                append('\n')
            }
            append("                    else -> emptySet()\n")
            append("                }\n")
            append("            },\n")
        }
        append("        )\n")
    }

    private fun renderControlRuntimes(
        request: KotlinProjectCodegenRequest,
        actionMethods: Map<String, String>,
        continuousActionMethods: Map<String, String>,
    ): String = buildString {
        val schemes = request.controlSchemes.sortedBy { it.documentId }
        val profiles = request.controllerProfiles.associateBy { it.documentId }
        val platform = request.targetInputPlatform
        check(schemes.isEmpty() || platform != null)
        val actions = request.catalog.actions.associateBy { it.key }
        append("    val knownControlSchemeIds: Set<String> = ")
        append(renderStringSet(schemes.map { it.documentId }, 1))
        append("\n")
        append("    val DEFAULT_CONTROL_SCHEME_ID: String? = ")
        append(renderNullableString(schemes.singleOrNull()?.documentId))
        append("\n\n")
        val hasDriveBindings = schemes.any { scheme -> scheme.bindings.any { it.enabled && it.target.kind == ControlTargetKind.DRIVE } }
        append("    /** True when the active scheme binds at least one drivetrain axis. */\n")
        append("    val HAS_GENERATED_DRIVE_BINDINGS: Boolean = $hasDriveBindings\n")
        append("    private val driveAxisValues = DoubleArray(3)\n\n")
        append("    /**\n")
        append("     * Publishes the latest drive-axis listener values as one combined command. Disconnects emit\n")
        append("     * zeros and the analog rearm policy holds that neutral until every axis passes through its\n")
        append("     * deadband, so a deflected stick cannot lurch the robot across a controller reconnect.\n")
        append("     */\n")
        append("    fun emitDriveCommand(registry: ${request.registryInterfaceName}) {\n")
        append("        registry.onDriveCommand(driveAxisValues[0], driveAxisValues[1], driveAxisValues[2], HAS_GENERATED_DRIVE_BINDINGS)\n")
        append("    }\n\n")
        append("    /**\n")
        append("     * Builds one allocation-free update runtime per zero-based Driver Station port. Suppressing chords are\n")
        append("     * ordered before constituent buttons and raise their effective press debounce to the chord\n")
        append("     * window, preventing a near-simultaneous chord from leaking a single-button action.\n")
        append("     */\n")
        append("    @Suppress(\"UNUSED_PARAMETER\")\n")
        append("    fun createControllerRuntimes(\n")
        append("        schemeId: String?,\n")
        append("        registry: ${request.registryInterfaceName},\n")
        append("        routineManager: RoutineManager,\n")
        append("        taskSink: GeneratedControlTaskSink,\n")
        append("    ): Map<Int, ControllerBindingRuntime> {\n")
        if (schemes.isEmpty()) {
            append("        require(schemeId == null) { \"This project has no generated control scheme\" }\n")
            append("        return emptyMap()\n")
            append("    }\n")
            return@buildString
        }
        append("        val activeSchemeId = requireNotNull(schemeId) { \"A generated control scheme is required\" }\n")
        append("        return when (activeSchemeId) {\n")
        schemes.forEach { scheme ->
            val enabledBindings = scheme.bindings.filter { it.enabled }
            val suppressors = enabledBindings
                .filter { it.source.kind == ControlSourceKind.CHORD && it.suppressConstituentBindings }
                .sortedWith(compareByDescending<ControlBindingDocument> { it.priority }.thenBy { it.bindingId })
            val suppressorNames = suppressors.associate { it.bindingId to suppressorVariableName(it.bindingId) }
            append("        ${stringLiteral(scheme.documentId)} -> run {\n")
            val suppressionStateNames = scheme.controllers.associate { controller ->
                controller.slot to suppressionStateVariableName(controller.slot)
            }
            scheme.controllers.sortedWith(compareBy({ requireNotNull(it.devicePort) }, { it.slot })).forEach { controller ->
                val profile = profiles.getValue(controller.profileId)
                val maximumIndex = profile.controls.mapNotNull { control ->
                    control.mappings.firstOrNull { it.platform == platform }?.buttonIndex
                }.maxOrNull() ?: -1
                append(
                    "            val ${suppressionStateNames.getValue(controller.slot)} = " +
                        "ButtonSuppressionState(buttonCapacity = ${maxOf(128, maximumIndex + 1)})\n"
                )
            }
            suppressors.forEach { binding ->
                val controller = scheme.controllers.first { it.slot == binding.source.controllerSlot }
                val profile = profiles.getValue(controller.profileId)
                val indexes = binding.source.controlIds.map { profile.control(it).requiredButtonIndex(requireNotNull(platform)) }
                append("            val ${suppressorNames.getValue(binding.bindingId)} = SuppressingButtonChordSource(\n")
                append("                buttonIndexes = intArrayOf(${indexes.joinToString()}),\n")
                append("                simultaneityWindowNanos = ${secondsToNanos(binding.source.chordWindowSeconds)}L,\n")
                append("                suppression = ${suppressionStateNames.getValue(binding.source.controllerSlot)},\n")
                append("            )\n")
            }
            if (suppressors.isNotEmpty()) append('\n')
            append("            linkedMapOf(\n")
            scheme.controllers.sortedWith(compareBy({ requireNotNull(it.devicePort) }, { it.slot })).forEach { controller ->
                val profile = profiles.getValue(controller.profileId)
                val bindings = enabledBindings
                    .filter { it.source.controllerSlot == controller.slot }
                    .sortedWith(
                        compareByDescending<ControlBindingDocument> {
                            it.source.kind == ControlSourceKind.CHORD && it.suppressConstituentBindings
                        }.thenByDescending { it.priority }.thenBy { it.bindingId }
                    )
                val digital = bindings.filter { it.source.kind in DIGITAL_SOURCE_KINDS }
                val analog = bindings.filter { it.source.kind in ANALOG_SOURCE_KINDS }
                val slotSuppressors = suppressors.filter { it.source.controllerSlot == controller.slot }
                append("                ${requireNotNull(controller.devicePort)} to ControllerBindingRuntime(\n")
                append("                    digitalBindings = ${renderDigitalBindingList(digital, slotSuppressors, suppressorNames, suppressionStateNames.getValue(controller.slot), profile, requireNotNull(platform), actions, actionMethods, 5)},\n")
                append("                    analogBindings = ${renderAnalogBindingList(analog, profile, requireNotNull(platform), actions, actionMethods, continuousActionMethods, 5)},\n")
                append("                ),\n")
            }
            append("            )\n")
            append("        }\n")
        }
        append("            else -> throw IllegalArgumentException(\"Unknown control scheme '\$activeSchemeId'\")\n")
        append("        }\n")
        append("    }\n")
    }

    private fun renderDigitalBindingList(
        bindings: List<ControlBindingDocument>,
        suppressors: List<ControlBindingDocument>,
        suppressorNames: Map<String, String>,
        suppressionStateName: String,
        profile: ControllerProfileDocument,
        platform: ControllerInputPlatform,
        actions: Map<String, ActionDescriptor>,
        actionMethods: Map<String, String>,
        indent: Int
    ): String {
        if (bindings.isEmpty()) return "emptyList()"
        return buildString {
            append("listOf(\n")
            bindings.forEach { binding ->
                val constituentSuppressors = if (binding.source.kind == ControlSourceKind.BUTTON) {
                    suppressors.filter { suppressor ->
                        binding.source.controlIds.single() in suppressor.source.controlIds
                    }
                } else {
                    emptyList()
                }
                appendIndent(indent + 1, "DigitalBinding(\n")
                appendIndent(
                    indent + 2,
                    "source = ${renderDigitalSource(binding, constituentSuppressors, suppressorNames, suppressionStateName, profile, platform, indent + 2)},\n"
                )
                val minimumDebounce = constituentSuppressors.maxOfOrNull { it.source.chordWindowSeconds } ?: 0.0
                appendIndent(indent + 2, "timing = ${renderDigitalTiming(binding, minimumDebounce, indent + 2)},\n")
                appendIndent(indent + 2, "listener = object : DigitalBindingListener {\n")
                val statement = renderControlTarget(binding, actions, actionMethods, valueExpression = null)
                when (binding.event) {
                    ControlEvent.PRESS -> appendListenerMethod(indent + 3, "onPress()", statement)
                    ControlEvent.RELEASE -> appendListenerMethod(
                        indent + 3,
                        "onRelease(heldForNanos: Long, reason: BindingReleaseReason)",
                        "if (reason == BindingReleaseReason.INPUT_RELEASED) {\n" +
                            statement.prependIndent("    ") +
                            "\n}"
                    )
                    ControlEvent.HELD -> appendListenerMethod(indent + 3, "onHeld(heldForNanos: Long)", statement)
                    ControlEvent.HOLD -> appendListenerMethod(indent + 3, "onHold(heldForNanos: Long)", statement)
                    ControlEvent.REPEAT -> appendListenerMethod(indent + 3, "onRepeat(heldForNanos: Long)", statement)
                    else -> error("Validated digital binding has analog event ${binding.event}")
                }
                appendIndent(indent + 2, "},\n")
                appendIndent(indent + 1, "),\n")
            }
            appendIndent(indent, ")")
        }
    }

    private fun StringBuilder.appendListenerMethod(indent: Int, signature: String, statement: String) {
        appendIndent(indent, "override fun $signature {\n")
        statement.lines().forEach { appendIndent(indent + 1, "$it\n") }
        appendIndent(indent, "}\n")
    }

    private fun renderDigitalSource(
        binding: ControlBindingDocument,
        constituentSuppressors: List<ControlBindingDocument>,
        suppressorNames: Map<String, String>,
        suppressionStateName: String,
        profile: ControllerProfileDocument,
        platform: ControllerInputPlatform,
        indent: Int
    ): String {
        val source = binding.source
        return when (source.kind) {
            ControlSourceKind.BUTTON -> {
                val buttonIndex = profile.control(source.controlIds.single()).requiredButtonIndex(platform)
                if (constituentSuppressors.isEmpty()) {
                    "RawButtonSource($buttonIndex)"
                } else {
                    "SuppressibleButtonSource(\n" +
                        "    ".repeat(indent + 1) + "buttonIndex = $buttonIndex,\n" +
                        "    ".repeat(indent + 1) + "suppression = $suppressionStateName,\n" +
                        "    ".repeat(indent) + ")"
                }
            }
            ControlSourceKind.CHORD -> buildString {
                if (binding.suppressConstituentBindings) {
                    append(suppressorNames.getValue(binding.bindingId))
                } else {
                    append("ChordSource(\n")
                    appendIndent(indent + 1, "sources = listOf(\n")
                    source.controlIds.forEach { controlId ->
                        appendIndent(
                            indent + 2,
                            "RawButtonSource(${profile.control(controlId).requiredButtonIndex(platform)}),\n"
                        )
                    }
                    appendIndent(indent + 1, "),\n")
                    appendIndent(indent + 1, "simultaneityWindowNanos = ${secondsToNanos(source.chordWindowSeconds)}L,\n")
                    appendIndent(indent, ")")
                }
            }
            ControlSourceKind.AXIS_THRESHOLD -> {
                val axis = profile.control(source.controlIds.single()).requiredAxisIndex(platform)
                "AxisThresholdSource(\n" +
                    "    ".repeat(indent + 1) + "axisIndex = $axis,\n" +
                    "    ".repeat(indent + 1) + "pressThreshold = ${doubleLiteral(requireNotNull(source.pressThreshold))},\n" +
                    "    ".repeat(indent + 1) + "releaseThreshold = ${doubleLiteral(requireNotNull(source.releaseThreshold))},\n" +
                    "    ".repeat(indent + 1) + "direction = ThresholdDirection.${source.thresholdDirection.name},\n" +
                    "    ".repeat(indent + 1) + "transform = ${renderAxisTransform(source.transform, indent + 1)},\n" +
                    "    ".repeat(indent) + ")"
            }
            else -> error("Validated analog source was rendered as digital")
        }
    }

    private fun renderDigitalTiming(
        binding: ControlBindingDocument,
        minimumPressDebounceSeconds: Double,
        indent: Int
    ): String = buildString {
        val timing = binding.timing
        append("DigitalBindingTiming(\n")
        appendIndent(
            indent + 1,
            "pressDebounceNanos = ${secondsToNanos(maxOf(timing.pressDebounceSeconds, minimumPressDebounceSeconds))}L,\n"
        )
        appendIndent(indent + 1, "releaseDebounceNanos = ${secondsToNanos(timing.releaseDebounceSeconds)}L,\n")
        appendIndent(indent + 1, "holdAfterNanos = ${optionalSecondsToNanos(timing.holdAfterSeconds)}L,\n")
        appendIndent(indent + 1, "repeatAfterNanos = ${optionalSecondsToNanos(timing.repeatAfterSeconds)}L,\n")
        appendIndent(
            indent + 1,
            "repeatEveryNanos = ${timing.repeatEverySeconds?.let(::secondsToNanos) ?: 0L}L,\n"
        )
        appendIndent(indent + 1, "cooldownNanos = ${secondsToNanos(timing.cooldownSeconds)}L,\n")
        appendIndent(indent + 1, "maximumActiveNanos = ${optionalSecondsToNanos(timing.maximumActiveSeconds)}L,\n")
        appendIndent(indent, ")")
    }

    private fun renderAnalogBindingList(
        bindings: List<ControlBindingDocument>,
        profile: ControllerProfileDocument,
        platform: ControllerInputPlatform,
        actions: Map<String, ActionDescriptor>,
        actionMethods: Map<String, String>,
        continuousActionMethods: Map<String, String>,
        indent: Int
    ): String {
        if (bindings.isEmpty()) return "emptyList()"
        return buildString {
            append("listOf(\n")
            bindings.forEach { binding ->
                val source = binding.source
                val policy = requireNotNull(binding.analogPolicy)
                val axisIndex = profile.control(source.controlIds.single()).requiredAxisIndex(platform)
                appendIndent(indent + 1, "AnalogBinding(\n")
                appendIndent(indent + 2, "axisIndex = $axisIndex,\n")
                appendIndent(indent + 2, "transform = ${renderAxisTransform(source.transform, indent + 2)},\n")
                if (source.kind == ControlSourceKind.AXIS_VALUE) {
                    val listenerStatement = if (binding.target.kind == ControlTargetKind.DRIVE) {
                        "driveAxisValues[${driveAxisIndex(binding.target.key)}] = value"
                    } else {
                        renderControlTarget(binding, actions, actionMethods, "value", continuousActionMethods)
                    }
                    appendIndent(indent + 2, "listener = object : AnalogBindingListener {\n")
                    appendListenerMethod(indent + 3, "onValue(value: Double)", listenerStatement)
                    appendIndent(indent + 2, "},\n")
                    appendIndent(indent + 2, "zones = emptyList(),\n")
                } else {
                    appendIndent(indent + 2, "listener = object : AnalogBindingListener {},\n")
                    appendIndent(indent + 2, "zones = listOf(\n")
                    appendIndent(indent + 3, "AnalogZone(\n")
                    appendIndent(indent + 4, "id = ${stringLiteral(binding.bindingId)},\n")
                    appendIndent(indent + 4, "minimum = ${doubleLiteral(requireNotNull(source.zoneMinimum))},\n")
                    appendIndent(indent + 4, "maximum = ${doubleLiteral(requireNotNull(source.zoneMaximum))},\n")
                    appendIndent(indent + 4, "hysteresis = ${doubleLiteral(source.zoneHysteresis)},\n")
                    appendIndent(indent + 4, "listener = object : AnalogZoneListener {\n")
                    val statement = renderControlTarget(
                        binding,
                        actions,
                        actionMethods,
                        "value",
                        continuousActionMethods,
                    )
                    when (binding.event) {
                        ControlEvent.ZONE_ENTER -> appendListenerMethod(indent + 5, "onEnter(value: Double)", statement)
                        ControlEvent.ZONE_ACTIVE -> appendListenerMethod(indent + 5, "onActive(value: Double)", statement)
                        ControlEvent.ZONE_EXIT -> appendListenerMethod(indent + 5, "onExit(value: Double)", statement)
                        else -> error("Validated zone binding has non-zone event ${binding.event}")
                    }
                    appendIndent(indent + 4, "},\n")
                    appendIndent(indent + 3, "),\n")
                    appendIndent(indent + 2, "),\n")
                }
                appendIndent(
                    indent + 2,
                    "emissionPolicy = AnalogEmissionPolicy.${if (policy.emitOnlyOnChange) "ON_CHANGE" else "EVERY_UPDATE"},\n"
                )
                appendIndent(indent + 2, "changeEpsilon = ${doubleLiteral(policy.changeEpsilon)},\n")
                appendIndent(
                    indent + 2,
                    "riseRatePerSecond = ${policy.riseRatePerSecond?.let(::doubleLiteral) ?: "Double.POSITIVE_INFINITY"},\n"
                )
                appendIndent(
                    indent + 2,
                    "fallRatePerSecond = ${policy.fallRatePerSecond?.let(::doubleLiteral) ?: "Double.POSITIVE_INFINITY"},\n"
                )
                appendIndent(indent + 2, "rearmNeutralThreshold = ${doubleLiteral(policy.rearmNeutralThreshold)},\n")
                appendIndent(indent + 1, "),\n")
            }
            appendIndent(indent, ")")
        }
    }

    private fun renderAxisTransform(
        transform: com.areslib.controls.AxisTransformDocument?,
        indent: Int
    ): String {
        val value = transform ?: return "AxisTransform()"
        return buildString {
            append("AxisTransform(\n")
            appendIndent(indent + 1, "inputMin = ${doubleLiteral(value.inputMinimum)},\n")
            appendIndent(indent + 1, "inputCenter = ${doubleLiteral(value.inputCenter)},\n")
            appendIndent(indent + 1, "inputMax = ${doubleLiteral(value.inputMaximum)},\n")
            appendIndent(indent + 1, "deadband = ${doubleLiteral(value.deadband)},\n")
            appendIndent(indent + 1, "exponent = ${doubleLiteral(value.exponent)},\n")
            appendIndent(indent + 1, "inverted = ${value.inverted},\n")
            appendIndent(indent + 1, "outputMin = ${doubleLiteral(value.outputMinimum)},\n")
            appendIndent(indent + 1, "outputMax = ${doubleLiteral(value.outputMaximum)},\n")
            appendIndent(indent, ")")
        }
    }

    private fun renderControlTarget(
        binding: ControlBindingDocument,
        actions: Map<String, ActionDescriptor>,
        actionMethods: Map<String, String>,
        valueExpression: String?,
        continuousActionMethods: Map<String, String> = emptyMap(),
    ): String {
        val target = binding.target
        return when (target.kind) {
            ControlTargetKind.ACTION -> {
                val descriptor = actions.getValue(target.key)
                val dynamicKey = valueExpression?.let { binding.analogPolicy?.valueArgumentKey }
                if (valueExpression != null && target.key in continuousActionMethods) {
                    "registry.${continuousActionMethods.getValue(target.key)}(" +
                        renderControlActionArguments(descriptor.parameters, target.arguments, dynamicKey, valueExpression, 0) +
                        ")"
                } else {
                    val taskExpression = if (target.key in actionMethods) {
                        "registry.${actionMethods.getValue(target.key)}(" +
                            renderControlActionArguments(
                                descriptor.parameters,
                                target.arguments,
                                dynamicKey,
                                valueExpression,
                                2,
                            ) +
                            ")"
                    } else {
                        "requireNotNull(registry.createActionTask(" +
                            "${stringLiteral(target.key)}, ${renderStringMap(target.arguments, 2)}" +
                            ")) { ${stringLiteral("Generated action '${target.key}' is unavailable at runtime")} }"
                    }
                    "taskSink.submit(\n" +
                        "    bindingId = ${stringLiteral(binding.bindingId)},\n" +
                        "    task = $taskExpression,\n" +
                        ")"
                }
            }
            ControlTargetKind.ROUTINE -> when (target.routinePolicy) {
                RoutineInvocationPolicy.TOGGLE_CANCEL ->
                    "if (routineManager.cancelRoutine(${stringLiteral(target.key)}, " +
                        "${stringLiteral("Toggled by ${binding.bindingId}")}) == 0) {\n" +
                        "    routineManager.request(${stringLiteral(target.key)}, RoutineStartPolicy.RESTART_EXISTING)\n" +
                        "}"
                else -> "routineManager.request(" +
                    "${stringLiteral(target.key)}, RoutineStartPolicy.${target.routinePolicy.name})"
            }
            ControlTargetKind.CANCEL_ROUTINE ->
                "routineManager.cancelRoutine(${stringLiteral(target.key)}, " +
                    "${stringLiteral("Cancelled by ${binding.bindingId}")})"
            ControlTargetKind.DRIVE ->
                error("Validated drive-axis binding '${binding.bindingId}' must render through the analog accumulator")
        }
    }

    private fun driveAxisIndex(key: String): Int = when (key) {
        DriveAxisKeys.VX -> 0
        DriveAxisKeys.VY -> 1
        DriveAxisKeys.OMEGA -> 2
        else -> error("Validated drive axis key '$key' has no accumulator index")
    }

    private fun renderControlActionArguments(
        parameters: List<CapabilityParameterDescriptor>,
        arguments: Map<String, String>,
        dynamicKey: String?,
        valueExpression: String?,
        indent: Int
    ): String {
        val sorted = parameters.sortedBy { it.key }
        if (sorted.isEmpty()) return ""
        val names = assignParameterNames(parameters)
        return buildString {
            append('\n')
            sorted.forEach { parameter ->
                val value = if (parameter.key == dynamicKey) {
                    requireNotNull(valueExpression)
                } else {
                    renderSerializedParameter(parameter, arguments[parameter.key])
                }
                append("    ".repeat(indent + 1))
                append("${names.getValue(parameter.key)} = $value,\n")
            }
            append("    ".repeat(indent))
        }
    }

    private fun renderSerializedParameter(parameter: CapabilityParameterDescriptor, raw: String?): String {
        if (raw == null) {
            return when (parameter.type) {
                CapabilityParameterType.NUMBER -> parameter.defaultNumber?.let(::doubleLiteral) ?: "null"
                CapabilityParameterType.BOOLEAN -> parameter.defaultBoolean?.toString() ?: "null"
                CapabilityParameterType.TEXT,
                CapabilityParameterType.ENUM -> parameter.defaultText?.let(::stringLiteral) ?: "null"
            }
        }
        return when (parameter.type) {
            CapabilityParameterType.NUMBER -> doubleLiteral(raw.toDouble())
            CapabilityParameterType.BOOLEAN -> raw.equals("true", ignoreCase = true).toString()
            CapabilityParameterType.TEXT,
            CapabilityParameterType.ENUM -> stringLiteral(raw)
        }
    }

    private fun renderInvocationArguments(
        parameters: List<CapabilityParameterDescriptor>,
        indent: Int
    ): String {
        val sorted = parameters.sortedBy { it.key }
        if (sorted.isEmpty()) return ""
        val names = assignParameterNames(parameters)
        return buildString {
            append('\n')
            sorted.forEach { parameter ->
                append("    ".repeat(indent + 1))
                append("${names.getValue(parameter.key)} = ${renderArgumentRead(parameter)},\n")
            }
            append("    ".repeat(indent))
        }
    }

    private fun renderArgumentRead(parameter: CapabilityParameterDescriptor): String {
        val method = when (parameter.type) {
            CapabilityParameterType.NUMBER -> if (parameter.isEffectivelyRequired()) "requiredNumber" else "optionalNumber"
            CapabilityParameterType.BOOLEAN -> if (parameter.isEffectivelyRequired()) "requiredBoolean" else "optionalBoolean"
            CapabilityParameterType.TEXT -> if (parameter.isEffectivelyRequired()) "requiredText" else "optionalText"
            CapabilityParameterType.ENUM -> if (parameter.isEffectivelyRequired()) "requiredEnum" else "optionalEnum"
        }
        return when (parameter.type) {
            CapabilityParameterType.NUMBER -> "parsed.$method(" + listOf(
                stringLiteral(parameter.key),
                renderNullableDouble(parameter.defaultNumber),
                renderNullableDouble(parameter.minimum),
                renderNullableDouble(parameter.maximum)
            ).joinToString() + ")"
            CapabilityParameterType.BOOLEAN ->
                "parsed.$method(${stringLiteral(parameter.key)}, ${parameter.defaultBoolean ?: "null"})"
            CapabilityParameterType.TEXT ->
                "parsed.$method(${stringLiteral(parameter.key)}, ${renderNullableString(parameter.defaultText)})"
            CapabilityParameterType.ENUM ->
                "parsed.$method(${stringLiteral(parameter.key)}, " +
                    "${renderStringSet(parameter.options, 0)}, ${renderNullableString(parameter.defaultText)})"
        }
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

    private fun renderStringSet(values: List<String>, @Suppress("UNUSED_PARAMETER") indent: Int): String =
        if (values.isEmpty()) "emptySet()" else values.distinct().sorted().joinToString(
            prefix = "setOf(",
            postfix = ")"
        ) { stringLiteral(it) }

    /**
     * Resource keys an action exclusively owns while active. READ claims are omitted: they
     * describe state observation, not arbitration, and must not produce parallel-resource
     * conflicts — the runtime bitmask model treats them the same way.
     */
    private fun exclusiveResourceKeys(descriptor: ActionDescriptor): List<String> =
        descriptor.resources.filter { it.access == ResourceAccess.EXCLUSIVE }.map { it.resourceKey }

    private fun assignMethodNames(prefix: String, keys: List<String>): Map<String, String> {
        val candidates = keys.associateWith { key -> prefix + key.toIdentifierSuffix() }
        val duplicateCandidates = candidates.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return keys.associateWith { key ->
            val candidate = candidates.getValue(key)
            if (candidate in duplicateCandidates) "${candidate}_${sha256(key).take(8)}" else candidate
        }
    }

    private fun String.toIdentifierSuffix(): String {
        val words = split(Regex("[^A-Za-z0-9]+"))
            .filter(String::isNotEmpty)
        val suffix = words.joinToString("") { word ->
            word.replaceFirstChar { character -> character.uppercaseChar() }
        }.ifEmpty { "Capability" }
        return if (suffix.first().isDigit()) "Key$suffix" else suffix
    }

    private fun parameterName(key: String): String {
        val sanitized = key.replace(Regex("[^A-Za-z0-9_]"), "_")
        val prefixed = if (sanitized.firstOrNull()?.isDigit() == true) "p_$sanitized" else sanitized
        return if (prefixed in KOTLIN_KEYWORDS) "p_$prefixed" else prefixed
    }

    private fun assignParameterNames(
        parameters: List<CapabilityParameterDescriptor>
    ): Map<String, String> {
        val candidates = parameters.associate { it.key to parameterName(it.key) }
        val collisions = candidates.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return candidates.mapValues { (key, candidate) ->
            if (candidate in collisions) "${candidate}_${sha256(key).take(8)}" else candidate
        }
    }

    private fun suppressorVariableName(bindingId: String): String =
        "suppressingChord_${bindingId.replace(Regex("[^A-Za-z0-9_]"), "_")}_${sha256(bindingId).take(8)}"

    private fun suppressionStateVariableName(controllerSlot: String): String =
        "buttonSuppression_${controllerSlot.replace(Regex("[^A-Za-z0-9_]"), "_")}_${sha256(controllerSlot).take(8)}"

    private fun CapabilityParameterDescriptor.kotlinType(): String {
        val base = when (type) {
            CapabilityParameterType.NUMBER -> "Double"
            CapabilityParameterType.BOOLEAN -> "Boolean"
            CapabilityParameterType.TEXT,
            CapabilityParameterType.ENUM -> "String"
        }
        return if (isEffectivelyRequired()) base else "$base?"
    }

    private fun CapabilityParameterDescriptor.isEffectivelyRequired(): Boolean = required || when (type) {
        CapabilityParameterType.NUMBER -> defaultNumber != null
        CapabilityParameterType.BOOLEAN -> defaultBoolean != null
        CapabilityParameterType.TEXT,
        CapabilityParameterType.ENUM -> defaultText != null
    }

    private fun validateControls(
        request: KotlinProjectCodegenRequest,
        actions: Map<String, ActionDescriptor>,
        routineIds: Set<String>
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
                        "Control scheme '${scheme.documentId}' references missing profile '${assignment.profileId}'"
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
                            "Binding '${binding.bindingId}' references unknown control '$controlId'"
                        )
                    val expectedType = when (binding.source.kind) {
                        ControlSourceKind.BUTTON,
                        ControlSourceKind.CHORD -> ControllerControlTypeDocument.BUTTON
                        ControlSourceKind.AXIS_THRESHOLD,
                        ControlSourceKind.AXIS_VALUE,
                        ControlSourceKind.AXIS_ZONE -> ControllerControlTypeDocument.AXIS
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
                validateControlTarget(binding, actions)
                listOf(
                    binding.timing.pressDebounceSeconds,
                    binding.timing.releaseDebounceSeconds,
                    binding.timing.holdAfterSeconds,
                    binding.timing.repeatAfterSeconds,
                    binding.timing.repeatEverySeconds,
                    binding.timing.cooldownSeconds,
                    binding.timing.maximumActiveSeconds,
                    binding.source.chordWindowSeconds
                ).filterNotNull().forEach { secondsToNanos(it) }
            }
        }
    }

    private fun validateControlTarget(
        binding: ControlBindingDocument,
        actions: Map<String, ActionDescriptor>
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
        val dynamicKey = if (binding.source.kind in ANALOG_SOURCE_KINDS) {
            requireNotNull(binding.analogPolicy).valueArgumentKey
        } else {
            null
        }
        if (dynamicKey != null) {
            val parameter = descriptor.parameters.firstOrNull { it.key == dynamicKey }
                ?: throw IllegalArgumentException(
                    "Binding '${binding.bindingId}' writes analog value to missing action argument '$dynamicKey'"
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
            descriptor.parameters.mapTo(mutableSetOf()) { it.key }
        )
        descriptor.parameters.forEach { reader.read(it) }
    }

    private fun ControllerProfileDocument.control(controlId: String): ControllerControlDocument =
        controls.first { it.controlId == controlId }

    private fun ControllerControlDocument.requiredButtonIndex(platform: ControllerInputPlatform): Int =
        requireNotNull(mappings.firstOrNull { it.platform == platform }?.buttonIndex) {
            "Control '$controlId' does not have a learned $platform button index"
        }

    private fun ControllerControlDocument.requiredAxisIndex(platform: ControllerInputPlatform): Int =
        requireNotNull(mappings.firstOrNull { it.platform == platform }?.axisIndex) {
            "Control '$controlId' does not have a learned $platform axis index"
        }

    private fun secondsToNanos(seconds: Double): Long {
        require(seconds.isFinite() && seconds >= 0.0 && seconds <= MAX_NANOSECOND_DURATION_SECONDS) {
            "Duration $seconds seconds cannot be represented in monotonic nanoseconds"
        }
        return kotlin.math.round(seconds * NANOS_PER_SECOND)
            .coerceAtMost(Long.MAX_VALUE.toDouble())
            .toLong()
    }

    private fun optionalSecondsToNanos(seconds: Double?): Long = seconds?.let(::secondsToNanos) ?: -1L

    private fun routineSupportsContext(
        routineId: String,
        routines: Map<String, RoutineDocument>,
        actions: Map<String, ActionDescriptor>,
        visited: MutableSet<String>
    ): Boolean {
        if (!visited.add(routineId)) return true
        val supported = routines[routineId]?.steps.orEmpty().all { step ->
            stepSupportsContext(step, routines, actions, visited)
        }
        visited.remove(routineId)
        return supported
    }

    private fun stepSupportsContext(
        step: RoutineStep,
        routines: Map<String, RoutineDocument>,
        actions: Map<String, ActionDescriptor>,
        visited: MutableSet<String>
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
        if (routineId != null && !routineSupportsContext(routineId, routines, actions, visited)) return false
        return step.deadline?.let { stepSupportsContext(it, routines, actions, visited) } != false &&
            step.children.all { stepSupportsContext(it, routines, actions, visited) } &&
            step.elseChildren.all { stepSupportsContext(it, routines, actions, visited) }
    }

    private fun StringBuilder.appendIndent(level: Int, value: String) {
        append("    ".repeat(level))
        append(value)
    }

    private fun renderNullableString(value: String?): String = value?.let(::stringLiteral) ?: "null"
    private fun renderNullableDouble(value: Double?): String = value?.let(::doubleLiteral) ?: "null"
    private fun doubleLiteral(value: Double): String {
        return value.kotlinDoubleLiteral()
    }

    private fun stringLiteral(value: String): String = value.kotlinStringLiteral()

    private fun String.isKotlinIdentifier(): Boolean =
        matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && this !in KOTLIN_KEYWORDS

    private fun RoutinePose.isFinite(): Boolean =
        xMeters.isFinite() && yMeters.isFinite() && headingRadians.isFinite()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .toHex()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private val SOURCE_HASH_DECLARATION =
        Regex("const val SOURCE_SHA256: String = \\\"([a-f0-9]{64})\\\"")
    private val SHA_256_REGEX = Regex("[a-f0-9]{64}")
    private const val SOURCE_HASH_PLACEHOLDER = "0000000000000000000000000000000000000000000000000000000000000000"
    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val MAX_NANOSECOND_DURATION_SECONDS = Long.MAX_VALUE / NANOS_PER_SECOND
    private val DIGITAL_SOURCE_KINDS = setOf(
        ControlSourceKind.BUTTON,
        ControlSourceKind.CHORD,
        ControlSourceKind.AXIS_THRESHOLD
    )
    private val ANALOG_SOURCE_KINDS = setOf(ControlSourceKind.AXIS_VALUE, ControlSourceKind.AXIS_ZONE)
    private val KOTLIN_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
        "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch", "constructor",
        "delegate", "dynamic", "field", "file", "finally", "get", "import", "init", "param", "property",
        "receiver", "set", "setparam", "where", "actual", "abstract", "annotation", "companion", "const",
        "crossinline", "data", "enum", "expect", "external", "final", "infix", "inline", "inner", "internal",
        "lateinit", "noinline", "open", "operator", "out", "override", "private", "protected", "public",
        "reified", "sealed", "suspend", "tailrec", "vararg"
    )
}
