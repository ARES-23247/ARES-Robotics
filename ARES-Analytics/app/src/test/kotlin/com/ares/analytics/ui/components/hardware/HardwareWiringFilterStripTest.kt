package com.ares.analytics.ui.components.hardware

import com.ares.analytics.service.commissioning.CommissioningSimulationStatus
import com.ares.analytics.service.commissioning.CommissioningSimulationSummary
import com.ares.analytics.service.hardware.HardwareAddressKind
import com.ares.analytics.service.hardware.HardwareInventoryIssue
import com.ares.analytics.service.hardware.HardwareInventoryItem
import com.ares.analytics.service.hardware.HardwareInventoryOwner
import com.ares.analytics.service.hardware.HardwareIssueSeverity
import com.ares.analytics.service.hardware.HardwareReviewStatus
import com.ares.analytics.service.hardware.HardwareSetupSnapshot
import com.ares.analytics.shared.models.League
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HardwareWiringFilterStripTest {

    private val sampleSnapshot = HardwareSetupSnapshot(
        projectPath = "/tmp/robot",
        league = League.FRC,
        inventoryHash = "abcdef0123456789abcdef0123456789",
        items = listOf(
            HardwareInventoryItem(
                uid = "drive-fl",
                displayName = "Front Left Drive",
                owner = HardwareInventoryOwner.DRIVEBASE,
                ownerDisplayName = "Swerve",
                sourcePath = ".ares/drivetrains/swerve.aresdrivetrain",
                role = "Drive Motor",
                roleKey = "DRIVE_MOTOR",
                addressKind = HardwareAddressKind.CAN,
                address = "1",
                bus = "canivore",
                required = true,
                inverted = false,
                safeOutput = 0.0,
            ),
            HardwareInventoryItem(
                uid = "sub-arm",
                displayName = "Pivot Kraken",
                owner = HardwareInventoryOwner.SUBSYSTEM,
                ownerDisplayName = "Arm",
                sourcePath = ".ares/subsystems/arm.aressubsystem",
                role = "Primary Actuator",
                roleKey = "ACTUATOR",
                addressKind = HardwareAddressKind.CAN,
                address = "14",
                bus = "rio",
                required = true,
                inverted = true,
                safeOutput = 0.0,
            ),
            HardwareInventoryItem(
                uid = "sub-intake",
                displayName = "Roller NEO",
                owner = HardwareInventoryOwner.SUBSYSTEM,
                ownerDisplayName = "Intake",
                sourcePath = ".ares/subsystems/intake.aressubsystem",
                role = "Primary Actuator",
                roleKey = "ACTUATOR",
                addressKind = HardwareAddressKind.CAN,
                address = "",
                bus = "rio",
                required = true,
                inverted = false,
                safeOutput = 0.0,
            ),
        ),
        issues = listOf(
            HardwareInventoryIssue(
                severity = HardwareIssueSeverity.ERROR,
                message = "Roller NEO has no configured CAN ID.",
                itemUid = "sub-intake",
            )
        ),
        reviewStatus = HardwareReviewStatus.NOT_REVIEWED,
        reviewedBy = null,
        simulationVerification = CommissioningSimulationSummary(
            status = CommissioningSimulationStatus.VERIFIED,
            controllerCount = 2,
            scenarioCount = 6,
            failures = emptyList(),
            performanceWarnings = emptyList(),
        ),
    )

    @Test
    fun `summarizeHardwareBuses groups devices by bus and counts issues`() {
        val buses = summarizeHardwareBuses(sampleSnapshot)
        assertEquals(2, buses.size)
        val canivore = buses.first { it.busName == "canivore" }
        assertEquals(1, canivore.totalDevices)
        assertEquals(0, canivore.issueCount)

        val rio = buses.first { it.busName == "rio" }
        assertEquals(2, rio.totalDevices)
        assertEquals(1, rio.unconfiguredCount)
        assertEquals(1, rio.issueCount)
    }

    @Test
    fun `filterHardwareInventory filters by owner, bus, issues, and search query`() {
        val all = filterHardwareInventory(sampleSnapshot, "", HardwareOwnerFilter.ALL, null)
        assertEquals(3, all.size)

        val driveOnly = filterHardwareInventory(sampleSnapshot, "", HardwareOwnerFilter.DRIVEBASE, null)
        assertEquals(listOf("drive-fl"), driveOnly.map { it.uid })

        val issuesOnly = filterHardwareInventory(sampleSnapshot, "", HardwareOwnerFilter.ISSUES_ONLY, null)
        assertEquals(listOf("sub-intake"), issuesOnly.map { it.uid })

        val rioBus = filterHardwareInventory(sampleSnapshot, "", HardwareOwnerFilter.ALL, "rio")
        assertEquals(2, rioBus.size)

        val searchKraken = filterHardwareInventory(sampleSnapshot, "kraken", HardwareOwnerFilter.ALL, null)
        assertEquals(listOf("sub-arm"), searchKraken.map { it.uid })
    }

    @Test
    fun `generateHardwareWiringSpecSections and formatHardwareWiringMarkdown produce complete pit checklist`() {
        val sections = generateHardwareWiringSpecSections(sampleSnapshot)
        assertEquals(3, sections.size)
        assertEquals(2, sections[0].rows.size) // 2 buses
        assertEquals(1, sections[1].rows.size) // 1 drivetrain item
        assertEquals(2, sections[2].rows.size) // 2 subsystem items

        val markdown = formatHardwareWiringMarkdown(sampleSnapshot)
        assertTrue(markdown.contains("# ARES Pit Wiring & Port Map Checklist"))
        assertTrue(markdown.contains("| [ ] | Swerve | Front Left Drive | Drive Motor | canivore | `1` | FORWARD | `0.0` |"))
        assertTrue(markdown.contains("| [ ] | Arm | Pivot Kraken | Primary Actuator | rio | `14` | INVERTED | `0.0` |"))
        assertTrue(markdown.contains("NOT CONFIGURED"))
    }
}
