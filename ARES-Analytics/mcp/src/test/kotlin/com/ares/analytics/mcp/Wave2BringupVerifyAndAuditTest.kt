package com.ares.analytics.mcp

import com.ares.analytics.mcp.audit.AuditSeverity
import com.ares.analytics.mcp.audit.KotlinInvariantAuditor
import com.ares.analytics.mcp.bridge.StudioBridgeClient
import com.ares.analytics.mcp.bringup.HardwareBringupEngine
import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.HeadlessProjectMutators
import com.ares.analytics.mcp.verify.ProjectGradleVerifier
import com.ares.analytics.mcp.verify.VerificationLayers
import com.areslib.drivetrain.DrivetrainDocumentCodec
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Wave2BringupVerifyAndAuditTest {

    private fun resolveTemplateDir(): File = listOf(
        File("../app/src/main/resources/project-templates"),
        File("ARES-Analytics/app/src/main/resources/project-templates"),
        File("app/src/main/resources/project-templates"),
    ).first { it.isDirectory }.canonicalFile

    @Test
    fun `hardware bringup handles vendordeps read-only TunerConstants import and FTC REV Hub XML validation`() = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-w2-bringup-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val frcDir = File(tempRoot, "frc-bringup")
        val ftcDir = File(tempRoot, "ftc-bringup")
        val engine = HeadlessProjectEngine(StudioBridgeClient(bridgeDir), templateDir = resolveTemplateDir())
        val mutators = HeadlessProjectMutators(engine)
        val bringup = HardwareBringupEngine(engine, mutators.templateAndPatch)
        try {
            // FRC vendordeps + read-only TunerConstants import
            engine.initProject(frcDir, "FRC", displayName = "FRC Bringup", initGit = true)
            val installRes = bringup.execute(
                projectRoot = frcDir,
                operationRaw = "INSTALL_VENDORDEP",
                vendordepPreset = "PHOENIX6",
            )
            assertTrue(installRes.passed)
            assertEquals(VerificationLayers.PHYSICAL_VALIDATION_NOT_RUN, installRes.physicalValidationStatus)
            assertTrue(File(frcDir, "vendordeps/Phoenix6-26.1.1.json").isFile)

            val tunerJava = File(frcDir, "src/main/java/frc/robot/generated/TunerConstants.java").apply {
                parentFile.mkdirs()
            }
            val tunerOriginalText = """
                package frc.robot.generated;
                public class TunerConstants {
                    public static final String kCANBus = "canivore";
                    public static final int kPigeonId = 30;
                    public static final double kWheelRadius = 0.0508;
                    public static final double kSpeedAt12Volts = 4.73;
                    public static final double kDriveGearRatio = 6.12;
                    public static final double kSteerGearRatio = 21.42;
                    public static final int kFrontLeftDriveMotorId = 1;
                    public static final int kFrontLeftSteerMotorId = 2;
                    public static final int kFrontLeftEncoderId = 3;
                    public static final double kFrontLeftXPos = 0.28;
                    public static final double kFrontLeftYPos = 0.28;
                }
            """.trimIndent()
            tunerJava.writeText(tunerOriginalText)

            val importRes = bringup.execute(
                projectRoot = frcDir,
                operationRaw = "IMPORT_CTRE_TUNER_CONSTANTS",
            )
            assertTrue(importRes.passed)
            assertEquals(VerificationLayers.PHYSICAL_VALIDATION_NOT_RUN, importRes.physicalValidationStatus)
            assertEquals(tunerOriginalText, tunerJava.readText(), "TunerConstants.java must remain unmodified!")
            val swerveDoc = DrivetrainDocumentCodec.decode(
                File(frcDir, ".ares/drivetrains").listFiles { f -> f.extension == "aresdrivetrain" }!!.first().readText(),
            )
            assertEquals("src/main/java/frc/robot/generated/TunerConstants.java", swerveDoc.ctreImport?.vendorSourcePath)
            assertEquals("canivore", swerveDoc.ctreImport?.canBusName)

            // FTC REV Hub XML generation (with token overwrite protection) + validation
            engine.initProject(ftcDir, "FTC", displayName = "FTC Bringup", initGit = true)
            val xmlGen1 = bringup.execute(ftcDir, "GENERATE_FTC_HUB_XML")
            assertTrue(xmlGen1.passed)
            val generatedPath = xmlGen1.generatedPath!!
            assertTrue(File(ftcDir, generatedPath).isFile)

            val xmlPreview = bringup.execute(ftcDir, "GENERATE_FTC_HUB_XML")
            assertTrue(xmlPreview.pendingConfirmation)
            assertNotNull(xmlPreview.confirmationToken)
            val xmlApply2 = bringup.execute(ftcDir, "GENERATE_FTC_HUB_XML", confirmationToken = xmlPreview.confirmationToken)
            assertTrue(xmlApply2.passed)

            val badXml = """
                <Robot type="FirstInspires-FTC">
                  <LynxUsbDevice name="Control Hub Portal" serialNumber="DQ000001" parentModuleAddress="173">
                    <LynxModule name="Control Hub" port="173">
                      <NeveRest40Motor name="shooter" port="7" />
                    </LynxModule>
                  </LynxUsbDevice>
                </Robot>
            """.trimIndent()
            val invalidXmlRes = bringup.execute(ftcDir, "VALIDATE_FTC_HUB_XML", xmlContent = badXml)
            assertFalse(invalidXmlRes.passed)
            assertTrue(invalidXmlRes.issues.any { it.severity == "ERROR" })
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `static auditor enforces ARES-HW-009 through ARES-COORD-012`() = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-w2-audit-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val frcDir = File(tempRoot, "frc-audit")
        val engine = HeadlessProjectEngine(StudioBridgeClient(bridgeDir), templateDir = resolveTemplateDir())
        try {
            engine.initProject(frcDir, "FRC", displayName = "Audit Bot", initGit = true)
            val violatorRel = "src/main/kotlin/org/aresfirst/starter/frc/extensions/ViolatorSubsystem.kt"
            File(frcDir, violatorRel).apply {
                parentFile.mkdirs()
                writeText(
                    """
                        |package org.aresfirst.starter.frc.extensions
                        |import com.ctre.phoenix6.hardware.TalonFX
                        |import kotlinx.coroutines.runBlocking
                        |class ViolatorSubsystem {
                        |    private val motor = TalonFX(99)
                        |    fun periodic() {
                        |        motor.position.refresh()
                        |        runBlocking { }
                        |    }
                        |    fun mirrorX(pose: PoseMock): Double {
                        |        val x = -pose.x
                        |        return x
                        |    }
                        |    fun stop() = motor.stopMotor()
                        |}
                    """.trimMargin(),
                )
            }
            val report = KotlinInvariantAuditor.auditProject(frcDir, listOf(violatorRel))
            val ruleIds = report.findings.map { it.ruleId }.toSet()
            assertTrue("ARES-HW-009" in ruleIds, "Expected ARES-HW-009 missing vendordep, got $ruleIds")
            assertTrue("ARES-PHX-010" in ruleIds, "Expected ARES-PHX-010 unbatched refresh, got $ruleIds")
            assertTrue("ARES-LOOP-011" in ruleIds, "Expected ARES-LOOP-011 runBlocking in periodic, got $ruleIds")
            assertTrue("ARES-COORD-012" in ruleIds, "Expected ARES-COORD-012 FRC -pose.x mirror, got $ruleIds")
            assertFalse(report.passed)

            val ftcBadCoord = KotlinInvariantAuditor.auditText(
                relativePath = "TeamCode/src/main/java/BadAuto.kt",
                sourceText = """
                    class BadAuto {
                        fun flipX(x: Double): Double = 16.54 - x
                    }
                """.trimIndent(),
                coordinateConvention = "CENTER_ORIGIN_CCW",
            )
            assertTrue(ftcBadCoord.any { it.ruleId == "ARES-COORD-012" && it.severity == AuditSeverity.ERROR })
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `gradle verifier parses diagnostics junit xml and updates latest verification resource`() = runBlocking {
        val sampleLog = """
            w: file:///C:/repo/Foo.kt:4:12 Variable 'unused' is never used
            e: file:///C:/repo/Bar.kt:19:5 Unresolved reference: boom
            C:/repo/JavaFile.java:27: error: cannot find symbol
        """.trimIndent()
        val diagnostics = ProjectGradleVerifier.parseCompilerDiagnostics(sampleLog)
        assertEquals(3, diagnostics.size)
        assertEquals("WARNING", diagnostics[0].severity)
        assertEquals("ERROR", diagnostics[1].severity)
        assertEquals(19, diagnostics[1].line)
        assertEquals(27, diagnostics[2].line)

        val xmlRoot = Files.createTempDirectory("ares-w2-junit-").toFile()
        val tempProject = Files.createTempDirectory("ares-w2-verify-res-").toFile()
        val bridgeDir = Files.createTempDirectory("ares-w2-verify-bridge-").toFile()
        try {
            val testResultsDir = File(xmlRoot, "TeamCode/build/test-results/testDebugUnitTest").apply { mkdirs() }
            File(testResultsDir, "TEST-SampleTest.xml").writeText(
                """
                    <testsuite name="SampleTest" tests="3" failures="1" errors="0" skipped="1">
                      <testcase classname="SampleTest" name="ok" />
                      <testcase classname="SampleTest" name="skip" />
                      <testcase classname="SampleTest" name="fails">
                        <failure message="expected 1.0 but was 0.0">Assertion failed</failure>
                      </testcase>
                    </testsuite>
                """.trimIndent(),
            )
            val summary = ProjectGradleVerifier.parseJunitResultsDir(xmlRoot)
            assertEquals(3, summary.total)
            assertEquals(1, summary.passed)
            assertEquals(1, summary.failed)
            assertEquals(1, summary.skipped)
            assertTrue(summary.failureMessages.single().contains("SampleTest.fails"))

            val engine = HeadlessProjectEngine(StudioBridgeClient(bridgeDir), templateDir = resolveTemplateDir())
            val projDir = File(tempProject, "missing-wrapper-frc")
            engine.initProject(projDir, "FRC", displayName = "Verify Resource Bot", initGit = true)
            // Remove wrapper intentionally so verifier returns cleanly in <1s while persisting mcp-verification-latest.json
            File(projDir, "gradlew").delete()
            File(projDir, "gradlew.bat").delete()
            val verifier = ProjectGradleVerifier(engine)
            val verifyRes = verifier.verify(projDir, modeRaw = "COMPILE_ONLY")
            assertEquals(VerificationLayers.PHYSICAL_VALIDATION_NOT_RUN, verifyRes.layers.physicalValidation)
            assertTrue(File(projDir, ProjectGradleVerifier.LATEST_VERIFY_RELATIVE_PATH).isFile)

            withTimeout(20_000L) {
                val c2sIn = PipedInputStream(1024 * 1024)
                val c2sOut = PipedOutputStream(c2sIn)
                val s2cIn = PipedInputStream(1024 * 1024)
                val s2cOut = PipedOutputStream(s2cIn)
                val server = AresMcpServerFactory.create(McpToolsetMode.COMPACT, bridgeDir, resolveTemplateDir())
                val job = launch(Dispatchers.IO) {
                    server.createSession(StdioServerTransport(c2sIn.asSource().buffered(), s2cOut.asSink().buffered()))
                }
                val client = Client(Implementation("verify-res-client", "1.0.0"), ClientOptions())
                client.connect(StdioClientTransport(s2cIn.asSource().buffered(), c2sOut.asSink().buffered()))
                try {
                    client.callTool("ares_project_inspect", mapOf("projectPath" to projDir.absolutePath))
                    val latestRes = (
                        client.readResource(
                            ReadResourceRequest(ReadResourceRequestParams(uri = "ares://verification/latest")),
                        ).contents.first() as TextResourceContents
                        ).text
                    assertTrue(latestRes.contains("latestProjectVerify"))
                    assertTrue(latestRes.contains("NOT_RUN"))
                } finally {
                    runCatching { client.close() }
                    runCatching { server.close() }
                    job.cancel()
                }
            }
        } finally {
            xmlRoot.deleteRecursively()
            tempProject.deleteRecursively()
            bridgeDir.deleteRecursively()
        }
    }
}
