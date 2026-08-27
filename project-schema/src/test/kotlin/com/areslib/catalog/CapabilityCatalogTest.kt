package com.areslib.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CapabilityCatalogTest {
    @Test
    fun `codec canonicalizes actions parameters resources and contexts`() {
        val catalog = CapabilityCatalogDocument(
            projectId = "marvin",
            actions = listOf(
                ActionDescriptor(
                    key = "shooter.prepare",
                    displayName = "Prepare shooter",
                    description = "Spins the shooter to a selected target.",
                    category = "Shooter",
                    parameters = listOf(
                        CapabilityParameterDescriptor(
                            key = "rpm",
                            displayName = "Speed",
                            description = "Flywheel speed",
                            type = CapabilityParameterType.NUMBER,
                            unit = "rpm",
                            minimum = 0.0,
                            maximum = 6000.0,
                            defaultNumber = 4000.0
                        )
                    ),
                    resources = listOf(ResourceClaim("shooter.flywheel")),
                    allowedContexts = listOf(CapabilityContext.TELEOP, CapabilityContext.AUTONOMOUS)
                ),
                ActionDescriptor(
                    key = "intake.stop",
                    displayName = "Stop intake",
                    description = "Stops the intake.",
                    category = "Intake"
                )
            ),
            conditions = listOf(
                ConditionDescriptor(
                    key = "shooter.ready",
                    displayName = "Shooter ready",
                    description = "Reports fresh aligned shooter velocity.",
                    resources = listOf(ResourceClaim("shooter.flywheel", ResourceAccess.READ))
                )
            )
        )

        val decoded = CapabilityCatalogCodec.decode(CapabilityCatalogCodec.encode(catalog))

        assertEquals(listOf("intake.stop", "shooter.prepare"), decoded.actions.map { it.key })
        assertEquals(
            listOf(CapabilityContext.AUTONOMOUS, CapabilityContext.TELEOP),
            decoded.actions.last().allowedContexts
        )
        assertEquals(CapabilityCatalogCodec.contentHash(catalog), CapabilityCatalogCodec.contentHash(decoded))
    }

    @Test
    fun `validator rejects duplicate keys and incompatible parameter settings`() {
        val invalidParameter = CapabilityParameterDescriptor(
            key = "mode",
            displayName = "Mode",
            description = "Selected mode",
            type = CapabilityParameterType.ENUM,
            defaultText = "missing",
            options = listOf("fast", "fast")
        )
        val action = ActionDescriptor(
            key = "intake.run",
            displayName = "Run intake",
            description = "Runs the intake.",
            parameters = listOf(invalidParameter)
        )
        val issues = validateCapabilityCatalog(
            CapabilityCatalogDocument(projectId = "test", actions = listOf(action, action))
        )

        assertTrue(issues.any { it.code == "duplicate_action" })
        assertTrue(issues.any { it.code == "invalid_options" })
        assertTrue(issues.any { it.code == "invalid_enum_default" })
        assertThrows<IllegalArgumentException> {
            CapabilityCatalogCodec.encode(
                CapabilityCatalogDocument(projectId = "test", actions = listOf(action, action))
            )
        }
    }

    @Test
    fun `typed keys reject unstable identifiers`() {
        assertThrows<IllegalArgumentException> { ActionKey("1 bad key") }
        assertThrows<IllegalArgumentException> { ConditionKey("has piece") }
        assertThrows<IllegalArgumentException> { ResourceKey("/shooter") }
    }
}
