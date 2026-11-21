package com.ares.analytics.mcp

import com.ares.analytics.mcp.audit.KotlinInvariantAuditor
import com.ares.analytics.mcp.bridge.StudioBridgeClient
import com.ares.analytics.mcp.bringup.FrcVendordepCatalog
import com.ares.analytics.mcp.bringup.FtcRevHubXmlGenerator
import com.ares.analytics.mcp.bringup.HardwareBringupEngine
import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.HeadlessProjectMutators
import com.ares.analytics.mcp.engine.TuningAssignmentInput
import com.ares.analytics.mcp.resources.SchemaExemplarCatalog
import com.ares.analytics.mcp.scaffold.HybridRegistrationRequest
import com.ares.analytics.mcp.scaffold.VendorSdkProfile
import com.ares.analytics.mcp.scaffold.VendorSdkScaffoldRenderer
import com.ares.analytics.mcp.verify.ProjectGradleVerifier
import com.ares.analytics.shared.mcp.MCP_BRIDGE_FILE_NAME
import com.ares.analytics.shared.mcp.McpBridgeDescriptor
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningComponentDocument
import com.areslib.tuning.TuningComponentDocumentCodec
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Wave2FreshAuditRegressionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resolveTemplateDir(): File = listOf(
        File("../app/src/main/resources/project-templates"),
        File("ARES-Analytics/app/src/main/resources/project-templates"),
        File("app/src/main/resources/project-templates"),
    ).first { it.isDirectory }.canonicalFile

    @Test
    fun `wave1 path traversal code injection and stale bridge regressions are blocked`(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-w2-audit-sec-").toFile()
        val projectDir = File(tempRoot, "bot")
        val siblingDir = File(tempRoot, "bot-secret").apply { mkdirs() }
        val secretFile = File(siblingDir, "Secret.kt").apply { writeText("class Secret\n") }
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val engine = HeadlessProjectEngine(StudioBridgeClient(bridgeDir, isPidAlive = { false }), templateDir = resolveTemplateDir())
        val mutators = HeadlessProjectMutators(engine)
        try {
            engine.initProject(projectDir, "FTC", displayName = "Sec Bot", initGit = true)
            assertTrue(secretFile.isFile)

            // W1-1: sibling-prefix directory traversal blocked
            assertFailsWith<IllegalArgumentException> {
                KotlinInvariantAuditor.auditProject(projectDir, listOf("../bot-secret/Secret.kt"))
            }

            // W1-3: packageName injection & non-source relativePathOverride blocked
            assertFailsWith<IllegalArgumentException> {
                mutators.scaffoldKotlin(
                    projectRoot = projectDir,
                    leagueRaw = "FTC",
                    kindRaw = "HARDWARE_IO",
                    name = "Arm",
                    modeRaw = "PREVIEW",
                    packageName = "org.team; fun pwn() {}",
                )
            }
            assertFailsWith<IllegalArgumentException> {
                mutators.scaffoldKotlin(
                    projectRoot = projectDir,
                    leagueRaw = "FTC",
                    kindRaw = "HARDWARE_IO",
                    name = "Arm",
                    modeRaw = "APPLY",
                    relativePathOverride = ".git/hooks/pre-commit",
                )
            }

            // W1-4: mutateTuning rejects path-traversal profileId
            assertFailsWith<IllegalArgumentException> {
                mutators.mutateTuning(
                    projectRoot = projectDir,
                    targetRaw = "LOCAL_OVERLAY",
                    profileId = "../subsystems/evil",
                    assignments = emptyList(),
                )
            }

            // W1-5: dead Studio PID bridge does not override lastInspectedRoot
            File(bridgeDir, MCP_BRIDGE_FILE_NAME).writeText(
                json.encodeToString(
                    McpBridgeDescriptor.serializer(),
                    McpBridgeDescriptor(
                        pid = 9999999L,
                        port = 55555,
                        bearerToken = "dead",
                        studioVersion = "7.0.81",
                        activeProjectRoot = siblingDir.absolutePath,
                        startedAtEpochMillis = 1_700_000_000_000L,
                    ),
                ),
            )
            val health = StudioBridgeClient(bridgeDir, isPidAlive = { false }).health()
            assertFalse(health.connected)
            assertNull(health.activeProjectRoot)
            assertEquals(projectDir.canonicalFile, engine.resolveTargetRoot(null))
            Unit
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `wave1 multiline hot methods do not leak into subsequent cold methods`() {
        val findings = KotlinInvariantAuditor.auditText(
            relativePath = "src/main/kotlin/org/team/MultilineBot.kt",
            sourceText = """
                package org.team
                class MultilineBot {
                    fun periodic(
                        dtSeconds: Double,
                    ) {
                        val x = dtSeconds * 2.0
                    }
                    fun reduce(state: Int, action: Int) = state + action
                    fun buildColdReport(): List<String> {
                        return listOf("ok")
                    }
                }
            """.trimIndent(),
        )
        assertTrue(findings.none { it.ruleId == "ARES-GC-001" }, "Unexpected GC findings: $findings")
    }

    @Test
    fun `wave2 verifier blocks testFilter injection FULL_VERIFICATION tests flag and stale junit xml`(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-w2-verify-sec-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val projDir = File(tempRoot, "frc-proj")
        val engine = HeadlessProjectEngine(StudioBridgeClient(bridgeDir), templateDir = resolveTemplateDir())
        val verifier = ProjectGradleVerifier(engine)
        try {
            engine.initProject(projDir, "FRC", displayName = "Verify Bot", initGit = true)
            // Ensure gradlew / gradlew.bat exist so buildCommandLine reaches testFilter validation
            File(projDir, "gradlew").writeText("#!/bin/sh\n")
            File(projDir, "gradlew.bat").writeText("@echo off\n")

            assertFailsWith<IllegalArgumentException> {
                verifier.verify(projDir, modeRaw = "UNIT_TEST", testFilter = "Foo & whoami")
            }
            assertFailsWith<IllegalArgumentException> {
                verifier.verify(projDir, modeRaw = "FULL_VERIFICATION", testFilter = "org.team.FooTest")
            }

            // W2-12: stale JUnit XML older than minModifiedMillis is excluded
            val resultsDir = File(projDir, "build/test-results/test").apply { mkdirs() }
            val xmlFile = File(resultsDir, "TEST-OldTest.xml").apply {
                writeText("""<testsuite name="OldTest" tests="2" failures="0" errors="0" skipped="0"></testsuite>""")
                setLastModified(1_000_000L)
            }
            assertTrue(xmlFile.isFile)
            val staleSummary = ProjectGradleVerifier.parseJunitResultsDir(projDir, minModifiedMillis = 2_000_000L)
            assertEquals(0, staleSummary.total)
            val freshSummary = ProjectGradleVerifier.parseJunitResultsDir(projDir, minModifiedMillis = 500_000L)
            assertEquals(2, freshSummary.total)
            Unit
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `wave2 vendordep traversal blocked phoenix6 keeps jni and photonlib uuid unified`(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-w2-vdep-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val frcDir = File(tempRoot, "frc-vdep")
        val engine = HeadlessProjectEngine(StudioBridgeClient(bridgeDir), templateDir = resolveTemplateDir())
        val mutators = HeadlessProjectMutators(engine)
        val bringup = HardwareBringupEngine(engine, mutators.templateAndPatch)
        try {
            engine.initProject(frcDir, "FRC", displayName = "Vendordep Bot", initGit = true)

            // W2-2: path traversal in vendordepJson.fileName rejected
            assertFailsWith<IllegalArgumentException> {
                bringup.execute(
                    projectRoot = frcDir,
                    operationRaw = "INSTALL_VENDORDEP",
                    vendordepJson = """{"fileName":"../build.gradle","name":"Evil","version":"1.0","uuid":"11111111-1111-1111-1111-111111111111","frcYear":"2026"}""",
                )
            }

            // W2-3: PHOENIX6 preset includes all 14 official jniDependencies
            val res = bringup.execute(frcDir, "INSTALL_VENDORDEP", vendordepPreset = "PHOENIX6")
            assertTrue(res.passed)
            val installedJson = JsonParser.parseString(File(frcDir, "vendordeps/Phoenix6-26.1.1.json").readText()).asJsonObject
            assertTrue(installedJson.getAsJsonArray("jniDependencies").size() >= 14)

            // W2-6: PhotonLib preset UUID matches catalog constant
            val photonPreset = FrcVendordepCatalog.resolvePreset("PHOTONLIB")
            assertEquals(FrcVendordepCatalog.PHOTONLIB_UUID, photonPreset.uuid)
            Unit
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `wave2 vendor renderers fix limelight pitch sign photonvision 2026 stale disable and fqn validation`(): Unit = runBlocking {
        val limelightBundle = VendorSdkScaffoldRenderer.renderBundle(
            profile = VendorSdkProfile.FTC_LIMELIGHT3A_PINPOINT,
            packageName = "org.team.vision",
            baseClassName = "Vision",
            deviceNameOrCanId = "limelight\"injected",
        )
        val llIo = limelightBundle.files.first { it.relativePath.endsWith("VisionIO.kt") }.content
        assertTrue(llIo.contains("getPitch(AngleUnit.RADIANS)"))
        assertTrue(llIo.contains("-rawPitch"))
        assertFalse(llIo.contains("getYaw(AngleUnit.RADIANS)"))
        assertTrue(llIo.contains("""limelight\"injected"""))

        val photonBundle = VendorSdkScaffoldRenderer.renderBundle(
            profile = VendorSdkProfile.FRC_PHOTONVISION_APRILTAG,
            packageName = "org.team.vision",
            baseClassName = "AprilTag",
        )
        val pvIo = photonBundle.files.first { it.relativePath.endsWith("AprilTagIO.kt") }.content
        assertTrue(pvIo.contains("PhotonPoseEstimator(fieldLayout, robotToCamera)"))
        assertFalse(pvIo.contains("PoseStrategy"))
        assertTrue(pvIo.contains("hasFreshEstimate = updated || cachedStampMs != Long.MIN_VALUE"))

        val talonBundle = VendorSdkScaffoldRenderer.renderBundle(
            profile = VendorSdkProfile.FRC_TALONFX_POSITION,
            packageName = "org.team.arm",
            baseClassName = "Arm",
            deviceNameOrCanId = "25",
        )
        val subKt = talonBundle.files.first { it.relativePath.endsWith("ArmSubsystem.kt") }.content
        assertTrue(subKt.contains("nowMs >= stamp"))
        assertTrue(subKt.contains("ArmAction.Disable"))
        assertEquals(25, talonBundle.defaultCanId)

        // Schema catalog cookbook uses -robotPoseTargetSpace.rotation.y and descriptorKind="FIELD"
        assertTrue(SchemaExemplarCatalog.cookbookSnippet("ftc/limelight-pinpoint")!!.contains("-robotPoseTargetSpace.rotation.y"))
        assertTrue(SchemaExemplarCatalog.renderSchemaReference("field").contains("""descriptorKind="FIELD""""))

        // W2-9: HybridSubsystemRegistrar blocks injected FQN & writes CAN ID into descriptor hardware[]
        val tempRoot = Files.createTempDirectory("ares-w2-hyb-sec-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val frcDir = File(tempRoot, "frc-hyb")
        val engine = HeadlessProjectEngine(StudioBridgeClient(bridgeDir), templateDir = resolveTemplateDir())
        val mutators = HeadlessProjectMutators(engine)
        try {
            engine.initProject(frcDir, "FRC", displayName = "Hyb Sec", initGit = true)
            val bundleRes = mutators.hybridRegistrar.scaffoldFromTool(
                projectRoot = frcDir,
                leagueRaw = "FRC",
                kindRaw = "VENDOR_SUBSYSTEM_BUNDLE",
                name = "Elevator",
                modeRaw = "APPLY",
                vendorProfile = "FRC_TALONFX_POSITION",
                deviceNameOrCanId = "25",
                registerHybrid = true,
            )
            assertEquals("elevator", bundleRes.registeredSubsystemId)
            val subDoc = SubsystemDocumentCodec.decode(File(frcDir, ".ares/subsystems/elevator.aressubsystem").readText())
            assertEquals(25, subDoc.hardware.first().connection.canId)

            assertFailsWith<IllegalArgumentException> {
                mutators.hybridRegistrar.registerExistingHybrid(
                    projectRoot = frcDir,
                    modeRaw = "PREVIEW",
                    request = HybridRegistrationRequest(
                        documentId = "evil",
                        displayName = "Evil",
                        kotlinTypeName = "EvilSubsystem",
                        sourceFiles = listOf(bundleRes.writtenPaths.first { it.endsWith("Subsystem.kt") }),
                        subsystemClassName = "org.team.Evil\"; fun pwn() {}",
                        ioContractClassName = "org.team.EvilIO",
                        hardwareAdapterClassName = "org.team.FrcEvilIO",
                    ),
                )
            }
            Unit
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `wave2 ftc hub xml reserves bus 0 for imu and preserves tuning components on drivetrain template and ctre import`(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-w2-tun-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val ftcDir = File(tempRoot, "ftc-xml")
        val frcDir = File(tempRoot, "frc-tun")
        val engine = HeadlessProjectEngine(StudioBridgeClient(bridgeDir), templateDir = resolveTemplateDir())
        val mutators = HeadlessProjectMutators(engine)
        val bringup = HardwareBringupEngine(engine, mutators.templateAndPatch)
        try {
            // W2-7: Control Hub external I2C devices start at bus 1 (bus 0 reserved for internal IMU)
            engine.initProject(ftcDir, "FTC", displayName = "Hub XML Bot", initGit = true)
            val xml = FtcRevHubXmlGenerator.generateFromSnapshot(engine.loadBundle(ftcDir).snapshot)
            assertFalse(xml.contains("RevSPARKMini"))
            assertTrue(FtcRevHubXmlGenerator.validateXml(xml).isEmpty(), "Generated XML had validation issues")
            // Validate I2C collision detection
            val collidingI2cXml = """
                <Robot type="FirstInspires-FTC">
                  <LynxUsbDevice name="Control Hub Portal" serialNumber="(embedded)" parentModuleAddress="173">
                    <LynxModule name="Control Hub" port="173">
                      <ControlHubImuBHI260AP name="imu" port="0" bus="0" />
                      <goBILDA_Pinpoint name="pinpoint" port="0" bus="0" />
                    </LynxModule>
                  </LynxUsbDevice>
                </Robot>
            """.trimIndent()
            assertTrue(
                FtcRevHubXmlGenerator.validateXml(collidingI2cXml).any {
                    it.code == "FTC_HUB_I2C_COLLISION" && it.message.contains("bus 0")
                },
            )

            // W2-10: tuningComponents preserved across applyDrivetrainTemplate & importCtreTunerConstants (MetersPerSecond.of)
            engine.initProject(frcDir, "FRC", displayName = "Tuning Comp Bot", initGit = true)
            val frcProjectId = engine.loadBundle(frcDir).metadata!!.projectId
            val compDoc = TuningComponentDocument(
                uid = "team.comp.shooter",
                projectId = frcProjectId,
                displayName = "Shooter Tuning Component",
                description = "Preserved tuning component parameters",
                parameters = listOf(
                    TuningParameterDeclaration(
                        uid = "team.comp.shooter.kp",
                        key = "shooter.compKp",
                        componentUid = "team.comp.shooter",
                        displayName = "Shooter Comp Kp",
                        description = "Preserved tuning component parameter",
                        type = TuningParameterType.DOUBLE,
                        minimum = 0.0,
                        maximum = 10.0,
                        defaultValue = TuningValue(doubleValue = 2.5),
                        applyPolicy = TuningApplyPolicy.LIVE_SAFE,
                    ),
                ),
            )
            File(frcDir, ".ares/tuning-components/shooter-comp.arestuningcomponent").apply {
                parentFile.mkdirs()
                writeText(TuningComponentDocumentCodec.encode(compDoc))
            }
            val profileId = engine.loadBundle(frcDir).snapshot.tuningProfiles.first().profileId
            val preview = mutators.mutateTuning(
                projectRoot = frcDir,
                targetRaw = "CANONICAL",
                profileId = profileId,
                assignments = listOf(TuningAssignmentInput(keyOrUid = "shooter.compKp", doubleValue = 4.2)),
            )
            mutators.mutateTuning(
                projectRoot = frcDir,
                targetRaw = "CANONICAL",
                profileId = profileId,
                assignments = listOf(TuningAssignmentInput(keyOrUid = "shooter.compKp", doubleValue = 4.2)),
                confirmationToken = preview.confirmationToken,
            )

            // Apply drivetrain template — shooter.compKp assignment must survive!
            mutators.mutateDrivetrain(frcDir, operationRaw = "TEMPLATE", presetName = "FRC_CTRE_SWERVE", drivebaseIdHint = "swerve")
            val afterTemplate = engine.loadBundle(frcDir).snapshot.tuningProfiles.first()
            assertEquals(4.2, afterTemplate.values.firstOrNull { it.parameterUid == "team.comp.shooter.kp" }?.value?.doubleValue)

            // Import CTRE TunerConstants with 2026 MetersPerSecond.of(5.12)
            File(frcDir, "src/main/java/frc/robot/generated/TunerConstants.java").apply {
                parentFile.mkdirs()
                writeText(
                    """
                        package frc.robot.generated;
                        public class TunerConstants {
                            public static final String kCANBus = "canivore";
                            public static final int kPigeonId = 30;
                            public static final double kWheelRadius = Inches.of(2.0);
                            public static final double kSpeedAt12Volts = MetersPerSecond.of(5.12);
                            public static final double kDriveGearRatio = 6.12;
                            public static final double kSteerGearRatio = 21.42;
                            public static final int kFrontLeftDriveMotorId = 11;
                            public static final int kFrontLeftSteerMotorId = 12;
                            public static final int kFrontLeftEncoderId = 13;
                        }
                    """.trimIndent(),
                )
            }
            bringup.execute(frcDir, "IMPORT_CTRE_TUNER_CONSTANTS")
            val afterImport = engine.loadBundle(frcDir)
            assertEquals(5.12, afterImport.snapshot.drivetrains.first().geometry.maxLinearSpeedMetersPerSecond, 1e-6)
            assertEquals(4.2, afterImport.snapshot.tuningProfiles.first().values.firstOrNull { it.parameterUid == "team.comp.shooter.kp" }?.value?.doubleValue)

            // W2-11: CAN collision detected against imported `${bus}_can_${id}` drivetrain hardware IDs
            val collidingKt = "src/main/kotlin/org/aresfirst/starter/frc/extensions/BadCan.kt"
            File(frcDir, collidingKt).apply {
                parentFile.mkdirs()
                writeText(
                    """
                        package org.aresfirst.starter.frc.extensions
                        import com.ctre.phoenix6.hardware.TalonFX
                        class BadCan {
                            private val m = TalonFX(11, "canivore")
                            fun stop() = m.stopMotor()
                        }
                    """.trimIndent(),
                )
            }
            val canReport = KotlinInvariantAuditor.auditProject(frcDir, listOf(collidingKt))
            assertTrue(canReport.findings.any { it.ruleId == "ARES-HW-009" && it.message.contains("CAN ID 11") })

            // W2-13: UPSERT_ACTION rejects incomplete JSON missing required key/displayName
            assertFailsWith<Exception> {
                mutators.configAndField.mutateProjectConfig(
                    projectRoot = frcDir,
                    configKindRaw = "ACTION_CATALOG",
                    operationRaw = "UPSERT_ACTION",
                    documentJson = """{"description":"missing key and displayName"}""",
                )
            }
            Unit
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
