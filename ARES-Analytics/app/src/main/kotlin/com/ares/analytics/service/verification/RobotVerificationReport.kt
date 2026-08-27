package com.ares.analytics.service.verification

import com.ares.analytics.shared.League
import com.areslib.codegen.ProjectGeneratedTestNames
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.routine.RoutineValidationSeverity
import com.areslib.routine.validateAutonomousCatalog
import com.areslib.routine.validateRoutineSet
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemVerificationEvidence
import com.areslib.subsystem.subsystemVerificationContract
import com.areslib.subsystem.validateSubsystemDocument
import com.areslib.superstructure.SuperstructureDocumentCodec
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.Serializable

@Serializable
enum class VerificationLayer {
    CONFIGURATION,
    GENERATED_BEHAVIOR,
    SIMULATOR,
    PLATFORM_INTEGRATION,
    BUILD,
    PHYSICAL_VALIDATION,
}

@Serializable
enum class VerificationResultStatus { PASSED, FAILED, BLOCKED, NOT_RUN }

@Serializable
enum class VerificationEvidenceLevel(val label: String) {
    CONFIGURATION_REVIEWED("Configuration reviewed"),
    COMPILED_SUCCESSFULLY("Compiled successfully"),
    GENERATED_BEHAVIOR_VERIFIED("Generated behavior verified"),
    GENERATED_CONTRACT_VERIFIED("Generated contract verified"),
    SIMULATION_VERIFIED("Simulation verified"),
    READY_FOR_PHYSICAL_VALIDATION("Ready for physical validation"),
    PHYSICAL_VALIDATION_REQUIRED("Physical validation required"),
    PHYSICALLY_VALIDATED("Physically validated"),
}

@Serializable
data class VerificationReportItem(
    val id: String,
    val layer: VerificationLayer,
    val title: String,
    val explanation: String,
    val status: VerificationResultStatus,
    val evidenceLevel: VerificationEvidenceLevel,
    val source: String,
    val advancedDetails: String = "",
)

@Serializable
data class RobotVerificationReport(
    val projectPath: String,
    val league: League,
    val provenance: VerificationRunProvenance,
    val items: List<VerificationReportItem>,
) {
    val failedCount: Int get() = items.count { it.status == VerificationResultStatus.FAILED }
    val blockedCount: Int get() = items.count { it.status == VerificationResultStatus.BLOCKED }
    val passedCount: Int get() = items.count { it.status == VerificationResultStatus.PASSED }
    val notRunCount: Int get() = items.count { it.status == VerificationResultStatus.NOT_RUN }
    val readyForPhysicalValidation: Boolean
        get() = items.none { it.layer != VerificationLayer.PHYSICAL_VALIDATION && it.status != VerificationResultStatus.PASSED }
}

/**
 * Joins canonical builder contracts to real Gradle XML results.
 *
 * Generated test source remains hidden build plumbing. Hand-written platform and simulator suites
 * stay visible as separate layers so a passing mock test is never presented as physical evidence.
 */
