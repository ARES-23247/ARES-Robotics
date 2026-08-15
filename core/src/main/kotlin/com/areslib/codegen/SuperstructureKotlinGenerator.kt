package com.areslib.codegen

import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemValueType
import com.areslib.subsystem.subsystemTargetActionKey
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureDocumentCodec
import com.areslib.superstructure.SuperstructureIssueSeverity
import com.areslib.superstructure.TransitionTriggerKind
import com.areslib.superstructure.validateSuperstructureProject

data class GeneratedSuperstructureFile(
    val relativePath: String,
    val content: String,
    val description: String = "",
)

/** Generates typed adapters from validated state machines to generated subsystem Redux tasks. */
object SuperstructureKotlinGenerator {
    fun generate(
        document: SuperstructureDocument,
        packageName: String,
        subsystemRegistryFqn: String,
        subsystems: List<SubsystemDocument>,
        actionKeys: Set<String>,
        parameterlessActionKeys: Set<String> = actionKeys,
    ): GeneratedSuperstructureFile {
        require(packageName.isKotlinPackage()) { "Invalid superstructure package '$packageName'" }
        require(subsystemRegistryFqn.isKotlinFqn()) { "Invalid subsystem registry '$subsystemRegistryFqn'" }
        val errors = validateSuperstructureProject(document, subsystems, actionKeys, parameterlessActionKeys)
            .filter { it.severity == SuperstructureIssueSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString("; ") { "${it.path}: ${it.message}" } }

        val typeName = document.superstructureId.pascalCase()
        val definitionName = "${typeName}SuperstructureDefinition"
        val bindingName = "${typeName}SuperstructureBinding"
        val basePackage = subsystemRegistryFqn.substringBeforeLast('.')
        val referenced = referencedSubsystems(document).map { uid -> subsystems.single { it.uid == uid } }
        val ports = referenced.sortedBy { it.uid }.flatMap { subsystem ->
            subsystem.stateFields.sortedBy { it.uid }.map { field -> PortBinding(subsystem, field) }
        }.mapIndexed { index, port -> port.copy(index = index) }
        val machineActions = document.transitions.asSequence()
            .filter { it.triggerKind == TransitionTriggerKind.ACTION_REQUEST }
            .mapNotNull { it.actionKey }
            .distinct()
            .sorted()
            .toList()
        val encoded = SuperstructureDocumentCodec.encode(document)

        val source = buildString {
            appendLine("// ARES OWNERSHIP: GENERATED - DO NOT EDIT")
            appendLine("// Typed superstructure adapter; edit the .aressuperstructure document instead.")
            appendLine("package $packageName")
            appendLine()
            appendLine("import com.areslib.sequencer.Task")
            appendLine("import com.areslib.pathing.CommandKey")
            appendLine("import com.areslib.pathing.NamedCommands")
            appendLine("import com.areslib.state.RobotState")
            appendLine("import com.areslib.subsystem.Subsystem")
            appendLine("import com.areslib.subsystem.SubsystemValueType")
            appendLine("import com.areslib.superstructure.SuperstructureDocumentCodec")
            appendLine("import com.areslib.superstructure.SuperstructureRuntime")
            appendLine("import com.areslib.superstructure.SuperstructureRuntimeBinding")
            appendLine("import com.areslib.superstructure.SuperstructurePortHealthBits")
            appendLine("import com.areslib.telemetry.RobotStatusTracker")
            appendLine("import $subsystemRegistryFqn")
            appendLine()
            appendLine("object $definitionName {")
            appendLine("    const val ID: String = ${document.superstructureId.quoted()}")
            appendLine("    const val CONTENT_SHA256: String = ${SuperstructureDocumentCodec.contentHash(document).quoted()}")
            appendLine("    val DOCUMENT = SuperstructureDocumentCodec.decode(${encoded.quoted()})")
            appendLine("}")
            appendLine()
            appendLine("private object $bindingName : SuperstructureRuntimeBinding {")
            appendLine("    override fun isRobotEnabled(): Boolean = RobotStatusTracker.isEnabled")
            appendLine()
            append(resolvePortFunction(ports))
            append(portTypeFunction(ports))
            append(readFunction("readNumeric", "Double", "Double.NaN", ports, basePackage) { field ->
                when (field.type) {
                    SubsystemValueType.DOUBLE -> "snapshot.${field.fieldId}"
                    SubsystemValueType.INT -> "snapshot.${field.fieldId}.toDouble()"
                    else -> null
                }
            })
            append(readFunction("readBoolean", "Boolean?", "null", ports, basePackage) { field ->
                if (field.type == SubsystemValueType.BOOLEAN) "snapshot.${field.fieldId}" else null
            })
            append(readFunction("readString", "String?", "null", ports, basePackage) { field ->
                if (field.type == SubsystemValueType.STRING) "snapshot.${field.fieldId}" else null
            })
            append(healthFunction(ports, basePackage))
            append(targetTaskFunction("Double", "createDoubleTargetTask", ports, subsystemRegistryFqn))
            append(targetTaskFunction("Int", "createIntTargetTask", ports, subsystemRegistryFqn))
            append(targetTaskFunction("Boolean", "createBooleanTargetTask", ports, subsystemRegistryFqn))
            append(targetTaskFunction("String", "createStringTargetTask", ports, subsystemRegistryFqn))
            appendLine("    override fun createLifecycleActionTask(actionKey: String, timestampMs: Long): Task? =")
            appendLine("        runCatching { CommandKey(actionKey) }.getOrNull()?.let { NamedCommands.create(it, timestampMs) }")
            appendLine("}")
            appendLine()
            appendLine("fun create${typeName}Superstructure(): Subsystem = SuperstructureRuntime(")
            appendLine("    $definitionName.DOCUMENT,")
            appendLine("    $bindingName,")
            appendLine(")")
            appendLine()
            appendLine("fun create${typeName}SuperstructureAction(actionKey: String): Task? = when (actionKey) {")
            machineActions.forEach { key ->
                appendLine("    ${key.quoted()} -> SuperstructureRuntime.requestTask(")
                appendLine("        $definitionName.ID,")
                appendLine("        ${document.initialStateId.quoted()},")
                appendLine("        actionKey,")
                appendLine("    )")
            }
            appendLine("    else -> null")
            appendLine("}")
        }
        return GeneratedSuperstructureFile(
            relativePath = "${typeName}Superstructure.kt",
            content = source,
            description = "Typed Redux runtime binding for superstructure '${document.superstructureId}'.",
        )
    }

