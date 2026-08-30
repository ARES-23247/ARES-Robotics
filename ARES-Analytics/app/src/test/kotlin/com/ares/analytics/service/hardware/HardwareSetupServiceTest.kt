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

    private fun seedDrivebase(root: java.io.File, includeLogicalWheelModule: Boolean = false) {
        val base = defaultDrivebase("team1-robot", DrivebaseKind.FTC_MECANUM)
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
