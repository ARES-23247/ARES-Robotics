package com.areslib.superstructure

import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemValueType
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.ArrayDeque

const val ARES_SUPERSTRUCTURE_SCHEMA_VERSION: Int = 2

enum class SuperstructureTargetMode { CONSTANT, DYNAMIC_LUT, PASS_THROUGH }
enum class TransitionTriggerKind { ACTION_REQUEST, SENSOR_CONDITION_AUTO, TIME_ELAPSED }
enum class LutInterpolationMethod { LINEAR, STEP, SMOOTH_COSINE }

data class LutControlPoint(val inputX: Double, val outputY: Double)

data class SuperstructureDynamicLut(
    val lutId: String,
    val displayName: String = "",
    val inputUnit: String = "",
    val outputUnit: String = "",
    val interpolation: LutInterpolationMethod = LutInterpolationMethod.LINEAR,
    val controlPoints: List<LutControlPoint> = emptyList(),
) {
    /** Samples a validated, sorted LUT without allocating. */
    fun sample(x: Double): Double {
        if (!x.isFinite() || controlPoints.isEmpty()) return Double.NaN
        if (controlPoints.size == 1 || x <= controlPoints.first().inputX) return controlPoints.first().outputY
        if (x >= controlPoints.last().inputX) return controlPoints.last().outputY
        for (index in 0 until controlPoints.size - 1) {
            val lower = controlPoints[index]
            val upper = controlPoints[index + 1]
            if (x <= upper.inputX) {
                val ratio = (x - lower.inputX) / (upper.inputX - lower.inputX)
                return when (interpolation) {
                    LutInterpolationMethod.STEP -> lower.outputY
                    LutInterpolationMethod.LINEAR -> lower.outputY + ratio * (upper.outputY - lower.outputY)
                    LutInterpolationMethod.SMOOTH_COSINE -> {
                        val factor = (1.0 - kotlin.math.cos(ratio * kotlin.math.PI)) / 2.0
                        lower.outputY + factor * (upper.outputY - lower.outputY)
                    }
                }
            }
        }
        return controlPoints.last().outputY
    }
}

/** A typed cached subsystem field used by a guard or computed target. */
data class SuperstructureFieldReference(
    val subsystemId: String,
    val fieldId: String,
)

data class TransitionGuard(
    val guardId: String,
    val source: SuperstructureFieldReference,
    val comparison: InterlockComparison = InterlockComparison.EQUALS_STATE,
    val expectedDoubleValue: Double? = null,
    val expectedBooleanValue: Boolean? = null,
    val expectedStringValue: String? = null,
    val tolerance: Double = 1e-4,
)

data class StateTransitionEdge(
    val transitionId: String,
    val sourceStateId: String,
    val targetStateId: String,
    val triggerKind: TransitionTriggerKind = TransitionTriggerKind.ACTION_REQUEST,
    val actionKey: String? = null,
    val guards: List<TransitionGuard> = emptyList(),
    val debounceMs: Long = 0L,
    /** Required for TIME_ELAPSED; optional pending-request deadline for ACTION_REQUEST. */
    val timeoutSeconds: Double? = null,
    /** Fail-closed destination when a pending ACTION_REQUEST times out. */
    val timeoutTargetStateId: String? = null,
)

data class SuperstructureSubsystemTarget(
    val subsystemId: String,
    val fieldId: String,
    val targetMode: SuperstructureTargetMode = SuperstructureTargetMode.CONSTANT,
    val constantDoubleValue: Double? = null,
    val constantBooleanValue: Boolean? = null,
    val constantStringValue: String? = null,
    val lutId: String? = null,
    val source: SuperstructureFieldReference? = null,
)

data class SuperstructureStatePreset(
    val stateId: String,
    val displayName: String = "",
    val description: String = "",
    val subsystemTargets: List<SuperstructureSubsystemTarget> = emptyList(),
    val timeoutSeconds: Double? = null,
    val timeoutTargetStateId: String? = null,
)

