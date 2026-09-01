package com.areslib.subsystem

/** Cross-subsystem identity and interlock validation. */
internal object SubsystemProjectValidation {
    fun validateAll(documents: List<SubsystemDocument>): List<SubsystemValidationIssue> = buildList {
        val byUid = documents.groupBy { it.uid }
        byUid.filterValues { it.size > 1 }.keys.sorted().forEach { uid ->
            add(SubsystemValidationIssue("subsystems", "Subsystem UID '$uid' is duplicated"))
        }
    
        documents.forEach { owner ->
            owner.interlocks.forEachIndexed { index, interlock ->
                val path = "subsystems[${owner.documentId}].interlocks[$index]"
                val target = byUid[interlock.targetSubsystemUid]?.singleOrNull()
                if (target == null) {
                    add(
                        SubsystemValidationIssue(
                            "$path.targetSubsystemUid",
                            "Interlock target '${interlock.targetSubsystemUid}' does not resolve to exactly one subsystem",
                        ),
                    )
                    return@forEachIndexed
                }
                if (!target.implementation.kind.isAresGenerated()) {
                    add(
                        SubsystemValidationIssue(
                            "$path.targetSubsystemUid",
                            "Generated interlocks require a generated target state; '${target.uid}' is hand-authored",
                        ),
                    )
                    return@forEachIndexed
                }
                val field = target.stateFields.singleOrNull { it.fieldId == interlock.targetFieldId }
                if (field == null) {
                    add(
                        SubsystemValidationIssue(
                            "$path.targetFieldId",
                            "Target subsystem '${target.uid}' has no state field '${interlock.targetFieldId}'",
                        ),
                    )
                    return@forEachIndexed
                }
                when (interlock.comparison) {
                    InterlockComparison.LESS_THAN,
                    InterlockComparison.GREATER_THAN -> if (field.type !in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) {
                        add(SubsystemValidationIssue("$path.comparison", "Ordered interlocks require a numeric target field"))
                    }
                    InterlockComparison.EQUALS_STATE,
                    InterlockComparison.NOT_EQUALS_STATE -> when (field.type) {
                        SubsystemValueType.BOOLEAN -> if (interlock.targetStateName?.lowercase() !in setOf("true", "false")) {
                            add(SubsystemValidationIssue("$path.targetStateName", "Boolean equality requires true or false"))
                        }
                        SubsystemValueType.STRING -> if (interlock.targetStateName.isNullOrBlank()) {
                            add(SubsystemValidationIssue("$path.targetStateName", "String equality requires an expected state value"))
                        }
                        SubsystemValueType.DOUBLE,
                        SubsystemValueType.INT -> Unit
                    }
                }
            }
        }
    }
    
}

