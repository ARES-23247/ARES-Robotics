package com.ares.analytics.service.drivebase

import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.drivebase.DriveLabState
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderIntent
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderStep
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderViewModel
import com.ares.analytics.viewmodel.drivebase.DrivebaseDiscardAction
import com.ares.analytics.viewmodel.drivebase.canonicalRuntimeProjectId
import com.ares.analytics.viewmodel.drivebase.evaluateDriveLab
import com.ares.analytics.viewmodel.drivebase.evaluateGeometryLab
import com.ares.analytics.viewmodel.drivebase.evaluateLocalizationFailure
import com.ares.analytics.viewmodel.drivebase.requireCanonicalProjectIdentity
import com.ares.analytics.viewmodel.drivebase.LocalizationFailureScenario
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import com.areslib.drivetrain.*
import com.areslib.codegen.DrivetrainKotlinGenerator
import com.areslib.tuning.TuningProfileAuthority
import com.areslib.tuning.TuningParameterType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DrivebaseAuthoringTest {
    @Test
    fun `league catalog exposes only its runnable no-code drive and labels advanced kinds as code required`() {
        assertEquals(
            listOf(DrivebaseKind.FTC_MECANUM, DrivebaseKind.DIFFERENTIAL, DrivebaseKind.CUSTOM),
            drivebaseKindsForLeague(League.FTC),
        )
        assertEquals(
            listOf(DrivebaseKind.FRC_CTRE_SWERVE, DrivebaseKind.DIFFERENTIAL, DrivebaseKind.CUSTOM),
            drivebaseKindsForLeague(League.FRC),
        )
        assertEquals(DrivebaseRuntimeSupport.NO_CODE_RUNNABLE, DrivebaseKind.FTC_MECANUM.runtimeSupport(League.FTC))
        assertEquals(DrivebaseRuntimeSupport.NO_CODE_RUNNABLE, DrivebaseKind.FRC_CTRE_SWERVE.runtimeSupport(League.FRC))
        assertEquals(DrivebaseRuntimeSupport.CODE_REQUIRED, DrivebaseKind.DIFFERENTIAL.runtimeSupport(League.FTC))
        assertEquals(DrivebaseRuntimeSupport.CODE_REQUIRED, DrivebaseKind.CUSTOM.runtimeSupport(League.FRC))
        assertEquals(DrivebaseRuntimeSupport.UNAVAILABLE_FOR_LEAGUE, DrivebaseKind.FRC_CTRE_SWERVE.runtimeSupport(League.FTC))
        assertEquals(
            listOf(DrivebaseKind.FTC_MECANUM),
            visibleDrivebaseKinds(League.FTC, advanced = false, selected = DrivebaseKind.FTC_MECANUM),
        )
        assertEquals(
            listOf(DrivebaseKind.FRC_CTRE_SWERVE, DrivebaseKind.DIFFERENTIAL, DrivebaseKind.CUSTOM),
            visibleDrivebaseKinds(League.FRC, advanced = true, selected = DrivebaseKind.FRC_CTRE_SWERVE),
        )
        assertTrue(
            DrivebaseKind.DIFFERENTIAL in visibleDrivebaseKinds(
                League.FTC,
                advanced = false,
                selected = DrivebaseKind.DIFFERENTIAL,
            ),
            "An already-open advanced draft must stay visible when Advanced is switched off",
        )
    }

    @Test
    fun `no-code validation blocks cross-league and team-written runtime architectures`() {
        val differential = defaultDrivebase("team", DrivebaseKind.DIFFERENTIAL, League.FTC)
        val crossLeague = defaultDrivebase("team", DrivebaseKind.FRC_CTRE_SWERVE, League.FRC)

        assertTrue(validateDrivebaseForLeague(differential, League.FTC).any {
            it.path == "runtime" && it.severity == DrivebaseIssueSeverity.ERROR && it.message.contains("CODE REQUIRED")
        })
        assertTrue(validateDrivebaseForLeague(crossLeague, League.FTC).any {
            it.path == "runtime" && it.severity == DrivebaseIssueSeverity.ERROR && it.message.contains("different competition platform")
        })
        assertTrue(validateDrivebaseForLeague(defaultDrivebase("team", DrivebaseKind.FTC_MECANUM, League.FTC), League.FTC).none {
            it.path == "runtime"
        })
    }

    @Test
    fun `XRP offers stock differential and four-port mecanum as honest no-code choices`() {
        assertEquals(
            listOf(DrivebaseKind.DIFFERENTIAL, DrivebaseKind.FTC_MECANUM, DrivebaseKind.CUSTOM),
            drivebaseKindsForLeague(League.XRP),
        )
        val differential = defaultDrivebase("xrp-project", DrivebaseKind.DIFFERENTIAL, League.XRP)
        assertEquals(com.areslib.drivetrain.DrivetrainPlatform.XRP, differential.canonical?.platform)
        assertEquals(listOf("1", "2"), differential.hardware.map { it.hardwareName })

        val mecanum = defaultDrivebase("xrp-project", DrivebaseKind.FTC_MECANUM, League.XRP)
        assertEquals(com.areslib.drivetrain.DrivetrainPlatform.XRP, mecanum.canonical?.platform)
        assertEquals(listOf("1", "2", "3", "4"), mecanum.hardware.map { it.hardwareName })
        assertTrue(mecanum.hardware.none { it.currentMeasurementRequired || it.currentMeasurementAvailable })
        val errors = validateDrivebaseForLeague(mecanum, League.XRP)
            .filter { it.severity == DrivebaseIssueSeverity.ERROR }
        assertTrue(errors.isEmpty(), errors.joinToString("\n") { "${it.path}: ${it.message}" })
        assertEquals("Four-motor mecanum", DrivebaseKind.FTC_MECANUM.displayName(League.XRP))
        assertEquals("FTC mecanum", DrivebaseKind.FTC_MECANUM.displayName(League.FTC))
        assertEquals(
            listOf(LocalizationKind.WHEEL_ODOMETRY_GYRO, LocalizationKind.SPARKFUN_OTOS, LocalizationKind.CUSTOM),
            localizationKindsForLeague(League.XRP),
        )
        assertFalse(LocalizationKind.FTC_PINPOINT in localizationKindsForLeague(League.XRP))
        assertFalse(LocalizationKind.CTRE_POSE_ESTIMATOR in localizationKindsForLeague(League.XRP))
    }

    @Test
    fun `default FRC CTRE swerve starts with unique simulation addresses and is reviewable`() {
        val draft = defaultDrivebase("team", DrivebaseKind.FRC_CTRE_SWERVE, League.FRC)
        val initialErrors = validateDrivebaseForLeague(draft, League.FRC)
            .filter { it.severity == DrivebaseIssueSeverity.ERROR }
        assertEquals((1..13).toList(), draft.hardware.map { it.canId })
        assertTrue(draft.hardware.all { it.canBus == "rio" })
        assertTrue(initialErrors.isEmpty(), initialErrors.joinToString("\n") { "${it.path}: ${it.message}" })
    }

    @Test
    fun `FRC simulation address helper assigns unique placeholders without claiming physical evidence`() = runBlocking {
        val root = Files.createTempDirectory("ares-frc-sim-addresses").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewModel = DrivebaseBuilderViewModel(root.path, "frc-team", League.FRC, scope)
        try {
            withTimeout(5_000) { viewModel.state.first { !it.loading } }
            viewModel.onIntent(DrivebaseBuilderIntent.UseSimulationCanIds)

            val state = viewModel.state.value
            assertEquals((1..13).toList(), state.draft.hardware.map { it.canId })
            assertTrue(state.draft.hardware.all { it.canBus == "rio" })
            assertTrue(state.status.contains("simulation-only"))
            assertTrue(state.issues.none { it.severity == DrivebaseIssueSeverity.ERROR }, state.issues.joinToString("\n") { it.message })
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `drivebase builder requires the canonical project identity without rewriting it`() = runBlocking {
        val root = Files.createTempDirectory("ares-drivebase-project-identity").toFile()
        File(root, ".ares/project.json").apply {
            parentFile.mkdirs()
            writeText(
                """{"schemaVersion":4,"projectId":"visible-frc-project","identity":{"teamId":"99998","seasonId":"2026","robotId":"VisibleFrcRobot","displayName":"Visible FRC Robot"},"league":"FRC","coordinateConvention":"BLUE_CORNER_ORIGIN_CCW","robotLengthMeters":0.75,"robotWidthMeters":0.65,"fieldLengthMeters":16.54175,"fieldWidthMeters":8.21055,"authoringModel":"GUI_OWNED","runtimeOptions":{}}""",
            )
        }
        val stale = canonicalTemplate("stale-project", DrivebaseKind.FRC_CTRE_SWERVE, League.FRC).toUiDrivebase()
        val runtimeProjectId = "visible-frc-project"
        val current = stale.copy(
            projectId = runtimeProjectId,
            canonical = requireNotNull(stale.canonical).copy(
                canonicalProfileUid = "$runtimeProjectId.profile.competition",
            ),
        )

        assertEquals(runtimeProjectId, canonicalRuntimeProjectId(root.path, "VisibleFrcRobot", League.FRC))
        assertSame(current, current.requireCanonicalProjectIdentity(runtimeProjectId))
        assertEquals(
            "$runtimeProjectId.profile.competition",
            current.canonical?.canonicalProfileUid,
        )
        val mismatch = assertFailsWith<IllegalArgumentException> {
            stale.requireCanonicalProjectIdentity(runtimeProjectId)
        }
        assertTrue(mismatch.message.orEmpty().contains("projectId"))
        root.deleteRecursively()
        Unit
    }

    @Test
    fun `reviewed save rejects a tuning profile owned by a different project`() {
        val root = Files.createTempDirectory("ares-drivebase-profile-identity").toFile()
        try {
            val ares = File(root, ".ares")
            val drivetrainDirectory = File(ares, "drivetrains").apply { mkdirs() }
            val tuningDirectory = File(ares, "tuning").apply { mkdirs() }
            val current = canonicalTemplate("visible-frc-project", DrivebaseKind.FRC_CTRE_SWERVE, League.FRC).let { template ->
                template.copy(components = template.components.mapIndexed { index, component ->
                    component.copy(hardwareId = (index + 1).toString())
                })
            }
            val currentFile = File(drivetrainDirectory, "primary.aresdrivetrain")
            val currentBytes = DrivetrainDocumentCodec.encode(current)
            currentFile.writeText(currentBytes)
            val expectedProjectId = "visible-frc-project"
            val mismatchedProfile = com.areslib.tuning.TuningProfileDocument(
                uid = current.canonicalProfileUid,
                profileId = "competition",
                displayName = "Competition",
                description = "Wrong project identity",
                projectId = "visiblefrcrobot",
                drivebaseUid = current.uid,
                authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
                values = emptyList(),
            )
            val profileFile = File(tuningDirectory, "competition.arestuning")
            val profileBytes = com.areslib.tuning.TuningProfileDocumentCodec.encode(mismatchedProfile, emptyList())
            profileFile.writeText(profileBytes)
            val reviewed = current.toUiDrivebase().copy(
                projectId = expectedProjectId,
                canonical = current.copy(canonicalProfileUid = "$expectedProjectId.profile.competition"),
            ).requireCanonicalProjectIdentity(expectedProjectId)
            val repository = DrivebaseProjectRepository()

            val failure = assertFailsWith<IllegalArgumentException> {
                repository.saveReviewed(
                    root.path,
                    DrivetrainDocumentCodec.contentHash(current),
                    reviewed,
                )
            }
            assertTrue(failure.message.orEmpty().contains("current project"))
            assertEquals(currentBytes, currentFile.readText())
            assertEquals(profileBytes, profileFile.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `view model defaults by league and refuses unsupported runnable saves`() = runBlocking {
        val ftcRoot = Files.createTempDirectory("ares-ftc-drivebase-platform").toFile()
        val frcRoot = Files.createTempDirectory("ares-frc-drivebase-platform").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ftc = DrivebaseBuilderViewModel(ftcRoot.path, "ftc-team", League.FTC, scope)
        val frc = DrivebaseBuilderViewModel(frcRoot.path, "frc-team", League.FRC, scope)
        try {
            withTimeout(5_000) { ftc.state.first { !it.loading } }
            withTimeout(5_000) { frc.state.first { !it.loading } }
            assertEquals(DrivebaseKind.FTC_MECANUM, ftc.state.value.draft.kind)
            assertEquals(DrivebaseKind.FRC_CTRE_SWERVE, frc.state.value.draft.kind)

            ftc.onIntent(DrivebaseBuilderIntent.SelectKind(DrivebaseKind.FRC_CTRE_SWERVE))
            assertEquals(DrivebaseKind.FTC_MECANUM, ftc.state.value.draft.kind)
            assertTrue(ftc.state.value.error.orEmpty().contains("not available"))

            ftc.onIntent(DrivebaseBuilderIntent.SelectKind(DrivebaseKind.DIFFERENTIAL))
            assertEquals(DrivebaseKind.DIFFERENTIAL, ftc.state.value.draft.kind)
            ftc.onIntent(DrivebaseBuilderIntent.ReviewSave)
            assertEquals(null, ftc.state.value.saveReview)
            assertTrue(ftc.state.value.issues.any { it.path == "runtime" && it.severity == DrivebaseIssueSeverity.ERROR })
            assertFalse(File(ftcRoot, ".ares/drivetrains").exists())
        } finally {
            scope.cancel()
            ftcRoot.deleteRecursively()
            frcRoot.deleteRecursively()
        }
    }

    @Test
    fun `FTC and full CTRE canonical documents round trip exactly through UI adapter`() {
        val ftc = canonicalTemplate("team", DrivebaseKind.FTC_MECANUM, League.FTC).copy(
            description = "Description must survive",
            components = canonicalTemplate("team", DrivebaseKind.FTC_MECANUM, League.FTC).components.mapIndexed { index, component ->
                component.copy(
                    controllerModel = "controller-$index",
                    encoderModel = "encoder-$index",
                    currentMeasurementRequired = false,
                    currentMeasurementAvailable = index % 2 == 0,
                    currentLimitAmps = 7.5 + index,
                    xMeters = index.toDouble(), yMeters = -index.toDouble()
                )
            },
            safety = DrivetrainSafetyDocument(currentValidityRequired = false, faultLatchingRequired = false, zeroAllocationPeriodicRequired = false),
            simulation = DrivetrainSimulationDocument("fixture.Model", "fixture.Adapter", usesPhysicalGeometry = false, usesCanonicalProfile = false, behavioralParityRequired = false)
        )
        val swerveBase = canonicalTemplate("team", DrivebaseKind.FRC_CTRE_SWERVE, League.FRC)
        val swerve = swerveBase.copy(
            components = swerveBase.components.map { it.copy(controllerModel = "TalonFX", encoderModel = "CANcoder", currentLimitAmps = 40.0) },
            localization = swerveBase.localization.copy(
                primaryOdometry = swerveBase.localization.primaryOdometry.copy(implementationClassName = "vendor.PoseEstimator"),
                visionFusion = listOf(DrivetrainLocalizationSourceDocument("localization.limelight", LocalizationSourceKind.EXTERNAL, listOf("drive.gyro"), "team.Vision")),
                headingSourceUid = "drive.gyro", headingCcwPositive = false, cachedInputsRequired = false
            ),
            control = swerveBase.control.copy(supported = DrivetrainControlKind.entries, defaultControl = DrivetrainControlKind.TRAJECTORY),
            calibrationProvenance = listOf(CalibrationProvenanceDocument("calibration.modules", CalibrationProvenanceKind.VENDOR_GENERATED, emptyList(), "evidence/tuner.java", "a".repeat(64), "fixture")),
            ctreImport = requireNotNull(swerveBase.ctreImport).copy(canBusName = "CAN2")
        )

        assertEquals(ftc, ftc.toUiDrivebase().toCanonicalDrivebase())
        assertEquals(swerve, swerve.toUiDrivebase().toCanonicalDrivebase())
        assertTrue(swerve.toUiDrivebase().hardware.all { it.canBus == "CAN2" })
    }

    @Test
    fun `one UI field edit patches only that canonical field`() {
        val canonical = canonicalTemplate("team", DrivebaseKind.FTC_MECANUM, League.FTC)
        val edited = canonical.toUiDrivebase().copy(displayName = "Student drive").toCanonicalDrivebase()
        assertEquals(canonical.copy(displayName = "Student drive"), edited)
    }

    @Test
    fun `mecanum template uses canonical names and explicit safety`() {
        val document = defaultDrivebase("team", DrivebaseKind.FTC_MECANUM, League.FTC)

        assertEquals(listOf("fl", "fr", "rl", "rr", "pinpoint"), document.hardware.map { it.hardwareName })
        assertTrue(document.safety.safeNeutralRequired)
        assertTrue(document.safety.configurationHealthRequired)
        assertTrue(document.safety.explicitNeutralRecoveryRequired)
        assertEquals(DrivetrainControlKind.CHASSIS_VELOCITY, document.defaultControlMode)
        assertEquals(
            true,
            document.canonical!!.parameters.single { it.key == CLOSED_LOOP_VELOCITY_KEY }
                .defaultValue.booleanValue,
        )
        assertTrue(validateDrivebase(document).none { it.severity == DrivebaseIssueSeverity.ERROR })
    }

    @Test
    fun `reviewed Pinpoint save repairs generator parameters and tuning assignments`() {
        val root = Files.createTempDirectory("ares-drivebase-pinpoint-repair").toFile()
        try {
            val repository = DrivebaseProjectRepository()
            val complete = defaultDrivebase("team", DrivebaseKind.FTC_MECANUM, League.FTC)
            val incomplete = complete.copy(
                canonical = complete.canonical!!.copy(
                    parameters = complete.canonical.parameters.filterNot { it.key.startsWith("localization.pinpoint") },
                ),
            )

            val saved = repository.saveReviewed(root.path, null, incomplete)
            val workspace = com.ares.analytics.service.tuning.TuningProfileRepository().load(root.path).getOrThrow()
            val requiredPinpointKeys = setOf(
                "localization.pinpointCcwPositive",
                "localization.pinpointXOffsetMm",
                "localization.pinpointYOffsetMm",
                "localization.pinpointEncoderResolution",
                "localization.pinpointXReversed",
                "localization.pinpointYReversed",
            )
            val declarations = saved.canonical!!.parameters
            assertTrue(requiredPinpointKeys.all { key -> declarations.any { it.key == key } })
            assertEquals(TuningParameterType.BOOLEAN, declarations.single { it.key == "localization.pinpointCcwPositive" }.type)
            val assigned = workspace.profiles.single().values.map { it.parameterUid }.toSet()
            assertTrue(declarations.all { it.uid in assigned })

            val generated = DrivetrainKotlinGenerator.generateFtcMecanumRuntime(
                saved.canonical,
                workspace.profiles,
                "org.example.generated",
            )
            assertTrue(generated.content.contains("pinpointIsCcwPositive"))
            assertTrue(generated.content.contains("useClosedLoopVelocity = values."))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `direction lab is pure local math with textual direction values`() {
        val fieldRelative = evaluateDriveLab(
            DrivebaseKind.FTC_MECANUM,
            DriveLabState(forward = 1.0, headingDegrees = 90.0, fieldRelative = true)
        )

        assertEquals(0.0, fieldRelative.robotForward, 1e-9)
        assertEquals(-1.0, fieldRelative.robotStrafe, 1e-9)
        assertTrue(fieldRelative.explanation.contains("does not connect to or move hardware"))
        assertEquals(setOf("frontLeft", "frontRight", "rearLeft", "rearRight"), fieldRelative.wheelOutputs.keys)
    }

    @Test
    fun `direction lab applies declared inversion and swerve preview exposes angle plus speed`() {
        val inverted = DriveHardwareDeclaration("fl", "Front left", DriveHardwareRole.FRONT_LEFT, hardwareName = "fl", inverted = true)
        val mecanum = evaluateDriveLab(DrivebaseKind.FTC_MECANUM, DriveLabState(forward = 1.0), hardware = listOf(inverted))
        assertTrue(mecanum.wheelOutputs.getValue("frontLeft") < 0.0)
        assertTrue(mecanum.wheelOutputs.getValue("frontRight") > 0.0)

        val swerve = evaluateDriveLab(DrivebaseKind.FRC_CTRE_SWERVE, DriveLabState(strafe = 1.0), DriveGeometry())
        assertEquals(swerve.wheelOutputs.keys, swerve.moduleAnglesDegrees.keys)
        assertTrue(swerve.moduleAnglesDegrees.values.all { kotlin.math.abs(it - 90.0) < 1e-9 })
    }

    @Test
    fun `differential leaders followers and inversion survive authoring and preview`() {
        val differential = defaultDrivebase("team", DrivebaseKind.DIFFERENTIAL, League.FTC)
        assertEquals(DriveHardwareRole.LEFT_LEADER, differential.hardware.first { it.id == "drive.left" }.role)
        assertEquals(DriveHardwareRole.RIGHT_LEADER, differential.hardware.first { it.id == "drive.right" }.role)

        val withFollowers = differential.copy(hardware = differential.hardware + listOf(
            DriveHardwareDeclaration(
                id = "drive.left-follower",
                displayName = "Left follower",
                role = DriveHardwareRole.LEFT_FOLLOWER,
                hardwareName = "leftFollower",
                leaderId = "drive.left",
                currentMeasurementRequired = true,
                currentMeasurementAvailable = true,
            ),
            DriveHardwareDeclaration(
                id = "drive.right-follower",
                displayName = "Right follower",
                role = DriveHardwareRole.RIGHT_FOLLOWER,
                hardwareName = "rightFollower",
                inverted = true,
                leaderId = "drive.right",
                currentMeasurementRequired = true,
                currentMeasurementAvailable = true,
            ),
        ))
        val followerIssues = validateDrivebase(withFollowers)
        assertTrue(followerIssues.none { it.severity == DrivebaseIssueSeverity.ERROR }, followerIssues.toString())

        val roundTrip = withFollowers.toCanonicalDrivebase().toUiDrivebase()
        assertEquals("drive.left", roundTrip.hardware.first { it.id == "drive.left-follower" }.leaderId)
        assertEquals("drive.right", roundTrip.hardware.first { it.id == "drive.right-follower" }.leaderId)

        val preview = evaluateDriveLab(DrivebaseKind.DIFFERENTIAL, DriveLabState(forward = 1.0), hardware = roundTrip.hardware)
        assertEquals(1.0, preview.wheelOutputs.getValue("left"), 1e-9)
        assertEquals(-1.0, preview.wheelOutputs.getValue("right"), 1e-9)
        assertEquals(1.0, preview.wheelOutputs.getValue("follower:drive.left-follower"), 1e-9)
        assertEquals(1.0, preview.wheelOutputs.getValue("follower:drive.right-follower"), 1e-9)
    }

    @Test
    fun `geometry and localization labs fail closed deterministically`() {
        val turn = evaluateGeometryLab(
            geometry = DriveGeometry(trackWidthMeters = .4, wheelBaseMeters = .3),
            linearCommand = 1.0,
            angularCommand = 2.0,
            configuredMaxLinearSpeedMps = 4.0,
            useCornerModuleRadius = true
        )
        assertEquals(.5, turn.turningRadiusMeters)
        assertEquals(1.4, turn.trackCircleDiameterMeters!!, 1e-9)
        assertEquals(4.0, turn.maxLinearSpeedMps)
        assertEquals(16.0, turn.maxAngularSpeedRadPerSec!!, 1e-9)
        assertFalse(evaluateLocalizationFailure(LocalizationFailureScenario.PRIMARY_STALE).canDriveClosedLoop)
        assertTrue(evaluateLocalizationFailure(LocalizationFailureScenario.VISION_REJECTED).canDriveClosedLoop)
        assertFalse(evaluateLocalizationFailure(LocalizationFailureScenario.VISION_REJECTED).usesVisionCorrection)
    }

    @Test
    fun `live typed CTRE fixture imports units modules inversions and CAN bus without changing source`() {
        val source = liveTunerConstants()
        assertTrue(source.isFile, "Expected live ARES-FRC TunerConstants fixture")
        val before = source.readBytes()

        val imported = CtreTunerConstantsReader.read(source)

        assertEquals("CAN2", imported.hardware.first().canBus)
        assertEquals(13, imported.hardware.count { it.canId != null })
        assertEquals(9, imported.hardware.first { it.role == DriveHardwareRole.GYRO }.canId)
        assertEquals(1.95 * 0.0254, imported.geometry!!.wheelRadiusMeters, 1e-9)
        assertEquals(21.75 * 0.0254, imported.geometry.trackWidthMeters, 1e-9)
        assertEquals(21.75 * 0.0254, imported.geometry.wheelBaseMeters, 1e-9)
        assertTrue(imported.hardware.first { it.role == DriveHardwareRole.FRONT_LEFT_DRIVE }.inverted)
        assertFalse(imported.hardware.first { it.role == DriveHardwareRole.FRONT_RIGHT_DRIVE }.inverted)
        val fixtureText = source.readText()
        val expectedSteer = Regex("kFrontLeftSteerMotorInverted\\s*=\\s*(true|false)").find(fixtureText)!!.groupValues[1].toBoolean()
        val expectedEncoder = Regex("kFrontLeftEncoderInverted\\s*=\\s*(true|false)").find(fixtureText)!!.groupValues[1].toBoolean()
        assertEquals(expectedSteer, imported.hardware.first { it.role == DriveHardwareRole.FRONT_LEFT_STEER }.inverted)
        assertEquals(expectedEncoder, imported.hardware.first { it.role == DriveHardwareRole.FRONT_LEFT_ENCODER }.inverted)
        assertTrue(imported.warnings.any { it.contains("not label", ignoreCase = true) })
        assertContentEquals(before, source.readBytes(), "Read-only import must never rewrite vendor code")
    }

    @Test
    fun `CTRE provenance hash is stable across checkout line endings`() {
        val lf = liveTunerConstants().readText().replace("\r\n", "\n")
        val crlfFile = Files.createTempFile("TunerConstants-crlf", ".java").toFile().apply {
            writeText(lf.replace("\n", "\r\n"))
        }
        val lfFile = Files.createTempFile("TunerConstants-lf", ".java").toFile().apply { writeText(lf) }

        assertEquals(
            CtreTunerConstantsReader.read(lfFile).sourceHash,
            CtreTunerConstantsReader.read(crlfFile).sourceHash,
        )
    }

    @Test
    fun `localization requires one compatible primary and optional vision`() {
        val base = defaultDrivebase("team", DrivebaseKind.FTC_MECANUM, League.FTC)
        val multiple = base.copy(localization = listOf(LocalizationKind.FTC_PINPOINT, LocalizationKind.WHEEL_ODOMETRY_GYRO))
        val incompatible = base.copy(localization = listOf(LocalizationKind.CTRE_POSE_ESTIMATOR))
        val valid = base.copy(localization = listOf(LocalizationKind.FTC_PINPOINT, LocalizationKind.VISION_FUSION))

        assertTrue(validateDrivebase(multiple).any { it.path == "localization" && it.severity == DrivebaseIssueSeverity.ERROR })
        assertTrue(validateDrivebase(incompatible).any { it.message.contains("not compatible") })
        assertTrue(validateDrivebase(valid).none { it.path == "localization" && it.severity == DrivebaseIssueSeverity.ERROR })
    }

    @Test
    fun `FTC short motor IDs retain all four physical corner roles`() {
        val template = canonicalTemplate("team", DrivebaseKind.FTC_MECANUM, League.FTC)
        val canonical = template.copy(
            components = template.components.map { component ->
                if (component.role == com.areslib.drivetrain.DrivetrainComponentRole.DRIVE_MOTOR) {
                    component.copy(uid = "ftc.motor.${component.hardwareId}")
                } else {
                    component
                }
            },
        )

        val draft = canonical.toUiDrivebase()

        assertEquals(
            listOf(
                DriveHardwareRole.FRONT_LEFT_DRIVE,
                DriveHardwareRole.FRONT_RIGHT_DRIVE,
                DriveHardwareRole.REAR_LEFT_DRIVE,
                DriveHardwareRole.REAR_RIGHT_DRIVE,
            ),
            listOf("fl", "fr", "rl", "rr").map { hardwareId ->
                draft.hardware.single { it.hardwareName == hardwareId }.role
            },
        )
        assertEquals(listOf("fl", "fr", "rl", "rr"), draft.cornerDriveHardware().map { it?.hardwareName })
    }

    @Test
    fun `CTRE import fails closed for every critical field group`() {
        val fixture = liveTunerConstants().readText()
        val mutations = mapOf(
            "module IDs" to fixture.replace(Regex("(?m)^.*kFrontLeftDriveMotorId.*$"), ""),
            "Pigeon" to fixture.replace(Regex("(?m)^.*kPigeonId.*$"), ""),
            "CAN bus" to fixture.replace("new CANBus(\"CAN2\", \"./logs/example.hoot\")", "null"),
            "wheel radius" to fixture.replace(Regex("(?m)^.*kWheelRadius.*$"), ""),
            "module position" to fixture.replace(Regex("(?m)^.*kFrontLeftXPos.*$"), ""),
            "gear ratios" to fixture.replace(Regex("(?m)^.*kDriveGearRatio.*$"), ""),
            "drive inversion" to fixture.replace(Regex("(?m)^.*kInvertLeftSide.*$"), ""),
            "steer inversion" to fixture.replace(Regex("(?m)^.*kFrontLeftSteerMotorInverted.*$"), ""),
            "encoder offset" to fixture.replace(Regex("(?m)^.*kFrontLeftEncoderOffset.*$"), ""),
            "speed and current" to fixture.replace(Regex("(?m)^.*SPEED_AT_12_VOLTS.*$"), "")
        )
        mutations.forEach { (group, text) ->
            val file = Files.createTempFile("TunerConstants-$group", ".java").toFile().apply { writeText(text) }
            val failure = assertFailsWith<IllegalArgumentException>(group) { CtreTunerConstantsReader.read(file) }
            assertTrue(failure.message.orEmpty().contains("incomplete"), group)
        }
    }

    @Test
    fun `reviewed save writes canonical UID path atomically and preserves content hash history`() {
        val root = Files.createTempDirectory("ares-drivebase").toFile()
        val repository = DrivebaseProjectRepository()
        val initial = defaultDrivebase("team", DrivebaseKind.FTC_MECANUM, League.FTC)

        val saved = repository.saveReviewed(root.path, null, initial)
        val canonical = File(root, ".ares/drivetrains/drive.primary.aresdrivetrain")
        val profileFile = File(root, ".ares/tuning/team.profile.competition.arestuning")
        assertTrue(canonical.isFile)
        assertTrue(profileFile.isFile)
        assertEquals(2, root.walkTopDown().count { it.isFile })

        val workspace = com.ares.analytics.service.tuning.TuningProfileRepository().load(root.path).getOrThrow()
        val profile = workspace.profiles.single()
        assertEquals(TuningProfileAuthority.CANONICAL_CHECKED_IN, profile.authority)
        assertEquals(saved.canonical!!.uid, profile.drivebaseUid)
        assertTrue(saved.canonical.parameters.isNotEmpty(), "Runtime tuning parameters must be explicit")
        assertTrue(saved.canonical.parameters.none { it.key.contains("trackWidth", ignoreCase = true) }, "Physical geometry must not be duplicated as a tuning parameter")
        val generated = DrivetrainKotlinGenerator.generate(saved.canonical, workspace.profiles, "org.example.generated")
        assertTrue(generated.content.contains("TRACK_WIDTH_METERS"))
        assertFalse(generated.content.contains("DRIVE_TRACKWIDTHMETERS"))

        val hash = com.areslib.drivetrain.DrivetrainDocumentCodec.contentHash(saved.canonical)
        val updated = repository.saveReviewed(root.path, hash, saved.copy(displayName = "Competition drive"))
        assertEquals("Competition drive", updated.displayName)
        assertTrue(File(root, ".ares/history/drivetrains/drive.primary/${hash.take(16)}.aresdrivetrain").isFile)
        assertFalse(File(canonical.parentFile, ".${canonical.name}.tmp").exists())
    }

    @Test
    fun `reviewed XRP drive type change atomically retargets its canonical tuning profile`() {
        val root = Files.createTempDirectory("ares-xrp-drivebase-change").toFile()
        try {
            val repository = DrivebaseProjectRepository()
            val differential = repository.saveReviewed(
                root.path,
                null,
                defaultDrivebase("xrp-project", DrivebaseKind.DIFFERENTIAL, League.XRP),
            )
            val previousHash = DrivetrainDocumentCodec.contentHash(requireNotNull(differential.canonical))
            val mecanum = repository.saveReviewed(
                root.path,
                previousHash,
                defaultDrivebase("xrp-project", DrivebaseKind.FTC_MECANUM, League.XRP),
            )

            val workspace = com.ares.analytics.service.tuning.TuningProfileRepository().load(root.path).getOrThrow()
            assertEquals(requireNotNull(mecanum.canonical).uid, workspace.profiles.single().drivebaseUid)
            assertEquals(
                requireNotNull(mecanum.canonical).parameters.map { it.uid }.toSet(),
                workspace.profiles.single().values.map { it.parameterUid }.toSet(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unsaved edits require confirmation before reload or drive type change`() = runBlocking {
        val root = Files.createTempDirectory("ares-drivebase-dirty").toFile()
        DrivebaseProjectRepository().saveReviewed(root.path, null, defaultDrivebase("team", DrivebaseKind.FTC_MECANUM, League.FTC))
        val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewModel = DrivebaseBuilderViewModel(root.path, "team", League.FTC, viewModelScope)
        try {
            withTimeout(5_000) { viewModel.state.first { !it.loading } }

            val editedGeometry = viewModel.state.value.draft.geometry.copy(trackWidthMeters = 0.42)
            viewModel.onIntent(DrivebaseBuilderIntent.UpdateGeometry(editedGeometry))
            assertTrue(viewModel.state.value.dirty)

            viewModel.onIntent(DrivebaseBuilderIntent.SelectKind(DrivebaseKind.DIFFERENTIAL))
            assertEquals(DrivebaseDiscardAction.CHANGE_KIND, viewModel.state.value.pendingDiscardAction)
            assertEquals(DrivebaseKind.FTC_MECANUM, viewModel.state.value.draft.kind)
            viewModel.onIntent(DrivebaseBuilderIntent.CancelDiscard)

            viewModel.onIntent(DrivebaseBuilderIntent.AddHardware(DriveHardwareRole.DRIVE_MOTOR))
            val addedId = viewModel.state.value.selectedHardwareId!!
            assertTrue(viewModel.state.value.draft.hardware.any { it.id == addedId })
            viewModel.onIntent(DrivebaseBuilderIntent.RemoveHardware(addedId))
            assertTrue(viewModel.state.value.draft.hardware.none { it.id == addedId })

            viewModel.onIntent(DrivebaseBuilderIntent.Reload)
            assertEquals(DrivebaseDiscardAction.RELOAD, viewModel.state.value.pendingDiscardAction)
            assertEquals(0.42, viewModel.state.value.draft.geometry.trackWidthMeters, 1e-9)
            viewModel.onIntent(DrivebaseBuilderIntent.ConfirmDiscard)
            assertTrue(viewModel.state.value.loading, "Confirmed reload must enter loading state before returning")
            withTimeout(5_000) { viewModel.state.first { !it.loading && !it.dirty } }
            assertEquals(0.36, viewModel.state.value.draft.geometry.trackWidthMeters, 1e-9)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun `builder rejects an incomplete persisted Pinpoint runtime contract`() = runBlocking {
        val root = Files.createTempDirectory("ares-drivebase-runtime-repair").toFile()
        val complete = defaultDrivebase("team", DrivebaseKind.FTC_MECANUM, League.FTC).canonical!!
        val removedPinpointUid = complete.components.single {
            it.role == DrivetrainComponentRole.ODOMETRY_SENSOR
        }.uid
        val incompleteDraft = complete.copy(
            components = complete.components.filterNot { it.role == DrivetrainComponentRole.ODOMETRY_SENSOR },
            localization = complete.localization.copy(
                primaryOdometry = complete.localization.primaryOdometry.copy(componentUids = emptyList()),
                visionFusion = listOf(
                    DrivetrainLocalizationSourceDocument(
                        uid = "localization.vision",
                        source = LocalizationSourceKind.EXTERNAL,
                        componentUids = emptyList(),
                        implementationClassName = "com.areslib.vision.VisionTracker",
                    ),
                ),
            ),
            parameters = complete.parameters.filterNot { it.componentUid == removedPinpointUid },
        )
        val persistedFile = File(root, ".ares/drivetrains/starter-mecanum.aresdrivetrain").apply {
            parentFile.mkdirs()
            writeText(DrivetrainDocumentCodec.encode(incompleteDraft))
        }
        val persistedBytes = persistedFile.readBytes()
        val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewModel = DrivebaseBuilderViewModel(root.path, "team", League.FTC, viewModelScope)
        try {
            withTimeout(5_000) { viewModel.state.first { !it.loading } }

            assertTrue(viewModel.state.value.error.orEmpty().contains("current drivetrain runtime contract"))
            assertNull(viewModel.state.value.saveReview)
            assertContentEquals(persistedBytes, persistedFile.readBytes())
        } finally {
            viewModelScope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `unchanged and reverted drafts do not enter an empty save review`() = runBlocking {
        val root = Files.createTempDirectory("ares-drivebase-noop-review").toFile()
        DrivebaseProjectRepository().saveReviewed(root.path, null, defaultDrivebase("team", DrivebaseKind.FTC_MECANUM, League.FTC))
        val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewModel = DrivebaseBuilderViewModel(root.path, "team", League.FTC, viewModelScope)
        try {
            withTimeout(5_000) { viewModel.state.first { !it.loading } }
            val savedGeometry = viewModel.state.value.draft.geometry

            viewModel.onIntent(DrivebaseBuilderIntent.UpdateGeometry(savedGeometry))
            assertFalse(viewModel.state.value.dirty)

            viewModel.onIntent(
                DrivebaseBuilderIntent.UpdateGeometry(savedGeometry.copy(trackWidthMeters = 0.42)),
            )
            assertTrue(viewModel.state.value.dirty)
            viewModel.onIntent(DrivebaseBuilderIntent.UpdateGeometry(savedGeometry))
            assertFalse(viewModel.state.value.dirty)

            viewModel.onIntent(DrivebaseBuilderIntent.ReviewSave)
            assertEquals(DrivebaseBuilderStep.REVIEW, viewModel.state.value.step)
            assertEquals(null, viewModel.state.value.saveReview)
            assertTrue(viewModel.state.value.status.contains("already matches"))
        } finally {
            viewModelScope.cancel()
            root.deleteRecursively()
        }
    }

    private fun liveTunerConstants(): File = listOf("../ARES-FRC", "../../ARES-FRC")
        .map { File(it, "src/main/java/frc/robot/generated/TunerConstants.java").canonicalFile }
        .firstOrNull(File::isFile)
        ?: error("Expected sibling ARES-FRC TunerConstants fixture from ${File(".").canonicalPath}")
}