object RobotVerificationReportLoader {
    fun load(
        projectRoot: File,
        league: League,
        buildExitCode: Int,
        provenance: VerificationRunProvenance,
    ): RobotVerificationReport {
        val root = projectRoot.canonicalFile
        val testCases = resultRoots(root, league).flatMap { (layer, directory) ->
            readTestCases(directory, layer)
        }
        val subsystemLoads = loadSubsystemDocuments(root)
        val documents = subsystemLoads.mapNotNull { it.document }
        val items = mutableListOf<VerificationReportItem>()

        items += loadProjectConfigurationItems(root)
        subsystemLoads.filter { it.document == null }.forEach { load ->
            items += VerificationReportItem(
                id = "subsystem.${load.file.nameWithoutExtension}.configuration",
                layer = VerificationLayer.CONFIGURATION,
                title = "${load.file.nameWithoutExtension} subsystem configuration",
                explanation = "The canonical subsystem descriptor could not be decoded, so generated behavior cannot be verified.",
                status = VerificationResultStatus.FAILED,
                evidenceLevel = VerificationEvidenceLevel.CONFIGURATION_REVIEWED,
                source = ".ares/subsystems/${load.file.name}",
                advancedDetails = load.error.orEmpty(),
            )
        }

        documents.forEach { document ->
            val issues = validateSubsystemDocument(document)
            items += VerificationReportItem(
                id = "${document.documentId}.configuration",
                layer = VerificationLayer.CONFIGURATION,
                title = "${document.displayName} configuration",
                explanation = if (issues.isEmpty()) {
                    "The canonical subsystem descriptor satisfies its typed ownership and safety rules."
                } else {
                    "The canonical subsystem descriptor has ${issues.size} validation issue(s)."
                },
                status = if (issues.isEmpty()) VerificationResultStatus.PASSED else VerificationResultStatus.FAILED,
                evidenceLevel = VerificationEvidenceLevel.CONFIGURATION_REVIEWED,
                source = ".ares/subsystems/${document.documentId}.aressubsystem",
                advancedDetails = issues.joinToString("\n") { "${it.path}: ${it.message}" },
            )
            val classSuffix = ".${document.kotlinTypeName}GeneratedTest"
            subsystemVerificationContract(document).forEach { check ->
                val matching = check.testMethodName?.let { method ->
                    testCases.firstOrNull { result ->
                        result.name == method && (result.className.endsWith(classSuffix) || result.className == document.kotlinTypeName + "GeneratedTest")
                    }
                }
                val status = when (check.evidence) {
                    SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST -> when {
                        buildExitCode != 0 && matching?.status != VerificationResultStatus.FAILED -> VerificationResultStatus.BLOCKED
                        matching != null -> matching.status
                        buildExitCode == 0 -> VerificationResultStatus.FAILED
                        else -> VerificationResultStatus.BLOCKED
                    }
                    SubsystemVerificationEvidence.COMPILED_GENERATED_CODE ->
                        if (buildExitCode == 0) VerificationResultStatus.PASSED else VerificationResultStatus.BLOCKED
                    SubsystemVerificationEvidence.CONFIGURATION ->
                        if (issues.isEmpty()) VerificationResultStatus.PASSED else VerificationResultStatus.FAILED
                    SubsystemVerificationEvidence.PLATFORM_INTEGRATION_TEST -> VerificationResultStatus.NOT_RUN
                }
                items += VerificationReportItem(
                    id = check.id,
                    layer = when (check.evidence) {
                        SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST -> VerificationLayer.GENERATED_BEHAVIOR
                        SubsystemVerificationEvidence.COMPILED_GENERATED_CODE -> VerificationLayer.BUILD
                        SubsystemVerificationEvidence.CONFIGURATION -> VerificationLayer.CONFIGURATION
                        SubsystemVerificationEvidence.PLATFORM_INTEGRATION_TEST -> VerificationLayer.PLATFORM_INTEGRATION
                    },
                    title = "${document.displayName}: ${check.title}",
                    explanation = check.explanation,
                    status = status,
                    evidenceLevel = when (check.evidence) {
                        SubsystemVerificationEvidence.CONFIGURATION -> VerificationEvidenceLevel.CONFIGURATION_REVIEWED
                        SubsystemVerificationEvidence.COMPILED_GENERATED_CODE -> VerificationEvidenceLevel.COMPILED_SUCCESSFULLY
                        SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST -> VerificationEvidenceLevel.GENERATED_BEHAVIOR_VERIFIED
                        SubsystemVerificationEvidence.PLATFORM_INTEGRATION_TEST -> VerificationEvidenceLevel.COMPILED_SUCCESSFULLY
                    },
                    source = "Robot Builder generated verification",
                    advancedDetails = matching?.details ?: check.testMethodName?.let { "Expected generated test: $it" }.orEmpty(),
                )
            }
        }

        PROJECT_GENERATED_CHECKS.forEach { check ->
            val matching = testCases.firstOrNull { result ->
                result.name == check.methodName && result.className.endsWith(PROJECT_GENERATED_TEST_CLASS)
            }
            items += VerificationReportItem(
                id = check.id,
                layer = VerificationLayer.GENERATED_BEHAVIOR,
                title = check.title,
                explanation = check.explanation,
                status = when {
                    buildExitCode != 0 && matching?.status != VerificationResultStatus.FAILED -> VerificationResultStatus.BLOCKED
                    matching != null -> matching.status
                    buildExitCode == 0 -> VerificationResultStatus.FAILED
                    else -> VerificationResultStatus.BLOCKED
                },
                evidenceLevel = VerificationEvidenceLevel.GENERATED_CONTRACT_VERIFIED,
                source = "Robot Builder generated project verification",
                advancedDetails = matching?.details ?: "Expected generated test: ${check.methodName}",
            )
        }

        items += aggregateLayer(
            id = "project.platform-integration",
            layer = VerificationLayer.PLATFORM_INTEGRATION,
            title = "Platform lifecycle and integration tests",
            explanation = "Hand-written tests protect ${league.name} lifecycle, Redux, autonomous orchestration, coordinates, telemetry, and migration behavior outside one robot descriptor.",
            results = testCases.filter {
                it.layer == VerificationLayer.PLATFORM_INTEGRATION &&
                    !it.className.endsWith("GeneratedTest") &&
                    !it.className.endsWith(PROJECT_GENERATED_TEST_CLASS)
            },
            buildExitCode = buildExitCode,
        )
        if (league == League.FTC) {
            items += aggregateLayer(
                id = "project.simulator",
                layer = VerificationLayer.SIMULATOR,
                title = "Desktop simulator integration tests",
                explanation = "The FTC project simulator, OpMode lifecycle, controls, telemetry, and mock hardware integration ran outside the generated subsystem suites.",
                results = testCases.filter { it.layer == VerificationLayer.SIMULATOR },
                buildExitCode = buildExitCode,
            )
        }
        items += VerificationReportItem(
            id = "project.build",
            layer = VerificationLayer.BUILD,
            title = "Project package build",
            explanation = if (buildExitCode == 0) {
                "Generated ownership checks, tests, and the project package completed without deployment."
            } else {
                "The compile-only verification process failed; no deployment was attempted."
            },
            status = if (buildExitCode == 0) VerificationResultStatus.PASSED else VerificationResultStatus.FAILED,
            evidenceLevel = VerificationEvidenceLevel.COMPILED_SUCCESSFULLY,
            source = "Gradle Verify & build",
            advancedDetails = "Process exit code: $buildExitCode",
        )
        items += VerificationReportItem(
            id = "project.physical-checklist",
            layer = VerificationLayer.PHYSICAL_VALIDATION,
            title = "Supervised robot checklist",
            explanation = "On the disabled robot, confirm wiring and device names; then verify direction, safe neutral, limits, sensors, both indicator lights, and Prism output under team supervision.",
            status = VerificationResultStatus.NOT_RUN,
            evidenceLevel = VerificationEvidenceLevel.PHYSICAL_VALIDATION_REQUIRED,
            source = "Physical commissioning checklist",
            advancedDetails = "ARES does not mark this complete from configuration, compilation, or simulation evidence.",
        )
        val ready = items.none { it.layer != VerificationLayer.PHYSICAL_VALIDATION && it.status != VerificationResultStatus.PASSED }
        items += VerificationReportItem(
            id = "project.physical-validation",
            layer = VerificationLayer.PHYSICAL_VALIDATION,
            title = if (ready) "Ready for physical validation" else "Physical validation is not ready",
            explanation = if (ready) {
                "Configuration, generated behavior, platform tests, simulator tests, and build evidence passed. Wiring and real hardware still require a supervised checklist."
            } else {
                "Resolve failed, blocked, or unmeasured checks before using this report as a physical-validation checklist."
            },
            status = if (ready) VerificationResultStatus.PASSED else VerificationResultStatus.BLOCKED,
            evidenceLevel = VerificationEvidenceLevel.READY_FOR_PHYSICAL_VALIDATION,
            source = "ARES evidence boundary",
            advancedDetails = "This item never claims that a physical robot was tested.",
        )

        return RobotVerificationReport(
            projectPath = root.path,
            league = league,
            provenance = provenance,
            items = items.sortedWith(compareBy({ it.layer.ordinal }, { it.id })),
        )
    }