data class SuperstructureInterlockRule(
    val ruleId: String,
    val description: String = "",
    val primary: SuperstructureFieldReference,
    val conditionComparison: InterlockComparison = InterlockComparison.LESS_THAN,
    val conditionThreshold: Double = 0.0,
    val constrainedSubsystemId: String,
    val constrainedFieldId: String,
    val clampMinimum: Double? = null,
    val clampMaximum: Double? = null,
)

data class SuperstructureDocument(
    val superstructureId: String,
    val displayName: String = "",
    val description: String = "",
    val schemaVersion: Int = ARES_SUPERSTRUCTURE_SCHEMA_VERSION,
    val initialStateId: String,
    val states: List<SuperstructureStatePreset> = emptyList(),
    val transitions: List<StateTransitionEdge> = emptyList(),
    val interlocks: List<SuperstructureInterlockRule> = emptyList(),
    val luts: List<SuperstructureDynamicLut> = emptyList(),
    /** Required fail-closed preset used when generated target application cannot be completed. */
    val faultStateId: String,
)

enum class SuperstructureIssueSeverity { ERROR, WARNING }

data class SuperstructureValidationIssue(
    val severity: SuperstructureIssueSeverity,
    val path: String,
    val message: String,
)

/** Validates document-local invariants without assuming a project or action catalog. */
fun validateSuperstructureDocument(document: SuperstructureDocument): List<SuperstructureValidationIssue> {
    val issues = mutableListOf<SuperstructureValidationIssue>()
    fun error(path: String, message: String) {
        issues += SuperstructureValidationIssue(SuperstructureIssueSeverity.ERROR, path, message)
    }

    if (!document.superstructureId.matches(ID_PATTERN)) error("superstructureId", "Use lowercase letters, digits, and hyphens")
    if (document.schemaVersion != ARES_SUPERSTRUCTURE_SCHEMA_VERSION) {
        error("schemaVersion", "Unsupported schema version ${document.schemaVersion}")
    }
    if (document.states.isEmpty()) error("states", "Declare at least one state preset")
    duplicateValues(document.states.map { it.stateId }).forEach { error("states", "State ID '$it' is duplicated") }
    duplicateValues(document.transitions.map { it.transitionId }).forEach { error("transitions", "Transition ID '$it' is duplicated") }
    duplicateValues(document.interlocks.map { it.ruleId }).forEach { error("interlocks", "Interlock ID '$it' is duplicated") }
    duplicateValues(document.luts.map { it.lutId }).forEach { error("luts", "LUT ID '$it' is duplicated") }

    val stateIds = document.states.mapTo(linkedSetOf()) { it.stateId }
    if (document.initialStateId !in stateIds) error("initialStateId", "Initial state '${document.initialStateId}' is not declared")
    if (document.faultStateId !in stateIds) error("faultStateId", "Fault state '${document.faultStateId}' is not declared")
    val lutIds = document.luts.mapTo(linkedSetOf()) { it.lutId }

    document.luts.forEachIndexed { index, lut ->
        val path = "luts[$index]"
        if (!lut.lutId.matches(ID_PATTERN)) error("$path.lutId", "Use lowercase letters, digits, and hyphens")
        if (lut.controlPoints.size < 2) error("$path.controlPoints", "A LUT requires at least two control points")
        lut.controlPoints.forEachIndexed { pointIndex, point ->
            if (!point.inputX.isFinite() || !point.outputY.isFinite()) {
                error("$path.controlPoints[$pointIndex]", "LUT coordinates must be finite")
            }
            if (pointIndex > 0 && point.inputX <= lut.controlPoints[pointIndex - 1].inputX) {
                error("$path.controlPoints", "inputX values must be strictly increasing")
            }
        }
    }

    val canonicalTargetSet = document.states.firstOrNull()?.subsystemTargets
        ?.map { it.subsystemId to it.fieldId }?.toSet().orEmpty()
    document.states.forEachIndexed { index, state ->
        val path = "states[$index]"
        if (!state.stateId.matches(TYPE_ID_PATTERN)) error("$path.stateId", "Use letters, digits, and underscores")
        val targetKeys = state.subsystemTargets.map { it.subsystemId to it.fieldId }
        duplicateValues(targetKeys).forEach { error("$path.subsystemTargets", "Target '${it.first}.${it.second}' is duplicated") }
        if (targetKeys.toSet() != canonicalTargetSet) {
            error("$path.subsystemTargets", "Every state must explicitly command the same target fields so outputs cannot remain stale")
        }
        validateTimeout(state.timeoutSeconds, "$path.timeoutSeconds", ::error)
        if ((state.timeoutSeconds == null) != (state.timeoutTargetStateId == null)) {
            error(path, "State timeoutSeconds and timeoutTargetStateId must be supplied together")
        }
        state.timeoutTargetStateId?.takeIf { it !in stateIds }?.let {
            error("$path.timeoutTargetStateId", "Timeout state '$it' is not declared")
        }
        state.subsystemTargets.forEachIndexed { targetIndex, target ->
            val targetPath = "$path.subsystemTargets[$targetIndex]"
            if (target.subsystemId.isBlank() || target.fieldId.isBlank()) error(targetPath, "Subsystem and field IDs are required")
            val constants = listOfNotNull(target.constantDoubleValue, target.constantBooleanValue, target.constantStringValue)
            when (target.targetMode) {
                SuperstructureTargetMode.CONSTANT -> if (constants.size != 1) error(targetPath, "A constant target requires exactly one typed value")
                SuperstructureTargetMode.DYNAMIC_LUT -> {
                    if (constants.isNotEmpty()) error(targetPath, "A dynamic LUT target cannot also declare a constant")
                    if (target.lutId !in lutIds) error("$targetPath.lutId", "Referenced LUT '${target.lutId}' is not declared")
                    if (target.source == null) error("$targetPath.source", "A dynamic LUT target requires a typed source field")
                }
                SuperstructureTargetMode.PASS_THROUGH -> {
                    if (constants.isNotEmpty() || target.lutId != null) error(targetPath, "A pass-through target cannot declare a constant or LUT")
                    if (target.source == null) error("$targetPath.source", "A pass-through target requires a typed source field")
                }
            }
        }
    }

    val actionEdges = mutableSetOf<Pair<String, String>>()
    document.transitions.forEachIndexed { index, transition ->
        val path = "transitions[$index]"
        if (!transition.transitionId.matches(ID_PATTERN)) error("$path.transitionId", "Use lowercase letters, digits, and hyphens")
        if (transition.sourceStateId !in stateIds) error("$path.sourceStateId", "Unknown source state '${transition.sourceStateId}'")
        if (transition.targetStateId !in stateIds) error("$path.targetStateId", "Unknown target state '${transition.targetStateId}'")
        if (transition.sourceStateId == transition.targetStateId) error(path, "A transition must change state")
        if (transition.debounceMs !in 0L..60_000L) error("$path.debounceMs", "Debounce must be from 0 to 60000 ms")
        validateTimeout(transition.timeoutSeconds, "$path.timeoutSeconds", ::error)
        transition.timeoutTargetStateId?.takeIf { it !in stateIds }?.let {
            error("$path.timeoutTargetStateId", "Timeout state '$it' is not declared")
        }
        when (transition.triggerKind) {
            TransitionTriggerKind.ACTION_REQUEST -> {
                if (transition.actionKey.isNullOrBlank()) error("$path.actionKey", "Action-request transitions require an action key")
                val key = transition.sourceStateId to transition.actionKey.orEmpty()
                if (!actionEdges.add(key)) error(path, "Only one outgoing transition may use action '${transition.actionKey}' from ${transition.sourceStateId}")
                if ((transition.timeoutSeconds == null) != (transition.timeoutTargetStateId == null)) {
                    error(path, "Pending action timeoutSeconds and timeoutTargetStateId must be supplied together")
                }
                if (transition.guards.isNotEmpty() && transition.timeoutSeconds == null) {
                    error(path, "Guarded action requests require a fail-closed timeout and timeout target")
                }
            }
            TransitionTriggerKind.SENSOR_CONDITION_AUTO -> {
                if (transition.actionKey != null) error("$path.actionKey", "Automatic sensor transitions cannot declare an action key")
                if (transition.guards.isEmpty()) error("$path.guards", "Automatic sensor transitions require at least one guard")
                if (transition.timeoutTargetStateId != null) error(path, "Sensor transitions do not use a pending-request timeout target")
            }
            TransitionTriggerKind.TIME_ELAPSED -> {
                if (transition.actionKey != null || transition.guards.isNotEmpty()) error(path, "Time transitions cannot declare action keys or guards")
                if (transition.timeoutSeconds == null) error("$path.timeoutSeconds", "Time transitions require timeoutSeconds")
                if (transition.timeoutTargetStateId != null) error(path, "The transition target is the elapsed-time destination")
            }
        }
        duplicateValues(transition.guards.map { it.guardId }).forEach { error("$path.guards", "Guard ID '$it' is duplicated") }
        transition.guards.forEachIndexed { guardIndex, guard ->
            val guardPath = "$path.guards[$guardIndex]"
            if (!guard.guardId.matches(ID_PATTERN)) error("$guardPath.guardId", "Use lowercase letters, digits, and hyphens")
            if (guard.source.subsystemId.isBlank() || guard.source.fieldId.isBlank()) error("$guardPath.source", "A typed source is required")
            if (!guard.tolerance.isFinite() || guard.tolerance < 0.0) error("$guardPath.tolerance", "Tolerance must be finite and non-negative")
            if (listOfNotNull(guard.expectedDoubleValue, guard.expectedBooleanValue, guard.expectedStringValue).size != 1) {
                error(guardPath, "A guard requires exactly one typed expected value")
            }
        }
    }

    document.interlocks.forEachIndexed { index, interlock ->
        val path = "interlocks[$index]"
        if (!interlock.ruleId.matches(ID_PATTERN)) error("$path.ruleId", "Use lowercase letters, digits, and hyphens")
        if (!interlock.conditionThreshold.isFinite()) error("$path.conditionThreshold", "Threshold must be finite")
        if (interlock.clampMinimum == null && interlock.clampMaximum == null) error(path, "An interlock must define a clamp")
        if (interlock.clampMinimum?.isFinite() == false || interlock.clampMaximum?.isFinite() == false) error(path, "Clamp values must be finite")
        if (interlock.clampMinimum != null && interlock.clampMaximum != null && interlock.clampMinimum > interlock.clampMaximum) {
            error(path, "Clamp minimum cannot exceed clamp maximum")
        }
    }

    // The runtime can enter the fault preset from any state when target preflight/application
    // fails. It therefore has an implicit safety edge and must not require a fabricated student
    // transition merely to satisfy graph reachability.
    val reachable = linkedSetOf(document.initialStateId, document.faultStateId)
    val queue = ArrayDeque<String>().apply {
        add(document.initialStateId)
        if (document.faultStateId != document.initialStateId) add(document.faultStateId)
    }
    while (queue.isNotEmpty()) {
        val source = queue.removeFirst()
        document.transitions.filter { it.sourceStateId == source }.forEach { edge ->
            if (reachable.add(edge.targetStateId)) queue.add(edge.targetStateId)
            edge.timeoutTargetStateId?.let { if (reachable.add(it)) queue.add(it) }
        }
        document.states.singleOrNull { it.stateId == source }?.timeoutTargetStateId?.let {
            if (reachable.add(it)) queue.add(it)
        }
    }
    (stateIds - reachable).forEach { error("states", "State '$it' is unreachable from '${document.initialStateId}'") }
    return issues
}

