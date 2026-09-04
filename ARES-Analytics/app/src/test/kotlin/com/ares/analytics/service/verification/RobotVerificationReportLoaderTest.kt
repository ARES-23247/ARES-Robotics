package com.ares.analytics.service.verification

import com.ares.analytics.shared.models.League
import com.areslib.codegen.ProjectGeneratedTestNames
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.subsystem.subsystemVerificationContract
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobotVerificationReportLoaderTest {
    @Test
    fun `report joins generated behavior platform simulator and build evidence`() {
        val root = Files.createTempDirectory("ares-verification-report").toFile()
        try {
            val document = SubsystemTemplates.create(
                template = SubsystemTemplate.INDICATOR_LIGHT_PWM,
                platform = SubsystemPlatform.FTC,
                documentId = "indicator-lights",
                displayName = "Indicator lights",
                kotlinTypeName = "IndicatorLights",
            )
            val descriptor = root.resolve(".ares/subsystems/indicator-lights.aressubsystem")
            descriptor.parentFile.mkdirs()
            descriptor.writeText(SubsystemDocumentCodec.encode(document))

            writeResults(
                root.resolve("TeamCode/build/test-results/testDebugUnitTest/TEST-generated.xml"),
                className = "org.firstinspires.ftc.teamcode.subsystems.indicator_lights.IndicatorLightsGeneratedTest",
                methods = subsystemVerificationContract(document).mapNotNull { it.testMethodName },
            )
            writeProjectResults(root)
            writeResults(
                root.resolve("TeamCode/build/test-results/testDebugUnitTest/TEST-platform.xml"),
                className = "org.firstinspires.ftc.teamcode.AresLifecycleIntegrationTest",
                methods = listOf("platform lifecycle stays disabled"),
            )
            writeResults(
                root.resolve("simulator/build/test-results/test/TEST-simulator.xml"),
                className = "org.firstinspires.ftc.teamcode.FtcSimulatorIntegrationTest",
                methods = listOf("simulator starts and stops cleanly"),
            )

            val report = RobotVerificationReportLoader.load(root, League.FTC, 0, provenance(0))

            assertEquals(0, report.failedCount)
            assertEquals(0, report.blockedCount)
            assertTrue(report.readyForPhysicalValidation)
            assertTrue(report.items.any { it.id == "indicator-lights.actions.generated" && it.status == VerificationResultStatus.PASSED })
            assertTrue(report.items.any {
                it.id == "project.controls.generated-contract" &&
                    it.status == VerificationResultStatus.PASSED &&
                    it.evidenceLevel == VerificationEvidenceLevel.GENERATED_CONTRACT_VERIFIED
            })
            assertTrue(report.items.any { it.layer == VerificationLayer.SIMULATOR && it.status == VerificationResultStatus.PASSED })
            assertTrue(report.items.any { it.layer == VerificationLayer.PHYSICAL_VALIDATION && it.status == VerificationResultStatus.PASSED })
            assertTrue(report.items.any { it.id == "project.physical-checklist" && it.status == VerificationResultStatus.NOT_RUN })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed current build blocks stale passing generated platform and simulator evidence`() {
        val root = Files.createTempDirectory("ares-verification-stale-results").toFile()
        try {
            val document = SubsystemTemplates.create(
                template = SubsystemTemplate.INDICATOR_LIGHT_PWM,
                platform = SubsystemPlatform.FTC,
                documentId = "indicator-lights",
                displayName = "Indicator lights",
                kotlinTypeName = "IndicatorLights",
            )
            val descriptor = root.resolve(".ares/subsystems/indicator-lights.aressubsystem")
            descriptor.parentFile.mkdirs()
            descriptor.writeText(SubsystemDocumentCodec.encode(document))
            writeResults(
                root.resolve("TeamCode/build/test-results/testDebugUnitTest/TEST-generated.xml"),
                className = "org.firstinspires.ftc.teamcode.subsystems.indicator_lights.IndicatorLightsGeneratedTest",
                methods = subsystemVerificationContract(document).mapNotNull { it.testMethodName },
            )
            writeProjectResults(root)
            writeResults(
                root.resolve("TeamCode/build/test-results/testDebugUnitTest/TEST-platform.xml"),
                className = "org.firstinspires.ftc.teamcode.AresLifecycleIntegrationTest",
                methods = listOf("platform lifecycle stays disabled"),
            )
            writeResults(
                root.resolve("simulator/build/test-results/test/TEST-simulator.xml"),
                className = "org.firstinspires.ftc.teamcode.FtcSimulatorIntegrationTest",
                methods = listOf("simulator starts and stops cleanly"),
            )

            val report = RobotVerificationReportLoader.load(root, League.FTC, 1, provenance(1))

            assertTrue(report.items.any {
                it.layer == VerificationLayer.GENERATED_BEHAVIOR && it.status == VerificationResultStatus.BLOCKED
            })
            assertTrue(report.items.any { it.id == "project.platform-integration" && it.status == VerificationResultStatus.BLOCKED })
            assertTrue(report.items.any { it.id == "project.simulator" && it.status == VerificationResultStatus.BLOCKED })
            assertFalse(report.readyForPhysicalValidation)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid subsystem descriptor is shown as failed configuration evidence`() {
        val root = Files.createTempDirectory("ares-verification-invalid-descriptor").toFile()
        try {
            val descriptor = root.resolve(".ares/subsystems/broken.aressubsystem")
            descriptor.parentFile.mkdirs()
            descriptor.writeText("not: valid: subsystem: yaml")

            val report = RobotVerificationReportLoader.load(root, League.FTC, 1, provenance(1))

            assertTrue(report.items.any {
                it.id == "subsystem.broken.configuration" &&
                    it.layer == VerificationLayer.CONFIGURATION &&
                    it.status == VerificationResultStatus.FAILED
            })
            assertFalse(report.readyForPhysicalValidation)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing generated result is visible instead of being inferred from a successful build`() {
        val root = Files.createTempDirectory("ares-verification-missing").toFile()
        try {
            val document = SubsystemTemplates.create(
                template = SubsystemTemplate.PRISM_LED_DRIVER,
                platform = SubsystemPlatform.FTC,
                documentId = "prism",
                displayName = "Prism",
                kotlinTypeName = "Prism",
            )
            val descriptor = root.resolve(".ares/subsystems/prism.aressubsystem")
            descriptor.parentFile.mkdirs()
            descriptor.writeText(SubsystemDocumentCodec.encode(document))

            val report = RobotVerificationReportLoader.load(root, League.FTC, 0, provenance(0))

            assertTrue(report.failedCount > 0)
            assertTrue(report.items.any {
                it.layer == VerificationLayer.GENERATED_BEHAVIOR && it.status == VerificationResultStatus.FAILED
            })
            assertTrue(report.items.any {
                it.layer == VerificationLayer.PHYSICAL_VALIDATION && it.status == VerificationResultStatus.BLOCKED
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `xrp python junit evidence joins generated contracts without pretending it is physical proof`() {
        val root = Files.createTempDirectory("ares-xrp-verification-report").toFile()
        try {
            val document = SubsystemTemplates.create(
                template = SubsystemTemplate.DIGITAL_OUTPUT,
                platform = SubsystemPlatform.XRP,
                documentId = "status-output",
                displayName = "Status output",
                kotlinTypeName = "StatusOutput",
            )
            val descriptor = root.resolve(".ares/subsystems/status-output.aressubsystem")
            descriptor.parentFile.mkdirs()
            descriptor.writeText(SubsystemDocumentCodec.encode(document))
            writeResults(
                root.resolve("build/test-results/test/TEST-ares-xrp-generated.xml"),
                className = "test_generated_safety.GeneratedSafetyTest",
                methods = listOf(
                    "test_generated_project_identity_and_footprint_are_valid",
                    "test_generated_drivetrain_safety_contract_is_valid",
                    "test_generated_controls_resolve_typed_project_targets",
                    "test_generated_autonomous_graph_is_closed",
                    "test_generated_superstructure_references_and_interlocks_are_valid",
                    "test_generated_subsystems_start_and_stop_neutral",
                    "test_generated_subsystems_latch_failed_writes",
                    "test_declared_target_limits_reject_out_of_range_values",
                    "test_generated_subsystems_fail_closed_on_invalid_feedback",
                    "test_generated_subsystem_actions_update_state",
                    "test_generated_subsystems_recover_only_after_successful_neutral",
                ),
            )
            writeResults(
                root.resolve("build/test-results/test/TEST-ares-xrp-platform.xml"),
                className = "test_generated_project.GeneratedProjectTest",
                methods = listOf("test_stock_xrp_output_buzzer_and_full_imu_adapters"),
            )
            writeResults(
                root.resolve("build/test-results/test/TEST-ares-xrp-simulator.xml"),
                className = "test_xrp_simulator.XrpSimulatorIntegrationTest",
                methods = listOf(
                    "test_simulator_robot_accepts_leased_drive_and_updates_odometry",
                    "test_simulator_robot_neutralizes_after_control_lease_loss",
                ),
            )

            val report = RobotVerificationReportLoader.load(root, League.XRP, 0, provenance(0))

            assertEquals(
                0,
                report.failedCount,
                report.items.filter { it.status == VerificationResultStatus.FAILED }
                    .joinToString("\n") { "${it.id}: ${it.advancedDetails}" },
            )
            assertEquals(0, report.blockedCount)
            assertTrue(report.readyForPhysicalValidation)
            assertTrue(report.items.any {
                it.id == "status-output.actions.generated" &&
                    it.status == VerificationResultStatus.PASSED
            })
            assertTrue(report.items.any {
                it.id == "project.platform-integration" &&
                    it.status == VerificationResultStatus.PASSED
            })
            assertTrue(report.items.any {
                it.id == "project.simulator" &&
                    it.status == VerificationResultStatus.PASSED &&
                    it.evidenceLevel == VerificationEvidenceLevel.SIMULATION_VERIFIED
            })
            assertTrue(report.items.any {
                it.id == "project.physical-checklist" &&
                    it.status == VerificationResultStatus.NOT_RUN
            })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeResults(file: java.io.File, className: String, methods: List<String>) {
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                appendLine("<testsuite tests=\"${methods.size}\" failures=\"0\" errors=\"0\">")
                methods.forEach { method ->
                    appendLine("  <testcase classname=\"$className\" name=\"${method.xmlEscape()}\" time=\"0.001\"/>")
                }
                appendLine("</testsuite>")
            },
        )
    }

    private fun writeProjectResults(root: java.io.File) {
        writeResults(
            root.resolve("TeamCode/build/test-results/testDebugUnitTest/TEST-project-generated.xml"),
            className = "org.firstinspires.ftc.teamcode.generated.GeneratedAresProjectContractTest",
            methods = listOf(
                ProjectGeneratedTestNames.PROJECT_IDENTITY,
                ProjectGeneratedTestNames.DRIVETRAIN_SAFETY,
                ProjectGeneratedTestNames.CONTROLS,
                ProjectGeneratedTestNames.AUTONOMOUS,
                ProjectGeneratedTestNames.SUPERSTRUCTURE,
            ),
        )
    }

    private fun provenance(exitCode: Int) = VerificationRunProvenance(
        runId = "test-run-$exitCode",
        canonicalContentHash = "0".repeat(64),
        aresVersion = "test",
        generatorVersion = "test",
        studioVersion = "test",
        command = listOf("gradlew", "test"),
        startedAt = "2026-01-01T00:00:00Z",
        finishedAt = "2026-01-01T00:00:01Z",
        buildExitCode = exitCode,
    )

    private fun String.xmlEscape(): String = replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
}