    private fun aggregateLayer(
        id: String,
        layer: VerificationLayer,
        title: String,
        explanation: String,
        results: List<TestCaseResult>,
        buildExitCode: Int,
    ): VerificationReportItem {
        val status = when {
            results.any { it.status == VerificationResultStatus.FAILED } -> VerificationResultStatus.FAILED
            buildExitCode != 0 -> VerificationResultStatus.BLOCKED
            results.isNotEmpty() && results.all { it.status == VerificationResultStatus.PASSED } -> VerificationResultStatus.PASSED
            else -> VerificationResultStatus.NOT_RUN
        }
        return VerificationReportItem(
            id = id,
            layer = layer,
            title = title,
            explanation = explanation,
            status = status,
            evidenceLevel = if (layer == VerificationLayer.SIMULATOR) {
                VerificationEvidenceLevel.SIMULATION_VERIFIED
            } else {
                VerificationEvidenceLevel.COMPILED_SUCCESSFULLY
            },
            source = "Independent project tests",
            advancedDetails = if (results.isEmpty()) {
                "No test result XML was available for this layer."
            } else {
                "${results.count { it.status == VerificationResultStatus.PASSED }} passed; " +
                    "${results.count { it.status == VerificationResultStatus.FAILED }} failed; " +
                    "${results.count { it.status == VerificationResultStatus.NOT_RUN }} skipped."
            },
        )
    }