/** Validates every reference against generated subsystem plumbing and the action catalog. */
fun validateSuperstructureProject(
    document: SuperstructureDocument,
    subsystems: List<SubsystemDocument>,
    actionKeys: Set<String>,
): List<SuperstructureValidationIssue> {
    val issues = validateSuperstructureDocument(document).toMutableList()
    fun error(path: String, message: String) {
        issues += SuperstructureValidationIssue(SuperstructureIssueSeverity.ERROR, path, message)
    }
    val byId = subsystems.associateBy { it.documentId }
    fun resolve(reference: SuperstructureFieldReference, path: String): Pair<SubsystemDocument, SubsystemStateFieldDocument>? {
        val subsystem = byId[reference.subsystemId]
        if (subsystem == null) {
            error(path, "Subsystem '${reference.subsystemId}' is not declared in .ares/subsystems")
            return null
        }
        if (subsystem.implementation.kind != SubsystemImplementationKind.GENERATED_STARTER) {
            error(path, "Hand-authored subsystem '${reference.subsystemId}' requires an explicit typed superstructure adapter")
            return null
        }
        val field = subsystem.stateFields.singleOrNull { it.fieldId == reference.fieldId }
        if (field == null) error(path, "Field '${reference.fieldId}' is not declared by subsystem '${reference.subsystemId}'")
        return field?.let { subsystem to it }
    }

    document.transitions.forEachIndexed { edgeIndex, edge ->
        if (edge.triggerKind == TransitionTriggerKind.ACTION_REQUEST && edge.actionKey !in actionKeys) {
            error("transitions[$edgeIndex].actionKey", "Action '${edge.actionKey}' is not present in the project action catalog")
        }
        edge.guards.forEachIndexed guardLoop@ { guardIndex, guard ->
            val path = "transitions[$edgeIndex].guards[$guardIndex]"
            val field = resolve(guard.source, "$path.source")?.second ?: return@guardLoop
            val correctType = when (field.type) {
                SubsystemValueType.DOUBLE, SubsystemValueType.INT -> guard.expectedDoubleValue != null
                SubsystemValueType.BOOLEAN -> guard.expectedBooleanValue != null
                SubsystemValueType.STRING -> guard.expectedStringValue != null
            }
            if (!correctType) error(path, "Guard expected value must match ${field.type}")
        }
    }
    document.states.forEachIndexed { stateIndex, state ->
        state.subsystemTargets.forEachIndexed targetLoop@ { targetIndex, target ->
            val path = "states[$stateIndex].subsystemTargets[$targetIndex]"
            val field = resolve(SuperstructureFieldReference(target.subsystemId, target.fieldId), path)?.second
                ?: return@targetLoop
            if (field.role != SubsystemFieldRole.TARGET) error(path, "Superstructure targets may command only TARGET fields")
            val constantTypeMatches = when (field.type) {
                SubsystemValueType.DOUBLE, SubsystemValueType.INT -> target.constantDoubleValue != null
                SubsystemValueType.BOOLEAN -> target.constantBooleanValue != null
                SubsystemValueType.STRING -> target.constantStringValue != null
            }
            if (target.targetMode == SuperstructureTargetMode.CONSTANT && !constantTypeMatches) {
                error(path, "Constant value must match ${field.type}")
            }
            if (target.targetMode == SuperstructureTargetMode.DYNAMIC_LUT && field.type !in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) {
                error(path, "Dynamic LUT outputs may command only numeric TARGET fields")
            }
            target.source?.let { source ->
                val sourceField = resolve(source, "$path.source")?.second ?: return@let
                if (target.targetMode == SuperstructureTargetMode.DYNAMIC_LUT && sourceField.type !in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) {
                    error("$path.source", "Dynamic LUT inputs must be numeric")
                }
                if (target.targetMode == SuperstructureTargetMode.PASS_THROUGH && sourceField.type != field.type) {
                    error("$path.source", "Pass-through source type ${sourceField.type} does not match target type ${field.type}")
                }
            }
        }
    }
    document.interlocks.forEachIndexed { index, interlock ->
        val path = "interlocks[$index]"
        val primary = resolve(interlock.primary, "$path.primary")?.second
        if (primary != null && primary.type !in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) {
            error("$path.primary", "Interlock source must be numeric")
        }
        val constrained = resolve(
            SuperstructureFieldReference(interlock.constrainedSubsystemId, interlock.constrainedFieldId),
            "$path.constrainedFieldId",
        )?.second
        if (constrained != null && (constrained.role != SubsystemFieldRole.TARGET || constrained.type !in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT))) {
            error(path, "Interlocks may clamp only numeric TARGET fields")
        }
    }
    val fault = document.states.singleOrNull { it.stateId == document.faultStateId }
    fault?.subsystemTargets?.forEachIndexed { index, target ->
        val field = resolve(SuperstructureFieldReference(target.subsystemId, target.fieldId), "faultStateId.targets[$index]")?.second
        if (field != null && target.targetMode == SuperstructureTargetMode.CONSTANT) {
            val neutral = when (field.type) {
                SubsystemValueType.DOUBLE, SubsystemValueType.INT ->
                    target.constantDoubleValue == field.numericDefault()
                SubsystemValueType.BOOLEAN -> target.constantBooleanValue == field.defaultBoolean
                SubsystemValueType.STRING -> target.constantStringValue == field.defaultText
            }
            if (!neutral) error("faultStateId.targets[$index]", "Fault-state targets must equal the subsystem field's declared safe default")
        } else if (field != null) {
            error("faultStateId.targets[$index]", "Fault-state targets must be constants")
        }
    }
    return issues
}

