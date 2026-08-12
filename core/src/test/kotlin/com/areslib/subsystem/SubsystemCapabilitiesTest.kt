package com.areslib.subsystem

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubsystemCapabilitiesTest {
    @Test
    fun `target fields become typed discoverable actions without replacing manual actions`() {
        val elevator = subsystem("elevator", "Elevator", SubsystemPlatform.FTC) {
            val target = state.double(
                "targetMeters", "Target height", SubsystemFieldRole.TARGET, 0.0, "m", 0.0, 1.5,
            )
            val motor = hardware.motor("leader", "Leader") { hardwareMapName = "elevator" }
            control.direct("manual", "Manual", motor, target)
        }
        val manual = ActionDescriptor("intake.stop", "Stop intake", "Stops the intake")
        val merged = mergeSubsystemCapabilities(
            CapabilityCatalogDocument(projectId = "robot", actions = listOf(manual)),
            listOf(elevator),
        )

        assertEquals(listOf("intake.stop", "subsystem.elevator.set.targetMeters"), merged.actions.map { it.key })
        val generated = merged.actions.last()
        assertEquals("m", generated.parameters.single().unit)
        assertEquals(0.0, generated.parameters.single().minimum)
        assertEquals(1.5, generated.parameters.single().maximum)
        assertEquals("subsystem.elevator", generated.resources.single().resourceKey)
    }

    @Test
    fun `manual collision with a generated setter fails closed`() {
        val intake = subsystem("intake", "Intake", SubsystemPlatform.FTC) {
            val power = state.double("power", "Power", SubsystemFieldRole.TARGET, 0.0)
            val motor = hardware.motor("roller", "Roller") { hardwareMapName = "intake" }
            control.direct("rollerControl", "Roller control", motor, power)
        }
        val conflicting = ActionDescriptor(
            "subsystem.intake.set.power", "Unrelated action", "Should not shadow generated behavior",
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            mergeSubsystemCapabilities(
                CapabilityCatalogDocument(projectId = "robot", actions = listOf(conflicting)),
                listOf(intake),
            )
        }
        assertTrue(error.message.orEmpty().contains("conflicts"))
    }

    @Test
    fun `hand-authored actions remain catalog-owned and must exist`() {
        val indicator = handAuthoredIndicator()
        val lightAction = ActionDescriptor(
            "indicator.primary.green", "Primary light green", "Sets the primary light to green",
        )
        val catalog = CapabilityCatalogDocument(projectId = "robot", actions = listOf(lightAction))

        val merged = mergeSubsystemCapabilities(catalog, listOf(indicator))
        assertEquals(listOf("indicator.primary.green"), merged.actions.map { it.key })
        assertTrue(subsystemTargetCapabilities(listOf(indicator)).isEmpty())

        val error = assertThrows(IllegalArgumentException::class.java) {
            mergeSubsystemCapabilities(catalog.copy(actions = emptyList()), listOf(indicator))
        }
        assertTrue(error.message.orEmpty().contains("references missing catalog action"))
    }

    private fun handAuthoredIndicator() = subsystem(
        "indicator",
        "Indicator",
        SubsystemPlatform.FTC,
    ) {
        generateMockIo = false
        generateTest = false
        capabilityAction("indicator.primary.green")
        implementation.apply {
            kind = SubsystemImplementationKind.HAND_AUTHORED
            ownership = SubsystemSourceOwnership.USER_OWNED
            modulePath = ":TeamCode"
            sourceFile("TeamCode/src/main/java/example/IndicatorSubsystem.kt")
            subsystemClassName = "example.IndicatorSubsystem"
            ioContractClassName = "example.IndicatorIO"
            hardwareAdapterClassName = "example.FtcIndicatorIO"
            simulationSupport = SubsystemSimulationSupport.UNAVAILABLE
            teachingLevel = SubsystemTeachingLevel.BEGINNER
        }
        val color = state.double(
            "color", "Color", SubsystemFieldRole.TARGET, default = 0.0, minimum = 0.0, maximum = 1.0,
        )
        val servo = hardware.positionalServo("indicator", "Indicator") {
            hardwareMapName = "indicator"
            safeOutput = 0.0
        }
        control.servoPosition("color", "Color", servo, color) {
            minimumOutput = 0.0
            maximumOutput = 1.0
        }
    }
}
