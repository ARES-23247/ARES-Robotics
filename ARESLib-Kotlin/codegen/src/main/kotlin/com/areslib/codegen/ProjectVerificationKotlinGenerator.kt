package com.areslib.codegen

import com.areslib.controls.ControllerInputPlatform

internal data class ProjectVerificationCodegenRequest(
    val packageName: String,
    val platform: ControllerInputPlatform,
    val projectJson: String,
    val catalogJson: String,
    val drivetrainJson: List<String>,
    val subsystemJson: List<String>,
    val superstructureJson: List<String>,
    val controllerProfileJson: List<String>,
    val controlSchemeJson: List<String>,
    val routineJson: List<String>,
    val autonomousCatalogJson: String?,
)

internal data class GeneratedProjectVerificationFile(
    val relativePath: String,
    val content: String,
)

/** Stable method identities consumed by Studio's run-scoped verification report. */
object ProjectGeneratedTestNames {
    const val PROJECT_IDENTITY = "generated project identity and footprint are valid"
    const val DRIVETRAIN_SAFETY = "generated drivetrain safety contract is valid"
    const val CONTROLS = "generated controls resolve typed project targets"
    const val AUTONOMOUS = "generated autonomous graph is closed"
    const val SUPERSTRUCTURE = "generated superstructure references and interlocks are valid"
}

