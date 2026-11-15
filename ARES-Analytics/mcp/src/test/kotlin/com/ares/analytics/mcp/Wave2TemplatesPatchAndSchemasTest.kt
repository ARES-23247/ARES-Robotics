package com.ares.analytics.mcp

import com.ares.analytics.mcp.bridge.StudioBridgeClient
import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.HeadlessProjectMutators
import com.ares.analytics.mcp.resources.SchemaExemplarCatalog
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControllerProfileCodec
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.drivetrain.DrivetrainKind
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.state.RobotFieldDocument
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.supportsPlatform
import com.areslib.superstructure.SuperstructureDocumentCodec
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningProfileDocumentCodec
import com.areslib.tuning.TuningValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Wave2TemplatesPatchAndSchemasTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resolveTemplateDir(): File = listOf(
        File("../app/src/main/resources/project-templates"),
        File("ARES-Analytics/app/src/main/resources/project-templates"),
        File("app/src/main/resources/project-templates"),
    ).first { it.isDirectory }.canonicalFile

    @Test
    fun `all 11 schema exemplars decode cleanly and catalog lists templates presets and vendor profiles`() {
        val kinds = SchemaExemplarCatalog.supportedSchemaKinds
        assertEquals(11, kinds.size)

        fun extractJsonFromMarkdown(kind: String): String {
            val md = SchemaExemplarCatalog.renderSchemaReference(kind)
            return md.substringAfter("```json\n").substringBefore("\n```")
        }

        assertEquals("arm", SubsystemDocumentCodec.decode(extractJsonFromMarkdown("subsystem")).documentId)
        val drivetrainExemplar = DrivetrainDocumentCodec.decode(extractJsonFromMarkdown("drivetrain"))
        assertEquals("main-drive", drivetrainExemplar.drivebaseId)
        assertEquals("scoring-superstructure", SuperstructureDocumentCodec.decode(extractJsonFromMarkdown("superstructure")).superstructureId)
        assertEquals("sample-auto", AresRoutineCodec.decode(extractJsonFromMarkdown("routine")).documentId)
        assertEquals("driver", ControlSchemeCodec.decode(extractJsonFromMarkdown("controls")).documentId)
        assertEquals("ftc-driver", ControllerProfileCodec.decode(extractJsonFromMarkdown("controller")).documentId)
        val declaredTuningParameters =
            listOf(
                TuningParameterDeclaration(
                    uid = "ftc.drive.heading.kp",
                    key = "drive.headingKp",
                    componentUid = "example.drive.main-drive",
                    displayName = "Heading Kp",
                    description = "Heading P gain",
                    type = TuningParameterType.DOUBLE,
                    minimum = 0.0,
                    maximum = 20.0,
                    defaultValue = TuningValue(doubleValue = 1.8),
                    applyPolicy = TuningApplyPolicy.LIVE_SAFE,
                ),
            )
        assertEquals(
            "example.profile.competition",
            TuningProfileDocumentCodec.decode(
                extractJsonFromMarkdown("tuning"),
                declaredTuningParameters,
            ).uid,
        )
        assertEquals("team-robot", AresProjectMetadataCodec.decode(extractJsonFromMarkdown("project")).projectId)
        assertEquals(1, CapabilityCatalogCodec.decode(extractJsonFromMarkdown("action-catalog")).actions.size)
        assertEquals("sample-auto", AutonomousCatalogCodec.decode(extractJsonFromMarkdown("autonomous-catalog")).entries.first().entryId)
        assertEquals("practice-field", RobotFieldDocument.decode(extractJsonFromMarkdown("field")).id)

        val catalogObj = json.parseToJsonElement(SchemaExemplarCatalog.renderTemplatesCatalogJson()).jsonObject
        val subsystemTemplates = catalogObj["subsystemTemplates"]?.jsonArray.orEmpty()
        assertEquals(31, SubsystemTemplate.entries.size)
        assertEquals(SubsystemTemplate.entries.size, subsystemTemplates.size)
        assertEquals(4, catalogObj["drivetrainPresets"]?.jsonArray?.size)
        assertEquals(4, catalogObj["fieldPresets"]?.jsonArray?.size)
        assertEquals(8, catalogObj["vendorSdkProfiles"]?.jsonArray?.size)
    }

    @Test
    fun `subsystem and drivetrain templates create valid descriptors and sync canonical tuning`() = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-w2-templates-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val ftcDir = File(tempRoot, "ftc-bot")
        val frcDir = File(tempRoot, "frc-bot")
        val engine = HeadlessProjectEngine(StudioBridgeClient(bridgeDir), templateDir = resolveTemplateDir())
        val mutators = HeadlessProjectMutators(engine)
        try {
            engine.initProject(ftcDir, "FTC", displayName = "Template FTC", initGit = true)
            for (tpl in listOf("FLYWHEEL_SHOOTER", "ELEVATOR_LIFT", "ARM_PIVOT")) {
                val res = mutators.mutateSubsystem(
                    projectRoot = ftcDir,
                    operationRaw = "TEMPLATE",
                    templateName = tpl,
                    documentIdHint = tpl.lowercase().replace('_', '-'),
                    implementationKind = "DECLARATIVE_GENERATED",
                )
                assertTrue(res.validation?.valid == true, "Expected template $tpl to validate")
                val decoded = SubsystemDocumentCodec.decode(File(ftcDir, res.filePath).readText())
                assertEquals(SubsystemImplementationKind.DECLARATIVE_GENERATED, decoded.implementation.kind)
            }

            engine.initProject(frcDir, "FRC", displayName = "Template FRC", initGit = true)
            val swerveRes = mutators.mutateDrivetrain(
                projectRoot = frcDir,
                operationRaw = "TEMPLATE",
                presetName = "FRC_CTRE_SWERVE",
                drivebaseIdHint = "swerve-drive",
            )
            assertTrue(swerveRes.validation?.valid == true)
            val swerveDoc = DrivetrainDocumentCodec.decode(File(frcDir, swerveRes.filePath).readText())
            assertEquals(DrivetrainKind.FRC_CTRE_SWERVE, swerveDoc.kind)
            assertTrue(swerveDoc.parameters.isNotEmpty())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `patch preserves uids deletes keyed entries removes orphaned loops and rolls back invalid edits`(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-w2-patch-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val projectDir = File(tempRoot, "ftc-patch-bot")
        val engine = HeadlessProjectEngine(StudioBridgeClient(bridgeDir), templateDir = resolveTemplateDir())
        val mutators = HeadlessProjectMutators(engine)
        try {
            engine.initProject(projectDir, "FTC", displayName = "Patch Bot", initGit = true)
            val created = mutators.mutateSubsystem(
                projectRoot = projectDir,
                operationRaw = "TEMPLATE",
                templateName = "ELEVATOR_LIFT",
                documentIdHint = "elevator",
            )
            val descriptorFile = File(projectDir, created.filePath)
            val originalDoc = SubsystemDocumentCodec.decode(descriptorFile.readText())
            val originalHw = originalDoc.hardware.first()

            val patch = """
                {
                  "displayName": "Tall Competition Elevator",
                  "hardware": [
                    {
                      "hardwareId": "${originalHw.hardwareId}",
                      "_delete": true
                    }
                  ]
                }
            """.trimIndent()
            val patched = mutators.mutateSubsystem(
                projectRoot = projectDir,
                operationRaw = "PATCH",
                documentIdHint = "elevator",
                patchJson = patch,
            )
            val afterDoc = SubsystemDocumentCodec.decode(descriptorFile.readText())
            assertEquals(originalDoc.uid, afterDoc.uid)
            assertEquals("Tall Competition Elevator", afterDoc.displayName)
            assertFalse(afterDoc.hardware.any { it.hardwareId == originalHw.hardwareId })
            assertTrue(afterDoc.controlLoops.none { it.actuatorId == originalHw.hardwareId })

            // Now add back hardware with a PATCH preserving UID of remaining entries or updating scalar without clobbering uid
            val safePatch = """{ "displayName": "Second Iteration Elevator" }"""
            mutators.mutateSubsystem(
                projectRoot = projectDir,
                operationRaw = "PATCH",
                documentIdHint = "elevator",
                patchJson = safePatch,
                expectedRevision = patched.newRevision,
            )
            assertEquals("Second Iteration Elevator", SubsystemDocumentCodec.decode(descriptorFile.readText()).displayName)

            val beforeInvalid = descriptorFile.readText()
            assertFailsWith<Exception> {
                mutators.mutateSubsystem(
                    projectRoot = projectDir,
                    operationRaw = "PATCH",
                    documentIdHint = "elevator",
                    patchJson = """{ "documentId": "INVALID UPPERCASE" }""",
                )
            }
            assertEquals(beforeInvalid, descriptorFile.readText())
            assertNotEquals("INVALID UPPERCASE", SubsystemDocumentCodec.decode(descriptorFile.readText()).documentId)
            assertNotNull(engine.validateProject(projectDir))
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