object SuperstructureDocumentCodec {
    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    fun encode(document: SuperstructureDocument): String {
        requireValid(document)
        return gson.toJson(document)
    }

    fun decode(json: String): SuperstructureDocument {
        val document = try {
            val root = JsonParser.parseString(json)
            require(root.isJsonObject) { "Superstructure document must be an object" }
            validateJsonShape(root.asJsonObject)
            val parsed = gson.fromJson(root, SuperstructureDocument::class.java)
                ?: error("Superstructure document is empty")
            normalize(parsed)
        } catch (error: Exception) {
            throw IllegalArgumentException("Superstructure document is not valid: ${error.message}", error)
        }
        requireValid(document)
        return document
    }

    fun contentHash(document: SuperstructureDocument): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(document).toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun normalize(document: SuperstructureDocument): SuperstructureDocument = document.copy(
        states = document.states.orEmpty().map { it.copy(subsystemTargets = it.subsystemTargets.orEmpty()) },
        transitions = document.transitions.orEmpty().map { it.copy(guards = it.guards.orEmpty()) },
        interlocks = document.interlocks.orEmpty(),
        luts = document.luts.orEmpty().map { it.copy(controlPoints = it.controlPoints.orEmpty()) },
    )

    private fun requireValid(document: SuperstructureDocument) {
        val errors = validateSuperstructureDocument(document).filter { it.severity == SuperstructureIssueSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString("; ") { "${it.path}: ${it.message}" } }
    }