    fun generateRegistry(
        documents: List<SuperstructureDocument>,
        packageName: String,
    ): GeneratedSuperstructureFile {
        val owners = documents.flatMap { document ->
            document.transitions.filter { it.triggerKind == TransitionTriggerKind.ACTION_REQUEST }
                .mapNotNull { edge -> edge.actionKey?.let { it to document } }
        }
        val duplicates = owners.groupBy { it.first }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "A superstructure action key must have one owner: ${duplicates.sorted().joinToString()}" }
        val sorted = documents.sortedBy { it.superstructureId }
        val source = buildString {
            appendLine("// ARES OWNERSHIP: GENERATED - DO NOT EDIT")
            appendLine("// Mechanical composition for validated generated superstructure runtimes.")
            appendLine("package $packageName")
            appendLine()
            appendLine("import com.areslib.sequencer.Task")
            appendLine("import com.areslib.subsystem.Subsystem")
            appendLine()
            appendLine("object GeneratedSuperstructureRegistry {")
            if (sorted.isEmpty()) {
                appendLine("    fun createAll(): List<Subsystem> = emptyList()")
            } else {
                appendLine("    fun createAll(): List<Subsystem> = listOf(")
                sorted.forEach { appendLine("        create${it.superstructureId.pascalCase()}Superstructure(),") }
                appendLine("    )")
            }
            appendLine()
            if (owners.isEmpty()) {
                appendLine("    fun createActionTask(@Suppress(\"UNUSED_PARAMETER\") actionKey: String): Task? = null")
            } else {
                appendLine("    fun createActionTask(actionKey: String): Task? = when (actionKey) {")
                owners.sortedBy { it.first }.forEach { (key, document) ->
                    appendLine("        ${key.quoted()} -> create${document.superstructureId.pascalCase()}SuperstructureAction(actionKey)")
                }
                appendLine("        else -> null")
                appendLine("    }")
            }
            appendLine("}")
        }
        return GeneratedSuperstructureFile(
            "GeneratedSuperstructureRegistry.kt",
            source,
            "Composition and action routing for generated superstructures.",
        )
    }

    private fun resolvePortFunction(ports: List<PortBinding>): String = buildString {
        appendLine("    override fun resolvePort(subsystemUid: String, fieldUid: String): Int = when (subsystemUid) {")
        ports.groupBy { it.subsystem.uid }.toSortedMap().forEach { (subsystemUid, subsystemPorts) ->
            appendLine("        ${subsystemUid.quoted()} -> when (fieldUid) {")
            subsystemPorts.sortedBy { it.field.uid }.forEach { port ->
                appendLine("            ${port.field.uid.quoted()} -> ${port.index}")
            }
            appendLine("            else -> -1")
            appendLine("        }")
        }
        appendLine("        else -> -1")
        appendLine("    }")
        appendLine()
    }

    private fun portTypeFunction(ports: List<PortBinding>): String = buildString {
        appendLine("    override fun portType(port: Int): SubsystemValueType? = when (port) {")
        ports.forEach { port -> appendLine("        ${port.index} -> SubsystemValueType.${port.field.type}") }
        appendLine("        else -> null")
        appendLine("    }")
        appendLine()
    }

    private fun readFunction(
        name: String,
        returnType: String,
        fallback: String,
        ports: List<PortBinding>,
        basePackage: String,
        expression: (SubsystemStateFieldDocument) -> String?,
    ): String = buildString {
        appendLine("    override fun $name(port: Int, state: RobotState): $returnType {")
        appendLine("        return when (port) {")
        ports.forEach { port ->
            val value = expression(port.field) ?: return@forEach
            val segment = port.subsystem.documentId.replace('-', '_')
            val stateFqn = "$basePackage.$segment.${port.subsystem.kotlinTypeName}State"
            appendLine("            ${port.index} -> {")
            appendLine("                val snapshot = state.superstructure.subsystems[${port.subsystem.documentId.quoted()}] as? $stateFqn")
            appendLine("                    ?: return $fallback")
            appendLine("                $value")
            appendLine("            }")
        }
        appendLine("            else -> $fallback")
        appendLine("        }")
        appendLine("    }")
        appendLine()
    }

    private fun healthFunction(ports: List<PortBinding>, basePackage: String): String = buildString {
        appendLine("    override fun readHealthBits(port: Int, state: RobotState, nowMs: Long): Int {")
        appendLine("        return when (port) {")
        ports.forEach { port ->
            val segment = port.subsystem.documentId.replace('-', '_')
            val stateFqn = "$basePackage.$segment.${port.subsystem.kotlinTypeName}State"
            val maxAgeMs = port.subsystem.hardware.asSequence()
                .flatMap { it.measurements.asSequence() }
                .filter { it.fieldId == port.field.fieldId }
                .mapNotNull { it.maxAgeMs }
                .minOrNull() ?: port.subsystem.safety.feedbackTimeoutMs ?: 250L
            appendLine("            ${port.index} -> {")
            appendLine("                val snapshot = state.superstructure.subsystems[${port.subsystem.documentId.quoted()}] as? $stateFqn ?: return 0")
            appendLine("                var bits = 0")
            appendLine("                if (snapshot.feedbackValid) bits = bits or SuperstructurePortHealthBits.VALID")
            appendLine("                val ageMs = if (nowMs >= snapshot.feedbackTimestampMs) nowMs - snapshot.feedbackTimestampMs else Long.MAX_VALUE")
            appendLine("                if (ageMs <= ${maxAgeMs}L) bits = bits or SuperstructurePortHealthBits.FRESH")
            appendLine("                if (snapshot.configurationHealthy) bits = bits or SuperstructurePortHealthBits.CONFIGURED")
            appendLine("                if (snapshot.homed) bits = bits or SuperstructurePortHealthBits.HOMED")
            appendLine("                if (snapshot.calibrated) bits = bits or SuperstructurePortHealthBits.CALIBRATED")
            appendLine("                if (snapshot.currentReadingValid) bits = bits or SuperstructurePortHealthBits.CURRENT_VALID")
            appendLine("                if (!snapshot.outputFaultLatched) bits = bits or SuperstructurePortHealthBits.OUTPUT_HEALTHY")
            appendLine("                bits")
            appendLine("            }")
        }
        appendLine("            else -> 0")
        appendLine("        }")
        appendLine("    }")
        appendLine()
    }

    private fun targetTaskFunction(
        valueType: String,
        functionName: String,
        ports: List<PortBinding>,
        registryFqn: String,
    ): String = buildString {
        val expectedType = when (valueType) {
            "Double" -> SubsystemValueType.DOUBLE
            "Int" -> SubsystemValueType.INT
            "Boolean" -> SubsystemValueType.BOOLEAN
            else -> SubsystemValueType.STRING
        }
        appendLine("    override fun $functionName(port: Int, value: $valueType): Task? = when (port) {")
        ports.filter { it.field.type == expectedType && it.field.role.name == "TARGET" }.forEach { port ->
            val key = subsystemTargetActionKey(port.subsystem.documentId, port.field.fieldId)
            appendLine("        ${port.index} -> $registryFqn.createActionTask(${key.quoted()}, value)")
        }
        appendLine("        else -> null")
        appendLine("    }")
        appendLine()
    }

    private fun referencedSubsystems(document: SuperstructureDocument): Set<String> = buildSet {
        document.states.forEach { state ->
            state.subsystemTargets.forEach { target ->
                add(target.target.subsystemUid)
                target.source?.let { add(it.subsystemUid) }
            }
        }
        document.transitions.forEach { edge -> edge.guards.forEach { add(it.source.subsystemUid) } }
        document.interlocks.forEach {
            add(it.primary.subsystemUid)
            add(it.constrained.subsystemUid)
        }
        document.healthFallbacks.forEach { add(it.source.subsystemUid) }
    }
}

private data class PortBinding(
    val subsystem: SubsystemDocument,
    val field: SubsystemStateFieldDocument,
    val index: Int = -1,
)

private fun String.pascalCase(): String = split(Regex("[^A-Za-z0-9]+"))
    .filter(String::isNotBlank)
    .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

private fun String.isKotlinPackage(): Boolean =
    matches(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"))

private fun String.isKotlinFqn(): Boolean = isKotlinPackage() && contains('.')

private fun String.quoted(): String = buildString {
    append('"')
    this@quoted.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(character)
        }
    }
    append('"')
}
