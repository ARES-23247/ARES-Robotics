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
    ): GeneratedSuperstructureFile {
        require(packageName.isKotlinPackage()) { "Invalid superstructure package '$packageName'" }
        require(subsystemRegistryFqn.isKotlinFqn()) { "Invalid subsystem registry '$subsystemRegistryFqn'" }
        val errors = validateSuperstructureProject(document, subsystems, actionKeys)
            .filter { it.severity == SuperstructureIssueSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString("; ") { "${it.path}: ${it.message}" } }

        val typeName = document.superstructureId.pascalCase()
        val definitionName = "${typeName}SuperstructureDefinition"
        val bindingName = "${typeName}SuperstructureBinding"
        val basePackage = subsystemRegistryFqn.substringBeforeLast('.')
        val referenced = referencedSubsystems(document).map { id -> subsystems.single { it.documentId == id } }
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
            appendLine("import com.areslib.state.RobotState")
            appendLine("import com.areslib.subsystem.Subsystem")
            appendLine("import com.areslib.subsystem.SubsystemValueType")
            appendLine("import com.areslib.superstructure.SuperstructureDocumentCodec")
            appendLine("import com.areslib.superstructure.SuperstructureRuntime")
            appendLine("import com.areslib.superstructure.SuperstructureRuntimeBinding")
            appendLine("import $subsystemRegistryFqn")
            appendLine()
            appendLine("object $definitionName {")
            appendLine("    const val ID: String = ${document.superstructureId.quoted()}")
            appendLine("    const val CONTENT_SHA256: String = ${SuperstructureDocumentCodec.contentHash(document).quoted()}")
            appendLine("    val DOCUMENT = SuperstructureDocumentCodec.decode(${encoded.quoted()})")
            appendLine("}")
            appendLine()
            appendLine("private object $bindingName : SuperstructureRuntimeBinding {")
            append(targetTypeFunction(referenced))
            append(readFunction("readNumeric", "Double", "Double.NaN", referenced, basePackage) { field ->
                when (field.type) {
                    SubsystemValueType.DOUBLE -> "snapshot.${field.fieldId}"
                    SubsystemValueType.INT -> "snapshot.${field.fieldId}.toDouble()"
                    else -> null
                }
            })
            append(readFunction("readBoolean", "Boolean?", "null", referenced, basePackage) { field ->
                if (field.type == SubsystemValueType.BOOLEAN) "snapshot.${field.fieldId}" else null
            })
            append(readFunction("readString", "String?", "null", referenced, basePackage) { field ->
                if (field.type == SubsystemValueType.STRING) "snapshot.${field.fieldId}" else null
            })
            append(targetTaskFunction("Double", "createDoubleTargetTask", referenced, subsystemRegistryFqn))
            append(targetTaskFunction("Int", "createIntTargetTask", referenced, subsystemRegistryFqn))
            append(targetTaskFunction("Boolean", "createBooleanTargetTask", referenced, subsystemRegistryFqn))
            append(targetTaskFunction("String", "createStringTargetTask", referenced, subsystemRegistryFqn))
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

    private fun targetTypeFunction(subsystems: List<SubsystemDocument>): String = buildString {
        appendLine("    override fun targetType(subsystemId: String, fieldId: String): SubsystemValueType? = when (subsystemId) {")
        subsystems.sortedBy { it.documentId }.forEach { subsystem ->
            val fields = subsystem.stateFields.filter { it.role.name == "TARGET" }
            appendLine("        ${subsystem.documentId.quoted()} -> when (fieldId) {")
            fields.sortedBy { it.fieldId }.forEach { field ->
                appendLine("            ${field.fieldId.quoted()} -> SubsystemValueType.${field.type}")
            }
            appendLine("            else -> null")
            appendLine("        }")
        }
        appendLine("        else -> null")
        appendLine("    }")
        appendLine()
    }

    private fun readFunction(
        name: String,
        returnType: String,
        fallback: String,
        subsystems: List<SubsystemDocument>,
        basePackage: String,
        expression: (SubsystemStateFieldDocument) -> String?,
    ): String = buildString {
        appendLine("    override fun $name(subsystemId: String, fieldId: String, state: RobotState): $returnType = when (subsystemId) {")
        subsystems.sortedBy { it.documentId }.forEach { subsystem ->
            val readable = subsystem.stateFields.mapNotNull { field -> expression(field)?.let { field to it } }
            val segment = subsystem.documentId.replace('-', '_')
            val stateFqn = "$basePackage.$segment.${subsystem.kotlinTypeName}State"
            appendLine("        ${subsystem.documentId.quoted()} -> {")
            appendLine("            val snapshot = state.superstructure.subsystems[${subsystem.documentId.quoted()}] as? $stateFqn")
            appendLine("                ?: return $fallback")
            appendLine("            when (fieldId) {")
            readable.sortedBy { it.first.fieldId }.forEach { (field, value) ->
                appendLine("                ${field.fieldId.quoted()} -> $value")
            }
            appendLine("                else -> $fallback")
            appendLine("            }")
            appendLine("        }")
        }
        appendLine("        else -> $fallback")
        appendLine("    }")
        appendLine()
    }

    private fun targetTaskFunction(
        valueType: String,
        functionName: String,
        subsystems: List<SubsystemDocument>,
        registryFqn: String,
    ): String = buildString {
        val expectedType = when (valueType) {
            "Double" -> SubsystemValueType.DOUBLE
            "Int" -> SubsystemValueType.INT
            "Boolean" -> SubsystemValueType.BOOLEAN
            else -> SubsystemValueType.STRING
        }
        appendLine("    override fun $functionName(subsystemId: String, fieldId: String, value: $valueType): Task? = when (subsystemId) {")
        subsystems.sortedBy { it.documentId }.forEach { subsystem ->
            val fields = subsystem.stateFields.filter { it.type == expectedType && it.role.name == "TARGET" }
            appendLine("        ${subsystem.documentId.quoted()} -> when (fieldId) {")
            fields.sortedBy { it.fieldId }.forEach { field ->
                val key = subsystemTargetActionKey(subsystem.documentId, field.fieldId)
                appendLine("            ${field.fieldId.quoted()} -> $registryFqn.createActionTask(${key.quoted()}, value)")
            }
            appendLine("            else -> null")
            appendLine("        }")
        }
        appendLine("        else -> null")
        appendLine("    }")
        appendLine()
    }

    private fun referencedSubsystems(document: SuperstructureDocument): Set<String> = buildSet {
        document.states.forEach { state ->
            state.subsystemTargets.forEach { target ->
                add(target.subsystemId)
                target.source?.let { add(it.subsystemId) }
            }
        }
        document.transitions.forEach { edge -> edge.guards.forEach { add(it.source.subsystemId) } }
        document.interlocks.forEach {
            add(it.primary.subsystemId)
            add(it.constrainedSubsystemId)
        }
    }
}

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
