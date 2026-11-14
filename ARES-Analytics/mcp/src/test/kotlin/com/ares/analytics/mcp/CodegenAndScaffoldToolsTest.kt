package com.ares.analytics.mcp

import com.ares.analytics.mcp.audit.KotlinInvariantAuditor
import com.ares.analytics.mcp.bridge.StudioBridgeClient
import com.ares.analytics.mcp.engine.ConfirmationTokenManager
import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.HeadlessProjectMutators
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodegenAndScaffoldToolsTest {

    private fun resolveTemplateDir(): File = listOf(
        File("../app/src/main/resources/project-templates"),
        File("ARES-Analytics/app/src/main/resources/project-templates"),
        File("app/src/main/resources/project-templates"),
    ).first { it.isDirectory }.canonicalFile

    @Test
    fun `reconcileCodegen previews applies and preserves user-owned files`() = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-mcp-codegen-").toFile()
        val ftcDir = File(tempRoot, "ftc-project")
        try {
            val engine = HeadlessProjectEngine(
                bridgeClient = StudioBridgeClient(File(tempRoot, "bridge")),
                tokens = ConfirmationTokenManager(),
                templateDir = resolveTemplateDir(),
            )
            engine.initProject(
                targetDir = ftcDir,
                leagueRaw = "FTC",
                displayName = "Codegen FTC Bot",
            )
            val userOwnedFile = File(ftcDir, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/CustomUserFile.kt")
            userOwnedFile.parentFile.mkdirs()
            val sentinelContent = "// ARES OWNERSHIP: USER-OWNED\npackage org.firstinspires.ftc.teamcode.subsystems\nclass CustomUserFile\n"
            userOwnedFile.writeText(sentinelContent)

            val preview = engine.reconcileCodegen(ftcDir, modeRaw = "PREVIEW")
            assertEquals("PREVIEW", preview.mode)
            assertTrue(preview.planText.isNotBlank())

            val apply = engine.reconcileCodegen(ftcDir, modeRaw = "APPLY")
            assertEquals("APPLY", apply.mode)
            assertEquals(sentinelContent, userOwnedFile.readText())
            assertTrue(
                File(ftcDir, "TeamCode/build/generated/ares/main/kotlin/org/firstinspires/ftc/teamcode/generated/GeneratedAresProject.kt").isFile,
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `all 6 scaffold kinds render zero-audit-finding kotlin enforce overwrite token and refuse generated files`() = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-mcp-scaffold-").toFile()
        val ftcDir = File(tempRoot, "ftc-project")
        val frcDir = File(tempRoot, "frc-project")
        try {
            val engine = HeadlessProjectEngine(
                bridgeClient = StudioBridgeClient(File(tempRoot, "bridge")),
                tokens = ConfirmationTokenManager(),
                templateDir = resolveTemplateDir(),
            )
            val mutators = HeadlessProjectMutators(engine)
            engine.initProject(ftcDir, "FTC", displayName = "Scaffold FTC")
            engine.initProject(frcDir, "FRC", displayName = "Scaffold FRC")

            val ftcKinds = listOf(
                "HARDWARE_IO" to "ArmHardwareIo",
                "SEASON_REDUX_SLICE" to "ScoringSlice",
                "TELEOP_OPMODE" to "MainTeleOp",
                "AUTO_OPMODE" to "SpecimenAuto",
                "UNIT_TEST" to "ArmControllerTest",
            )
            for ((kind, name) in ftcKinds) {
                val preview = mutators.scaffoldKotlin(ftcDir, "FTC", kind, name, modeRaw = "PREVIEW")
                assertFalse(File(ftcDir, preview.relativePath).exists(), "Preview must not write $kind")
                val findings = KotlinInvariantAuditor.auditText(preview.relativePath, preview.generatedCode)
                assertTrue(findings.isEmpty(), "Expected 0 audit findings for FTC $kind, found $findings")

                val apply = mutators.scaffoldKotlin(ftcDir, "FTC", kind, name, modeRaw = "APPLY")
                val written = File(ftcDir, apply.relativePath)
                assertTrue(written.isFile)
                assertTrue(written.readText().contains(ConfirmationTokenManager.USER_OWNED_MARKER))
            }

            // Second apply over existing USER-OWNED file requires two-step confirmationToken
            val overwritePreview = mutators.scaffoldKotlin(ftcDir, "FTC", "HARDWARE_IO", "ArmHardwareIo", modeRaw = "PREVIEW")
            assertTrue(overwritePreview.targetExists)
            val token = assertNotNull(overwritePreview.confirmationToken)

            assertFailsWith<IllegalStateException> {
                mutators.scaffoldKotlin(ftcDir, "FTC", "HARDWARE_IO", "ArmHardwareIo", modeRaw = "APPLY")
            }
            val overwriteApply = mutators.scaffoldKotlin(
                ftcDir,
                "FTC",
                "HARDWARE_IO",
                "ArmHardwareIo",
                modeRaw = "APPLY",
                confirmationToken = token,
            )
            assertTrue(overwriteApply.targetExists)

            // Refuses to overwrite GENERATED DO NOT EDIT file
            val generatedTarget = File(ftcDir, "TeamCode/build/generated/ares/main/kotlin/org/firstinspires/ftc/teamcode/generated/GeneratedDoNotTouch.kt")
            generatedTarget.parentFile.mkdirs()
            generatedTarget.writeText("${ConfirmationTokenManager.GENERATED_DO_NOT_EDIT_MARKER}\nclass GeneratedDoNotTouch\n")
            assertFailsWith<IllegalArgumentException> {
                mutators.scaffoldKotlin(
                    ftcDir,
                    "FTC",
                    "HARDWARE_IO",
                    "GeneratedDoNotTouch",
                    modeRaw = "APPLY",
                    relativePathOverride = "TeamCode/build/generated/ares/main/kotlin/org/firstinspires/ftc/teamcode/generated/GeneratedDoNotTouch.kt",
                )
            }

            val frcKinds = listOf(
                "HARDWARE_IO" to "ElevatorHardwareIo",
                "TIMED_ROBOT_HOOK" to "RobotLoopHook",
                "UNIT_TEST" to "ElevatorTest",
            )
            for ((kind, name) in frcKinds) {
                val apply = mutators.scaffoldKotlin(frcDir, "FRC", kind, name, modeRaw = "APPLY")
                val written = File(frcDir, apply.relativePath)
                assertTrue(written.isFile)
                val findings = KotlinInvariantAuditor.auditText(apply.relativePath, written.readText())
                assertTrue(findings.isEmpty(), "Expected 0 audit findings for FRC $kind, found $findings")
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