    private fun validateJsonShape(root: JsonObject) {
        exact(root, ROOT_FIELDS, "$")
        requireInteger(root, "schemaVersion", "$")
        require(root.get("schemaVersion").asInt == ARES_SUPERSTRUCTURE_SCHEMA_VERSION) {
            "Unsupported superstructure schema version ${root.get("schemaVersion").asInt}"
        }
        arrayObjects(root, "states", "$").forEachIndexed { index, state ->
            exact(state, STATE_FIELDS, "$.states[$index]")
            arrayObjects(state, "subsystemTargets", "$.states[$index]").forEachIndexed { targetIndex, target ->
                exact(target, TARGET_FIELDS, "$.states[$index].subsystemTargets[$targetIndex]")
                optionalObject(target, "source", "$.states[$index].subsystemTargets[$targetIndex]")?.let {
                    exact(it, REFERENCE_FIELDS, "$.states[$index].subsystemTargets[$targetIndex].source")
                }
            }
        }
        arrayObjects(root, "transitions", "$").forEachIndexed { index, edge ->
            exact(edge, TRANSITION_FIELDS, "$.transitions[$index]")
            arrayObjects(edge, "guards", "$.transitions[$index]").forEachIndexed { guardIndex, guard ->
                exact(guard, GUARD_FIELDS, "$.transitions[$index].guards[$guardIndex]")
                exact(
                    requiredObject(guard, "source", "$.transitions[$index].guards[$guardIndex]"),
                    REFERENCE_FIELDS,
                    "$.transitions[$index].guards[$guardIndex].source",
                )
            }
        }
        arrayObjects(root, "interlocks", "$").forEachIndexed { index, interlock ->
            exact(interlock, INTERLOCK_FIELDS, "$.interlocks[$index]")
            exact(requiredObject(interlock, "primary", "$.interlocks[$index]"), REFERENCE_FIELDS, "$.interlocks[$index].primary")
        }
        arrayObjects(root, "luts", "$").forEachIndexed { index, lut ->
            exact(lut, LUT_FIELDS, "$.luts[$index]")
            arrayObjects(lut, "controlPoints", "$.luts[$index]").forEachIndexed { pointIndex, point ->
                exact(point, POINT_FIELDS, "$.luts[$index].controlPoints[$pointIndex]")
            }
        }
    }

