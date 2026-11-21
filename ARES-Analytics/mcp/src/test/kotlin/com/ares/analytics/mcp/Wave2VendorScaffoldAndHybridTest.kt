package com.ares.analytics.mcp

import com.ares.analytics.mcp.audit.KotlinInvariantAuditor
import com.ares.analytics.mcp.bridge.StudioBridgeClient
import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.HeadlessProjectMutators
import com.ares.analytics.mcp.scaffold.HybridRegistrationRequest
import com.ares.analytics.mcp.scaffold.VendorSdkProfile
import com.ares.analytics.mcp.scaffold.VendorSdkScaffoldRenderer
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.project.AresProjectAuthoringModel
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.SubsystemSimulationSupport
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Wave2VendorScaffoldAndHybridTest {

    private fun resolveTemplateDir(): File = listOf(
        File("../app/src/main/resources/project-templates"),
        File("ARES-Analytics/app/src/main/resources/project-templates"),
        File("app/src/main/resources/project-templates"),
    ).first { it.isDirectory }.canonicalFile

    @Test
    fun `all 8 vendor SDK profiles render 4 USER-OWNED files passing static audit cleanly`() {
        assertEquals(8, VendorSdkProfile.entries.size)
        for (profile in VendorSdkProfile.entries) {
            val baseName = "Sample" + profile.name.lowercase()
                .split('_')
                .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
            val pkg = "org.team.extensions.${baseName.lowercase()}"
            val bundle = VendorSdkScaffoldRenderer.renderBundle(profile, pkg, baseName)
            assertEquals(4, bundle.files.size, "Expected 4 files for ${profile.name}")
            for (rendered in bundle.files) {
                assertTrue(rendered.content.startsWith("// ARES OWNERSHIP: USER-OWNED"))
                val findings = KotlinInvariantAuditor.auditText(rendered.relativePath, rendered.content)
                assertTrue(
                    findings.isEmpty(),
                    "Expected 0 static findings for ${profile.name} (${rendered.relativePath}), found: $findings",
                )
            }
        }
    }

    @Test
    fun `vendor bundle and registerExistingHybrid update descriptor authoringModel catalog and TeamRobotExtensions`() = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-w2-hybrid-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val ftcDir = File(tempRoot, "ftc-hybrid")
        val frcDir = File(tempRoot, "frc-hybrid")
        val engine = HeadlessProjectEngine(StudioBridgeClient(bridgeDir), templateDir = resolveTemplateDir())
        val mutators = HeadlessProjectMutators(engine)
        try {
            // FTC vendor bundle + atomic hybrid registration
            engine.initProject(ftcDir, "FTC", displayName = "FTC Hybrid", initGit = true)
            val extFile = File(ftcDir, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/extensions/TeamRobotExtensions.kt")
            assertTrue(extFile.isFile)
            val userMarkerComment = "// CUSTOM USER HELPER PRESERVED\nfun customTeamHelper(): Int = 42\n"
            extFile.appendText("\n$userMarkerComment")

            val ftcBundleRes = mutators.hybridRegistrar.scaffoldFromTool(
                projectRoot = ftcDir,
                leagueRaw = "FTC",
                kindRaw = "VENDOR_SUBSYSTEM_BUNDLE",
                name = "Intake",
                modeRaw = "APPLY",
                vendorProfile = "FTC_DCMOTOREX_PIDF",
                deviceNameOrCanId = "intakeMotor",
                registerHybrid = true,
                actionKeys = listOf("intake.spin", "intake.stop"),
            )
            assertEquals("intake", ftcBundleRes.registeredSubsystemId)
            assertEquals(4, ftcBundleRes.writtenPaths.count { it.endsWith(".kt") && !it.endsWith("TeamRobotExtensions.kt") })
            assertTrue(ftcBundleRes.writtenPaths.any { it.endsWith("TeamRobotExtensions.kt") })

            val subDoc = SubsystemDocumentCodec.decode(File(ftcDir, ".ares/subsystems/intake.aressubsystem").readText())
            assertEquals(SubsystemImplementationKind.HAND_AUTHORED, subDoc.implementation.kind)
            assertEquals(":TeamCode", subDoc.implementation.modulePath)
            assertEquals(SubsystemSimulationSupport.HAND_AUTHORED_MOCK, subDoc.implementation.simulation.support)

            assertEquals(
                AresProjectAuthoringModel.HYBRID,
                AresProjectMetadataCodec.decode(File(ftcDir, ".ares/project.json").readText()).authoringModel,
            )
            val catalogKeys = CapabilityCatalogCodec.decode(File(ftcDir, ".ares/action-catalog.json").readText())
                .actions.map { it.key }.toSet()
            assertTrue("intake.spin" in catalogKeys && "intake.stop" in catalogKeys)

            val extAfter = extFile.readText()
            assertTrue(extAfter.contains("fun customTeamHelper(): Int = 42"))
            assertTrue(extAfter.contains("AresHybridSubsystemIndex"))
            assertTrue(extAfter.contains("IntakeSubsystem"))

            assertTrue(engine.validateProject(ftcDir).valid)

            // FRC registerExistingHybrid over manually created files
            engine.initProject(frcDir, "FRC", displayName = "FRC Hybrid", initGit = true)
            val customRel = "src/main/kotlin/org/aresfirst/starter/frc/extensions/TurretSubsystem.kt"
            val customFile = File(frcDir, customRel).apply { parentFile.mkdirs() }
            customFile.writeText(
                """
                    |// ARES OWNERSHIP: USER-OWNED
                    |package org.aresfirst.starter.frc.extensions
                    |
                    |class TurretSubsystem {
                    |    fun stop() = Unit
                    |}
                """.trimMargin(),
            )
            val preview = mutators.hybridRegistrar.registerExistingHybrid(
                projectRoot = frcDir,
                modeRaw = "PREVIEW",
                request = HybridRegistrationRequest(
                    documentId = "turret",
                    displayName = "Turret",
                    kotlinTypeName = "TurretSubsystem",
                    sourceFiles = listOf(customRel),
                    subsystemClassName = "org.aresfirst.starter.frc.extensions.TurretSubsystem",
                    ioContractClassName = "org.aresfirst.starter.frc.extensions.TurretIO",
                    hardwareAdapterClassName = "org.aresfirst.starter.frc.extensions.FrcTurretIO",
                    mockAdapterClassName = "org.aresfirst.starter.frc.extensions.MockTurretIO",
                    actionKeys = listOf("turret.aim"),
                ),
            )
            val applyRes = mutators.hybridRegistrar.registerExistingHybrid(
                projectRoot = frcDir,
                modeRaw = "APPLY",
                request = HybridRegistrationRequest(
                    documentId = "turret",
                    displayName = "Turret",
                    kotlinTypeName = "TurretSubsystem",
                    sourceFiles = listOf(customRel),
                    subsystemClassName = "org.aresfirst.starter.frc.extensions.TurretSubsystem",
                    ioContractClassName = "org.aresfirst.starter.frc.extensions.TurretIO",
                    hardwareAdapterClassName = "org.aresfirst.starter.frc.extensions.FrcTurretIO",
                    mockAdapterClassName = "org.aresfirst.starter.frc.extensions.MockTurretIO",
                    actionKeys = listOf("turret.aim"),
                ),
                confirmationToken = preview.confirmationToken,
            )
            assertEquals("turret", applyRes.registeredSubsystemId)
            val turretDoc = SubsystemDocumentCodec.decode(File(frcDir, ".ares/subsystems/turret.aressubsystem").readText())
            assertTrue(turretDoc.implementation.modulePath?.startsWith(":") == true)
            assertTrue(engine.validateProject(frcDir).valid)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