/** Emits deterministic project-level contract tests from canonical GUI-owned documents. */
internal object ProjectVerificationKotlinGenerator {
    fun generate(request: ProjectVerificationCodegenRequest): GeneratedProjectVerificationFile {
        require(request.packageName.isKotlinPackageName()) { "Invalid project verification package '${request.packageName}'" }
        require(request.platform == ControllerInputPlatform.FTC || request.platform == ControllerInputPlatform.FRC) {
            "Project verification generation requires FTC or FRC"
        }
        val testImport = when (request.platform) {
            ControllerInputPlatform.FTC -> "import org.junit.Test\nimport org.junit.Assert.assertTrue"
            ControllerInputPlatform.FRC -> "import org.junit.jupiter.api.Test\nimport org.junit.jupiter.api.Assertions.assertTrue"
            ControllerInputPlatform.DESKTOP_GLFW -> error("Desktop projects do not own a robot contract suite")
        }
        val assertion = when (request.platform) {
            ControllerInputPlatform.FTC ->
                "assertTrue(errors.joinToString(\"\\n\"), errors.isEmpty())"
            ControllerInputPlatform.FRC ->
                "assertTrue(errors.isEmpty()) { errors.joinToString(\"\\n\") }"
            ControllerInputPlatform.DESKTOP_GLFW -> error("unreachable")
        }
        val source = """
            // ARES OWNERSHIP: GENERATED - DO NOT EDIT
            // Mechanical verification derived from canonical GUI-owned project documents.
            package ${request.packageName}

            import com.areslib.catalog.CapabilityCatalogCodec
            import com.areslib.catalog.ResourceAccess
            import com.areslib.controls.ControlSchemeCodec
            import com.areslib.controls.ControlValidationContext
            import com.areslib.controls.ControlValidationSeverity
            import com.areslib.controls.ControllerProfileCodec
            import com.areslib.controls.validateControlScheme
            import com.areslib.controls.validateControllerProfile
            import com.areslib.drivetrain.DrivetrainDocumentCodec
            import com.areslib.drivetrain.validateDrivetrainDocument
            import com.areslib.project.AresProjectMetadataCodec
            import com.areslib.project.validateAresProjectMetadata
            import com.areslib.routine.AresRoutineCodec
            import com.areslib.routine.AutonomousCatalogCodec
            import com.areslib.routine.RoutineValidationContext
            import com.areslib.routine.RoutineValidationSeverity
            import com.areslib.routine.validateAutonomousCatalog
            import com.areslib.routine.validateRoutineSet
            import com.areslib.subsystem.SubsystemDocumentCodec
            import com.areslib.superstructure.SuperstructureDocumentCodec
            import com.areslib.superstructure.SuperstructureIssueSeverity
            import com.areslib.superstructure.TransitionTriggerKind
            import com.areslib.superstructure.validateSuperstructureProject
            ${testImport.substringBefore('\n')}
            ${testImport.substringAfter('\n')}

            class GeneratedAresProjectContractTest {
                @Test
                fun `${ProjectGeneratedTestNames.PROJECT_IDENTITY}`() {
                    val document = AresProjectMetadataCodec.decode(PROJECT_JSON)
                    assertNoErrors(validateAresProjectMetadata(document))
                }

                @Test
                fun `${ProjectGeneratedTestNames.DRIVETRAIN_SAFETY}`() {
                    val errors = DRIVETRAIN_JSON.flatMap { json ->
                        validateDrivetrainDocument(DrivetrainDocumentCodec.decode(json))
                            .map { "${'$'}{it.path}: ${'$'}{it.message}" }
                    }
                    assertNoErrors(errors)
                }

                @Test
                fun `${ProjectGeneratedTestNames.CONTROLS}`() {
                    val catalog = CapabilityCatalogCodec.decode(CATALOG_JSON)
                    val profiles = CONTROLLER_PROFILE_JSON.map(ControllerProfileCodec::decode)
                    val profileErrors = profiles.flatMap(::validateControllerProfile)
                        .filter { it.severity == ControlValidationSeverity.ERROR }
                        .map { "${'$'}{it.path}: ${'$'}{it.message}" }
                    val context = ControlValidationContext.fromCatalog(
                        catalog = catalog,
                        routineIds = ROUTINE_JSON.map { AresRoutineCodec.decode(it).documentId }.toSet(),
                        profileControls = profiles.associate { profile ->
                            profile.documentId to profile.controls.mapTo(linkedSetOf()) { it.controlId }
                        },
                    )
                    val schemeErrors = CONTROL_SCHEME_JSON.flatMap { json ->
                        validateControlScheme(ControlSchemeCodec.decode(json), context)
                            .filter { it.severity == ControlValidationSeverity.ERROR }
                            .map { "${'$'}{it.path}: ${'$'}{it.message}" }
                    }
                    assertNoErrors(profileErrors + schemeErrors)
                }

                @Test
                fun `${ProjectGeneratedTestNames.AUTONOMOUS}`() {
                    val catalog = CapabilityCatalogCodec.decode(CATALOG_JSON)
                    val actionByKey = catalog.actions.associateBy { it.key }
                    val conditionKeys = catalog.conditions.mapTo(linkedSetOf()) { it.key }
                    val routineContext = RoutineValidationContext(
                        hasAction = actionByKey::containsKey,
                        hasCondition = conditionKeys::contains,
                        resourcesForAction = { key ->
                            actionByKey[key]?.resources
                                ?.filter { it.access == ResourceAccess.EXCLUSIVE }
                                ?.mapTo(linkedSetOf()) { it.resourceKey }
                                .orEmpty()
                        },
                    )
                    val routines = ROUTINE_JSON.map(AresRoutineCodec::decode)
                    val errors = validateRoutineSet(routines, routineContext)
                        .filter { it.severity == RoutineValidationSeverity.ERROR }
                        .map { "${'$'}{it.documentId}/${'$'}{it.path}: ${'$'}{it.message}" }
                        .toMutableList()
                    AUTONOMOUS_CATALOG_JSON?.let { json ->
                        errors += validateAutonomousCatalog(
                            AutonomousCatalogCodec.decode(json),
                            routines.mapTo(linkedSetOf()) { it.documentId },
                        ).filter { it.severity == RoutineValidationSeverity.ERROR }
                            .map { "${'$'}{it.path}: ${'$'}{it.message}" }
                    }
                    assertNoErrors(errors)
                }

                @Test
                fun `${ProjectGeneratedTestNames.SUPERSTRUCTURE}`() {
                    val catalog = CapabilityCatalogCodec.decode(CATALOG_JSON)
                    val actionKeys = catalog.actions.mapTo(linkedSetOf()) { it.key }
                    val parameterlessActionKeys = catalog.actions.filter { it.parameters.isEmpty() }
                        .mapTo(linkedSetOf()) { it.key }
                    val subsystems = SUBSYSTEM_JSON.map(SubsystemDocumentCodec::decode)
                    val superstructures = SUPERSTRUCTURE_JSON.map(SuperstructureDocumentCodec::decode)
                    val errors = superstructures.flatMap { document ->
                        validateSuperstructureProject(
                            document,
                            subsystems,
                            actionKeys,
                            parameterlessActionKeys,
                        ).filter { it.severity == SuperstructureIssueSeverity.ERROR }
                            .map { "${'$'}{it.path}: ${'$'}{it.message}" }
                    }.toMutableList()
                    superstructures
                        .flatMap { document ->
                            document.transitions.filter { it.triggerKind == TransitionTriggerKind.ACTION_REQUEST }
                                .mapNotNull { edge -> edge.actionKey?.let { it to document.superstructureId } }
                        }
                        .groupBy({ it.first }, { it.second })
                        .filterValues { it.distinct().size > 1 }
                        .forEach { (key, owners) ->
                            errors += "transitions.actionKey: Action '${'$'}key' is owned by more than one superstructure: ${'$'}{owners.distinct().sorted().joinToString()}"
                        }
                    superstructures
                        .flatMap { document ->
                            (
                                document.states.flatMap { preset -> preset.subsystemTargets.map { it.target.subsystemUid to it.target.fieldUid } } +
                                    document.interlocks.map { it.constrained.subsystemUid to it.constrained.fieldUid }
                                ).distinct().map { it to document.superstructureId }
                        }
                        .groupBy({ it.first }, { it.second })
                        .filterValues { it.distinct().size > 1 }
                        .forEach { (target, owners) ->
                            errors += "states.subsystemTargets: Target '${'$'}{target.first}.${'$'}{target.second}' is owned by more than one superstructure: ${'$'}{owners.distinct().sorted().joinToString()}"
                        }
                    assertNoErrors(errors)
                }

                private fun assertNoErrors(errors: List<String>) {
                    $assertion
                }

                private companion object {
                    val PROJECT_JSON: String = ${renderValue(request.projectJson)}
                    val CATALOG_JSON: String = ${renderValue(request.catalogJson)}
                    val DRIVETRAIN_JSON: List<String> = ${renderList(request.drivetrainJson)}
                    val SUBSYSTEM_JSON: List<String> = ${renderList(request.subsystemJson)}
                    val SUPERSTRUCTURE_JSON: List<String> = ${renderList(request.superstructureJson)}
                    val CONTROLLER_PROFILE_JSON: List<String> = ${renderList(request.controllerProfileJson)}
                    val CONTROL_SCHEME_JSON: List<String> = ${renderList(request.controlSchemeJson)}
                    val ROUTINE_JSON: List<String> = ${renderList(request.routineJson)}
                    val AUTONOMOUS_CATALOG_JSON: String? = ${request.autonomousCatalogJson?.let(::renderValue) ?: "null"}
                }
            }
        """.trimIndent() + "\n"
        return GeneratedProjectVerificationFile("project/GeneratedAresProjectContractTest.kt", source)
    }

    private fun renderList(values: List<String>): String = values.joinToString(
        prefix = "listOf(",
        postfix = ")",
        separator = ", ",
        transform = ::renderValue,
    )

    /** Uses small runtime-appended chunks so large project JSON cannot overflow one JVM constant. */
    private fun renderValue(value: String): String {
        val chunks = value.chunked(8_000).ifEmpty { listOf("") }
        return chunks.joinToString(prefix = "buildString { ", postfix = " }", separator = "; ") { chunk ->
            "append(${chunk.kotlinStringLiteral()})"
        }
    }
}