    private fun loadSubsystemDocuments(root: File): List<SubsystemLoad> =
        File(root, ".ares/subsystems").listFiles { file -> file.isFile && file.extension == "aressubsystem" }
            ?.sortedBy { it.name }
            ?.map { file ->
                val result = runCatching { SubsystemDocumentCodec.decode(file.readText()) }
                SubsystemLoad(file, result.getOrNull(), result.exceptionOrNull()?.message)
            }
            .orEmpty()

    private fun loadProjectConfigurationItems(root: File): List<VerificationReportItem> = buildList {
        if (File(root, ".ares/project.json").isFile) {
            addDecodedConfiguration(
                id = "project.identity.configuration",
                title = "Project identity and robot footprint",
                relativePath = ".ares/project.json",
                root = root,
            ) { AresProjectMetadataCodec.decode(it) }
        }

        File(root, ".ares/drivetrains").canonicalFile
            .listFiles { file -> file.isFile && file.extension == "aresdrivetrain" }
            ?.sortedBy { it.name }
            .orEmpty()
            .forEach { file ->
                addDecodedConfiguration(
                    id = "drivetrain.${file.nameWithoutExtension}.configuration",
                    title = "Drivetrain configuration",
                    relativePath = root.toPath().relativize(file.toPath()).toString().replace('\\', '/'),
                    root = root,
                ) { DrivetrainDocumentCodec.decode(it) }
            }

        File(root, ".ares/superstructures").canonicalFile
            .listFiles { file -> file.isFile && file.extension == "aressuperstructure" }
            ?.sortedBy { it.name }
            .orEmpty()
            .forEach { file ->
                addDecodedConfiguration(
                    id = "superstructure.${file.nameWithoutExtension}.configuration",
                    title = "Superstructure configuration",
                    relativePath = root.toPath().relativize(file.toPath()).toString().replace('\\', '/'),
                    root = root,
                ) { SuperstructureDocumentCodec.decode(it) }
            }

        val routineFiles = File(root, ".ares/routines").canonicalFile
            .listFiles { file -> file.isFile && file.extension == "aresroutine" }
            ?.sortedBy { it.name }
            .orEmpty()
        val routineResults = routineFiles.map { file -> file to runCatching { AresRoutineCodec.decode(file.readText()) } }
        val decodedRoutines = routineResults.mapNotNull { (_, result) -> result.getOrNull() }
        val routineDecodeFailures = routineResults.mapNotNull { (file, result) ->
            result.exceptionOrNull()?.let { file.name to it }
        }
        val routineIssues = validateRoutineSet(decodedRoutines)
            .filter { it.severity == RoutineValidationSeverity.ERROR }
        val catalogFile = File(root, ".ares/autonomous-catalog.json")
        if (routineFiles.isEmpty() && !catalogFile.isFile) return@buildList
        val catalogResult = runCatching { AutonomousCatalogCodec.decode(catalogFile.readText()) }
        val catalogIssues = catalogResult.getOrNull()?.let { catalog ->
            validateAutonomousCatalog(catalog, decodedRoutines.mapTo(linkedSetOf()) { it.documentId })
                .filter { it.severity == RoutineValidationSeverity.ERROR }
        }.orEmpty()
        val autoDetails = buildList {
            routineDecodeFailures.forEach { (name, failure) -> add("$name: ${failure.message}") }
            routineIssues.forEach { add("${it.documentId}/${it.path}: ${it.message}") }
            catalogResult.exceptionOrNull()?.let { add("autonomous-catalog.json: ${it.message}") }
            catalogIssues.forEach { add("${it.path}: ${it.message}") }
        }
        add(
            VerificationReportItem(
                id = "autonomous.configuration",
                layer = VerificationLayer.CONFIGURATION,
                title = "Autonomous routines and choices",
                explanation = if (autoDetails.isEmpty()) {
                    "Every routine decodes, cross-routine calls resolve, and the autonomous picker references available routines."
                } else {
                    "Autonomous documents contain ${autoDetails.size} blocking configuration issue(s)."
                },
                status = if (autoDetails.isEmpty()) VerificationResultStatus.PASSED else VerificationResultStatus.FAILED,
                evidenceLevel = VerificationEvidenceLevel.CONFIGURATION_REVIEWED,
                source = ".ares/routines and .ares/autonomous-catalog.json",
                advancedDetails = autoDetails.joinToString("\n"),
            )
        )
    }