    private fun exact(value: JsonObject, fields: Set<String>, path: String) {
        val unknown = value.keySet() - fields
        require(unknown.isEmpty()) { "Unknown fields at $path: ${unknown.sorted().joinToString()}" }
    }

    private fun requireInteger(value: JsonObject, field: String, path: String) {
        val element = value.get(field)
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "$path.$field must be an integer" }
        val number = element.asBigDecimal
        require(number.stripTrailingZeros().scale() <= 0) { "$path.$field must be an integer" }
    }

    private fun arrayObjects(value: JsonObject, field: String, path: String): List<JsonObject> {
        val element = value.get(field) ?: return emptyList()
        require(element.isJsonArray) { "$path.$field must be an array" }
        return element.asJsonArray.mapIndexed { index, child ->
            require(child.isJsonObject) { "$path.$field[$index] must be an object" }
            child.asJsonObject
        }
    }

    private fun requiredObject(value: JsonObject, field: String, path: String): JsonObject {
        val element = value.get(field)
        require(element != null && element.isJsonObject) { "$path.$field must be an object" }
        return element.asJsonObject
    }

    private fun optionalObject(value: JsonObject, field: String, path: String): JsonObject? {
        val element: JsonElement = value.get(field) ?: return null
        if (element.isJsonNull) return null
        require(element.isJsonObject) { "$path.$field must be an object" }
        return element.asJsonObject
    }

    private val ROOT_FIELDS = setOf("superstructureId", "displayName", "description", "schemaVersion", "initialStateId", "states", "transitions", "interlocks", "luts", "faultStateId")
    private val STATE_FIELDS = setOf("stateId", "displayName", "description", "subsystemTargets", "timeoutSeconds", "timeoutTargetStateId")
    private val TARGET_FIELDS = setOf("subsystemId", "fieldId", "targetMode", "constantDoubleValue", "constantBooleanValue", "constantStringValue", "lutId", "source")
    private val TRANSITION_FIELDS = setOf("transitionId", "sourceStateId", "targetStateId", "triggerKind", "actionKey", "guards", "debounceMs", "timeoutSeconds", "timeoutTargetStateId")
    private val GUARD_FIELDS = setOf("guardId", "source", "comparison", "expectedDoubleValue", "expectedBooleanValue", "expectedStringValue", "tolerance")
    private val REFERENCE_FIELDS = setOf("subsystemId", "fieldId")
    private val INTERLOCK_FIELDS = setOf("ruleId", "description", "primary", "conditionComparison", "conditionThreshold", "constrainedSubsystemId", "constrainedFieldId", "clampMinimum", "clampMaximum")
    private val LUT_FIELDS = setOf("lutId", "displayName", "inputUnit", "outputUnit", "interpolation", "controlPoints")
    private val POINT_FIELDS = setOf("inputX", "outputY")
}

private fun validateTimeout(value: Double?, path: String, issue: (String, String) -> Unit) {
    if (value != null && (!value.isFinite() || value <= 0.0 || value > 3600.0)) {
        issue(path, "Timeout must be finite and in (0, 3600] seconds")
    }
}

private fun <T> duplicateValues(values: List<T>): Set<T> = values.groupingBy { it }.eachCount()
    .filterValues { it > 1 }.keys

private fun SubsystemStateFieldDocument.numericDefault(): Double? = when (type) {
    SubsystemValueType.DOUBLE -> defaultNumber
    SubsystemValueType.INT -> defaultInt?.toDouble()
    else -> null
}

private val ID_PATTERN = Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
private val TYPE_ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*")
