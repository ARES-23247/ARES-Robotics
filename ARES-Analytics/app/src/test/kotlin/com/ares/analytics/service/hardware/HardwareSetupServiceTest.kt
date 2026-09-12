package com.ares.analytics.service.hardware

import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.service.drivebase.DrivebaseProjectRepository
import com.ares.analytics.service.drivebase.defaultDrivebase
import com.ares.analytics.shared.models.League
import com.ares.analytics.service.project.persistence.SubsystemProjectRepository
import com.areslib.drivetrain.DrivetrainComponentDocument
import com.areslib.drivetrain.DrivetrainComponentRole
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.subsystem.SubsystemHardwareConnection
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HardwareSetupServiceTest {
    @Test
    fun `physical validation requires current simulation and review and is invalidated by descriptor edits`() {
        val root = Files.createTempDirectory("ares-physical-evidence").toFile()
        try {
            seedDrivebase(root)
            val subsystemRepository = SubsystemProjectRepository()
            val lift = lift("arm")
            subsystemRepository.save(root.path, lift)
            val service = HardwareSetupService(
                clock = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC),
            )

            val beforeReview = service.inspect(root.path, League.FTC)
            assertTrue(beforeReview.simulationVerification.verified)
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                service.savePhysicalValidation(root.path, League.FTC, completePhysicalRequest())
            }
            service.saveReview(root.path, League.FTC, completeReviewRequest())

            val validated = service.savePhysicalValidation(root.path, League.FTC, completePhysicalRequest())
            assertEquals("Mentor One", validated.physicalValidation?.validatedBy)
            assertEquals(validated.inventoryHash, validated.physicalValidation?.inventoryHash)
            assertEquals(1_800_000_000_000L, validated.physicalValidation?.recordedAtEpochMillis)

            subsystemRepository.save(
                root.path,
                lift.copy(hardware = lift.hardware.map { it.copy(inverted = !it.inverted) }),
            )
            val stale = service.inspect(root.path, League.FTC)
            assertEquals(HardwareReviewStatus.STALE, stale.reviewStatus)
            assertEquals(null, stale.physicalValidation)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `review is bound to exact canonical hardware hashes and becomes stale after an edit`() {
        val root = Files.createTempDirectory("ares-hardware-review").toFile()
        try {
            seedDrivebase(root)
            val subsystemRepository = SubsystemProjectRepository()
            val lift = lift("arm")
            subsystemRepository.save(root.path, lift)
            val service = HardwareSetupService()

            val initial = service.inspect(root.path, League.FTC)
            assertTrue(initial.items.any { it.displayName == "Motor" && it.address == "arm" })
            assertTrue(initial.canReview)
            assertEquals(HardwareReviewStatus.NOT_REVIEWED, initial.reviewStatus)

            val reviewed = service.saveReview(
                root.path,
                League.FTC,
                HardwareReviewRequest(
                    reviewerName = "Student Driver",
                    wiringMatched = true,
                    addressesChecked = true,
                    directionsChecked = true,
                    neutralOutputsChecked = true,
                    limitsChecked = true,
                ),
            )
            assertEquals(HardwareReviewStatus.CURRENT, reviewed.reviewStatus)
            assertEquals("Student Driver", reviewed.reviewedBy)
            assertEquals(null, service.deploymentBlockReason(root.path, League.FTC))

            val changed = lift.copy(
                hardware = lift.hardware.map { device ->
                    device.copy(connection = device.connection.copy(hardwareMapName = "arm-updated"))
                },
            )
            subsystemRepository.save(root.path, changed)
            val stale = service.inspect(root.path, League.FTC)
            assertEquals(HardwareReviewStatus.STALE, stale.reviewStatus)
            assertTrue(service.deploymentBlockReason(root.path, League.FTC)!!.contains("changed after"))

            val evidenceDirectory = java.io.File(root, ".ares/evidence/hardware/configuration")
            evidenceDirectory.deleteRecursively()
            java.io.File(evidenceDirectory, "invalid.json").apply {
                parentFile.mkdirs()
                writeText("not-json")
            }
            val invalid = service.inspect(root.path, League.FTC)
            assertEquals(HardwareReviewStatus.INVALID, invalid.reviewStatus)
            assertTrue(invalid.canReview, "A malformed review must be replaceable after the hardware itself validates")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cross-document address collision fails review before any record is written`() {
        val root = Files.createTempDirectory("ares-hardware-collision").toFile()
        try {
            seedDrivebase(root)
            SubsystemProjectRepository().save(root.path, lift("fl"))
            val service = HardwareSetupService()

            val snapshot = service.inspect(root.path, League.FTC)
            assertTrue(snapshot.errorIssues.any { it.message.contains("is claimed by") })
            assertTrue(!snapshot.canReview)
            val plan = snapshot.commissioningPlan()
            assertTrue(!plan.ftcDiagnosticAvailable)
            assertTrue(!plan.clipboardText.contains("A / Cross"))
            assertTrue(plan.clipboardText.contains(requireNotNull(plan.ftcDiagnosticBlockReason)))
            assertTrue(service.deploymentBlockReason(root.path, League.FTC)!!.contains("blocking issue"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `FTC commissioning plan includes rear motors exact names and hold-to-run controls`() {
        val root = Files.createTempDirectory("ares-hardware-commissioning").toFile()
        try {
            seedDrivebase(root, includeLogicalWheelModule = true)
            val snapshot = HardwareSetupService().inspect(root.path, League.FTC)

            val plan = snapshot.commissioningPlan()

            assertTrue(plan.ftcDiagnosticAvailable)
            assertEquals(listOf("A / Cross", "B / Circle", "X / Square", "Y / Triangle"), plan.ftcMotorChecks.map { it.gamepadControl })
            assertEquals(listOf("fl", "fr", "rl", "rr"), plan.ftcMotorChecks.map { it.hardwareMapName })
            assertTrue(plan.hardwareMapEntries.none { it.displayName == "Mecanum drivebase" })
            assertTrue(!plan.clipboardText.contains("Mecanum drivebase"))
            assertTrue(plan.clipboardText.contains("Rear left: rl"))
            assertTrue(plan.clipboardText.contains("Rear right: rr"))
            assertTrue(plan.clipboardText.contains("release to stop"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `FTC motor diagnostic fails closed when a canonical wheel role is absent`() {
        val root = Files.createTempDirectory("ares-hardware-incomplete-diagnostic").toFile()
        try {
            seedDrivebase(root)
            val snapshot = HardwareSetupService().inspect(root.path, League.FTC)
            val incomplete = snapshot.copy(items = snapshot.items.filterNot { it.roleKey == "REAR_RIGHT_DRIVE" })

            val plan = incomplete.commissioningPlan()

            assertTrue(!plan.ftcDiagnosticAvailable)
            assertTrue(plan.ftcDiagnosticBlockReason!!.contains("exactly one"))
            assertEquals(listOf("fl", "fr", "rl"), plan.ftcMotorChecks.map { it.hardwareMapName })
            assertTrue(!plan.clipboardText.contains("A / Cross"))
            assertTrue(plan.clipboardText.contains(requireNotNull(plan.ftcDiagnosticBlockReason)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `subsystem commissioning is descriptor derived and motion remains unarmed`() {
        val root = Files.createTempDirectory("ares-subsystem-commissioning").toFile()
        try {
            seedDrivebase(root)
            SubsystemProjectRepository().save(root.path, lift("arm"))

            val plan = HardwareSetupService().inspect(root.path, League.FTC).commissioningPlan()
            val motor = plan.subsystemChecks.single { it.deviceName == "Motor" }

            assertEquals("Lift", motor.subsystemName)
            assertTrue(motor.readOnlySignals.isNotEmpty())
            assertTrue(motor.controlStrategies.isNotEmpty())
            assertEquals(250L, requireNotNull(motor.pulseProposal).maximumDurationMs)
            assertTrue(plan.clipboardText.contains("UNARMED proposal"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Prism driver is documented as PWM rather than I2C hardware`() {
        val root = Files.createTempDirectory("ares-prism-address-kind").toFile()
        try {
            SubsystemProjectRepository().save(
                root.path,
                SubsystemTemplates.create(
                    template = SubsystemTemplate.PRISM_LED_DRIVER,
                    documentId = "lights",
                    kotlinTypeName = "Lights",
                    platform = SubsystemPlatform.FRC,
                ),
            )

            val prism = HardwareSetupService().inspect(root.path, League.FRC).items.single {
                it.owner == HardwareInventoryOwner.SUBSYSTEM
            }
            assertEquals(HardwareAddressKind.PWM, prism.addressKind)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `FTC IMU inventory shows the exact declared Control Hub orientation`() {
        val root = Files.createTempDirectory("ares-imu-setup-details").toFile()
        try {
            SubsystemProjectRepository().save(
                root.path,
                SubsystemTemplates.create(
                    template = SubsystemTemplate.IMU_SENSOR,
                    documentId = "heading",
                    kotlinTypeName = "Heading",
                    platform = SubsystemPlatform.FTC,
                ),
            )

            val imu = HardwareSetupService().inspect(root.path, League.FTC).items.single {
                it.owner == HardwareInventoryOwner.SUBSYSTEM
            }
            assertTrue(imu.configurationDetails.any { it == "Control Hub mounting: logo faces up, USB faces forward" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `FRC IMU inventory identifies the onboard SPI and CCW normalization boundary`() {
        val root = Files.createTempDirectory("ares-frc-imu-setup-details").toFile()
        try {
            SubsystemProjectRepository().save(
                root.path,
                SubsystemTemplates.create(
                    template = SubsystemTemplate.IMU_SENSOR,
                    documentId = "heading",
                    kotlinTypeName = "Heading",
                    platform = SubsystemPlatform.FRC,
                ),
            )

            val imu = HardwareSetupService().inspect(root.path, League.FRC).items.single {
                it.owner == HardwareInventoryOwner.SUBSYSTEM
            }
            assertEquals(HardwareAddressKind.SPI, imu.addressKind)
            assertTrue(imu.configurationDetails.any { it.contains("CCW-positive radians") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `diagnostic rejects duplicated motor names even without precomputed inventory errors`() {
        val root = Files.createTempDirectory("ares-duplicate-motor-diagnostic").toFile()
        try {
            seedDrivebase(root)
            val snapshot = HardwareSetupService().inspect(root.path, League.FTC)
            val duplicated = snapshot.copy(items = snapshot.items.map { item ->
                if (item.roleKey == "FRONT_RIGHT_DRIVE") item.copy(address = "fl") else item
            })
            assertTrue(duplicated.errorIssues.isEmpty())
            val plan = duplicated.commissioningPlan()
            assertTrue(!plan.ftcDiagnosticAvailable)
            assertTrue(!plan.clipboardText.contains("A / Cross"))
            assertTrue(plan.clipboardText.contains(requireNotNull(plan.ftcDiagnosticBlockReason)))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `invalid subsystem evidence blocks diagnostic instructions for an otherwise complete drivebase`() {
        val root = Files.createTempDirectory("ares-invalid-subsystem-diagnostic").toFile()
        try {
            seedDrivebase(root)
            java.io.File(root, ".ares/subsystems/broken.aressubsystem").apply {
                parentFile.mkdirs()
                writeText("not a subsystem document")
            }
            val snapshot = HardwareSetupService().inspect(root.path, League.FTC)
            assertTrue(snapshot.errorIssues.isNotEmpty())
            val plan = snapshot.commissioningPlan()
            assertEquals(4, plan.ftcMotorChecks.size)
            assertTrue(!plan.ftcDiagnosticAvailable)
            assertTrue(!plan.clipboardText.contains("A / Cross"))
            assertTrue(plan.clipboardText.contains(requireNotNull(plan.ftcDiagnosticBlockReason)))
        } finally { root.deleteRecursively() }
    }

    private fun withInvalidReviewedProject(check: (java.io.File, HardwareSetupService, HardwareSetupSnapshot) -> Unit) {
        val root = Files.createTempDirectory("ares-invalid-reviewed-hardware").toFile()
        try {
            seedDrivebase(root)
            SubsystemProjectRepository().save(root.path, lift("arm"))
            val service = HardwareSetupService()
            service.saveReview(root.path, League.FTC, completeReviewRequest())
            val valid = service.savePhysicalValidation(root.path, League.FTC, completePhysicalRequest())
            assertTrue(valid.physicalValidation != null)
            java.io.File(root, ".ares/subsystems/broken.aressubsystem").writeText("invalid subsystem")
            val invalid = service.inspect(root.path, League.FTC)
            assertEquals(valid.inventoryHash, invalid.inventoryHash)
            assertEquals(HardwareReviewStatus.CURRENT, invalid.reviewStatus)
            assertTrue(invalid.errorIssues.isNotEmpty())
            check(root, service, invalid)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `current fingerprints do not enable the physical evidence form when inventory has errors`() =
        withInvalidReviewedProject { _, _, snapshot ->
            val state = com.ares.analytics.viewmodel.hardware.HardwareSetupState(
                loading = false, snapshot = snapshot,
                physicalValidatorName = "Mentor One", physicalEvidenceSummary = completePhysicalRequest().evidenceSummary,
                directionsAndPolarityTested = true, unitsAndSensorsTested = true, disabledNeutralTested = true,
                limitsAndCurrentTested = true, faultRecoveryTested = true,
            )
            assertTrue(!snapshot.readyForPhysicalValidation)
            assertTrue(!state.canSavePhysicalValidation)
            assertEquals(null, snapshot.physicalValidation)
        }

    @Test
    fun `service rejects new physical evidence for an invalid inventory without writing a record`() =
        withInvalidReviewedProject { root, service, _ ->
            val directory = java.io.File(root, ".ares/evidence/hardware/physical")
            val before = directory.listFiles().orEmpty().associate { it.name to it.readText() }
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                service.savePhysicalValidation(root.path, League.FTC, completePhysicalRequest())
            }
            assertEquals(before, directory.listFiles().orEmpty().associate { it.name to it.readText() })
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `commissioning UI refreshes eligibility when errors change under the same inventory hash`() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("ares-commissioning-cache").toFile()
        val scene = androidx.compose.ui.ImageComposeScene(10, 10, coroutineContext = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            seedDrivebase(root)
            val selected = androidx.compose.runtime.mutableStateOf(HardwareSetupService().inspect(root.path, League.FTC))
            var observed: HardwareCommissioningPlan? = null
            scene.setContent {
                val plan = com.ares.analytics.ui.screens.rememberCommissioningPlan(selected.value)
                androidx.compose.runtime.SideEffect { observed = plan }
            }
            fun pump() {
                repeat(2) {
                    testScheduler.runCurrent()
                    scene.render(testScheduler.currentTime * 1_000_000L).close()
                }
                testScheduler.runCurrent()
            }
            pump()
            assertTrue(requireNotNull(observed).ftcDiagnosticAvailable)
            val originalHash = selected.value.inventoryHash
            selected.value = selected.value.copy(issues = listOf(HardwareInventoryIssue(HardwareIssueSeverity.ERROR, "Unreadable subsystem")))
            assertEquals(originalHash, selected.value.inventoryHash)
            pump()
            assertTrue(!requireNotNull(observed).ftcDiagnosticAvailable)
            assertTrue(requireNotNull(observed).clipboardText.contains("Unreadable subsystem"))
        } finally {
            scene.close()
            testScheduler.runCurrent()
            root.deleteRecursively()
        }
    }

    @Test
    fun `follower commissioning retains read-only details without proposing an independent pulse`() {
        val root = Files.createTempDirectory("ares-follower-commissioning").toFile()
        try {
            seedDrivebase(root)
            SubsystemProjectRepository().save(root.path, SubsystemTemplates.create(
                SubsystemTemplate.DUAL_MOTOR_FOLLOWER, "pair", "Pair", SubsystemPlatform.FTC,
            ))
            val checks = HardwareSetupService().inspect(root.path, League.FTC).commissioningPlan().subsystemChecks
            assertTrue(checks.any { it.followerOnly })
            assertTrue(checks.filter { it.followerOnly }.all { it.pulseProposal == null })
            assertTrue(checks.any { !it.followerOnly && it.pulseProposal != null })
        } finally { root.deleteRecursively() }
    }

    private fun frcSensor(template: SubsystemTemplate, id: String, channel: Int, secondary: Int? = null) =
        SubsystemTemplates.create(template, id, id.replaceFirstChar { it.uppercase() }, SubsystemPlatform.FRC).let { document ->
            document.copy(hardware = document.hardware.map { device ->
                device.copy(connection = device.connection.copy(channel = channel, secondaryChannel = secondary))
            })
        }

    @Test
    fun `FRC absolute encoder reports DIO and may share a number with an analog sensor`() {
        val root = Files.createTempDirectory("ares-frc-encoder-address").toFile()
        try {
            val repository = SubsystemProjectRepository()
            repository.save(root.path, frcSensor(SubsystemTemplate.ABSOLUTE_ENCODER_SENSOR, "absolute", 0))
            repository.save(root.path, frcSensor(SubsystemTemplate.POTENTIOMETER_SENSOR, "analog", 0))
            val snapshot = HardwareSetupService().inspect(root.path, League.FRC)
            val encoder = snapshot.items.single { it.roleKey == "ABSOLUTE_ENCODER" }
            assertEquals(HardwareAddressKind.DIO, encoder.addressKind)
            assertEquals("0", encoder.address)
            assertTrue(!snapshot.commissioningPlan().clipboardText.contains("Configure Robot"))
            assertTrue(snapshot.errorIssues.none { it.message.contains("is claimed by") })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `FRC absolute encoder and digital input cannot claim the same channel`() {
        val root = Files.createTempDirectory("ares-frc-dio-collision").toFile()
        try {
            val repository = SubsystemProjectRepository()
            repository.save(root.path, frcSensor(SubsystemTemplate.ABSOLUTE_ENCODER_SENSOR, "absolute", 2))
            repository.save(root.path, frcSensor(SubsystemTemplate.LIMIT_SWITCH_SENSOR, "limit", 2))
            val snapshot = HardwareSetupService().inspect(root.path, League.FRC)
            assertTrue(snapshot.errorIssues.any { it.message.contains("is claimed by") })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `FRC quadrature encoder reserves both digital input channels independently`() {
        val root = Files.createTempDirectory("ares-frc-quadrature-collision").toFile()
        try {
            val repository = SubsystemProjectRepository()
            repository.save(root.path, frcSensor(SubsystemTemplate.QUADRATURE_ENCODER_SENSOR, "quadrature", 2, 3))
            for (channel in listOf(2, 3)) {
                repository.save(root.path, frcSensor(SubsystemTemplate.LIMIT_SWITCH_SENSOR, "limit", channel))
                val snapshot = HardwareSetupService().inspect(root.path, League.FRC)
                assertTrue(snapshot.errorIssues.any { it.message.contains("is claimed by") }, "DIO $channel must be reserved")
            }
            repository.save(root.path, frcSensor(SubsystemTemplate.LIMIT_SWITCH_SENSOR, "limit", 4))
            assertTrue(HardwareSetupService().inspect(root.path, League.FRC).errorIssues.none { it.message.contains("is claimed by") })
        } finally { root.deleteRecursively() }
    }

    private fun seedDrivebase(root: java.io.File, includeLogicalWheelModule: Boolean = false) {
        val base = defaultDrivebase("team1-robot", DrivebaseKind.FTC_MECANUM, League.FTC)
        DrivebaseProjectRepository().saveReviewed(
            root.path,
            expectedContentHash = null,
            document = base,
        )
        if (includeLogicalWheelModule) {
            val source = java.io.File(root, ".ares/drivetrains").listFiles().orEmpty().single()
            val canonical = DrivetrainDocumentCodec.decode(source.readText())
            source.writeText(
                DrivetrainDocumentCodec.encode(
                    canonical.copy(
                        components = canonical.components + DrivetrainComponentDocument(
                            uid = "drive.mecanum",
                            displayName = "Mecanum drivebase",
                            role = DrivetrainComponentRole.WHEEL_MODULE,
                            hardwareId = "mecanum",
                        ),
                    ),
                ),
            )
        }
    }

    private fun lift(hardwareMapName: String) = SubsystemTemplates.create(
        template = SubsystemTemplate.SIMPLE_ACTUATOR,
        documentId = "lift",
        kotlinTypeName = "Lift",
        platform = SubsystemPlatform.FTC,
    ).let { document ->
        document.copy(
            hardware = document.hardware.map { device ->
                device.copy(connection = SubsystemHardwareConnection(hardwareMapName = hardwareMapName))
            },
        )
    }

    private fun completeReviewRequest() = HardwareReviewRequest(
        reviewerName = "Student Driver",
        wiringMatched = true,
        addressesChecked = true,
        directionsChecked = true,
        neutralOutputsChecked = true,
        limitsChecked = true,
    )

    private fun completePhysicalRequest() = HardwarePhysicalValidationRequest(
        validatedBy = "Mentor One",
        evidenceSummary = "Robot on blocks: direction, sensors, neutral, limits, current, and fault recovery all matched the written procedure.",
        directionsAndPolarityTested = true,
        unitsAndSensorsTested = true,
        disabledNeutralTested = true,
        limitsAndCurrentTested = true,
        faultRecoveryTested = true,
    )
}