    private fun MutableList<VerificationReportItem>.addDecodedConfiguration(
        id: String,
        title: String,
        relativePath: String,
        root: File,
        decode: (String) -> Any,
    ) {
        val file = File(root, relativePath)
        val result = runCatching { decode(file.readText()) }
        add(
            VerificationReportItem(
                id = id,
                layer = VerificationLayer.CONFIGURATION,
                title = title,
                explanation = if (result.isSuccess) {
                    "The canonical Builder document satisfies its typed schema and validation rules."
                } else {
                    "The canonical Builder document is missing or invalid."
                },
                status = if (result.isSuccess) VerificationResultStatus.PASSED else VerificationResultStatus.FAILED,
                evidenceLevel = VerificationEvidenceLevel.CONFIGURATION_REVIEWED,
                source = relativePath,
                advancedDetails = result.exceptionOrNull()?.message.orEmpty(),
            )
        )
    }

    private fun resultRoots(root: File, league: League): List<Pair<VerificationLayer, File>> = when (league) {
        League.FTC -> listOf(
            VerificationLayer.PLATFORM_INTEGRATION to File(root, "TeamCode/build/test-results/testDebugUnitTest"),
            VerificationLayer.SIMULATOR to File(root, "simulator/build/test-results/test"),
        )
        League.FRC -> listOf(
            VerificationLayer.PLATFORM_INTEGRATION to File(root, "build/test-results/test"),
        )
    }

    private fun readTestCases(directory: File, layer: VerificationLayer): List<TestCaseResult> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles { file -> file.isFile && file.extension.equals("xml", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.flatMap { file -> parseTestFile(file, layer) }
            .orEmpty()
    }

    private fun parseTestFile(file: File, layer: VerificationLayer): List<TestCaseResult> = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("testcase")
        buildList {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? org.w3c.dom.Element ?: continue
                val failed = element.getElementsByTagName("failure").length > 0 || element.getElementsByTagName("error").length > 0
                val skipped = element.getElementsByTagName("skipped").length > 0
                add(
                    TestCaseResult(
                        className = element.getAttribute("classname"),
                        name = element.getAttribute("name").removeSuffix("()"),
                        layer = layer,
                        status = when {
                            failed -> VerificationResultStatus.FAILED
                            skipped -> VerificationResultStatus.NOT_RUN
                            else -> VerificationResultStatus.PASSED
                        },
                        details = "${file.name}: ${element.getAttribute("name")}",
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private data class TestCaseResult(
        val className: String,
        val name: String,
        val layer: VerificationLayer,
        val status: VerificationResultStatus,
        val details: String,
    )

    private data class SubsystemLoad(
        val file: File,
        val document: SubsystemDocument?,
        val error: String?,
    )

    private data class ProjectGeneratedCheck(
        val id: String,
        val methodName: String,
        val title: String,
        val explanation: String,
    )

    private const val PROJECT_GENERATED_TEST_CLASS = ".GeneratedAresProjectContractTest"
    private val PROJECT_GENERATED_CHECKS = listOf(
        ProjectGeneratedCheck(
            id = "project.identity.generated-contract",
            methodName = ProjectGeneratedTestNames.PROJECT_IDENTITY,
            title = "Generated project identity contract",
            explanation = "The generated suite decoded the canonical project identity and verified its robot and field dimensions.",
        ),
        ProjectGeneratedCheck(
            id = "project.drivetrain.generated-contract",
            methodName = ProjectGeneratedTestNames.DRIVETRAIN_SAFETY,
            title = "Generated drivetrain safety contract",
            explanation = "The generated suite verified every GUI-authored drivetrain document and its fail-closed safety rules.",
        ),
        ProjectGeneratedCheck(
            id = "project.controls.generated-contract",
            methodName = ProjectGeneratedTestNames.CONTROLS,
            title = "Generated control bindings contract",
            explanation = "The generated suite resolved controller inputs against typed drivetrain, subsystem, routine, and action targets.",
        ),
        ProjectGeneratedCheck(
            id = "project.autonomous.generated-contract",
            methodName = ProjectGeneratedTestNames.AUTONOMOUS,
            title = "Generated autonomous graph contract",
            explanation = "The generated suite verified routine references and autonomous choices without treating compilation as simulated execution.",
        ),
        ProjectGeneratedCheck(
            id = "project.superstructure.generated-contract",
            methodName = ProjectGeneratedTestNames.SUPERSTRUCTURE,
            title = "Generated superstructure contract",
            explanation = "The generated suite verified presets, interlocks, subsystem fields, and named actions referenced by GUI-authored superstructures.",
        ),
    )
}
