package com.areslib.codegen

import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.superstructure.StateTransitionEdge
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureStatePreset
import com.areslib.superstructure.SuperstructureSubsystemTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuperstructureKotlinGeneratorTest {
    @Test
    fun `generator emits typed runtime binding and parameterless action routing`() {
        val fixture = fixture()
        val generated = SuperstructureKotlinGenerator.generate(
            document = fixture.document,
            packageName = "org.example.subsystems.superstructure",
            subsystemRegistryFqn = "org.example.subsystems.GeneratedSubsystemRegistry",
            subsystems = listOf(fixture.subsystem),
            actionKeys = fixture.actionKeys,
        )

        assertEquals("MainMachineSuperstructure.kt", generated.relativePath)
        assertTrue(generated.content.contains("SuperstructureRuntimeBinding"))
        assertTrue(generated.content.contains("as? org.example.subsystems.arm.ArmState"))
        assertTrue(generated.content.contains("GeneratedSubsystemRegistry.createActionTask"))
        assertTrue(generated.content.contains("\"machine.activate\" -> SuperstructureRuntime.requestTask"))
        assertFalse(generated.content.contains("requestedState"))
        assertFalse(generated.content.contains("System.currentTimeMillis"))
    }

    @Test
    fun `multiple documents receive unique files and one deterministic registry`() {
        val fixture = fixture()
        val second = fixture.document.copy(
            superstructureId = "secondary-machine",
            transitions = fixture.document.transitions.map {
                it.copy(
                    transitionId = "secondary-${it.transitionId}",
                    actionKey = it.actionKey?.replace("machine.", "secondary."),
                )
            },
        )
        val registry = SuperstructureKotlinGenerator.generateRegistry(
            listOf(second, fixture.document),
            "org.example.subsystems.superstructure",
        )
        assertTrue(registry.content.contains("createMainMachineSuperstructure()"))
        assertTrue(registry.content.contains("createSecondaryMachineSuperstructure()"))
        assertTrue(registry.content.indexOf("createMainMachine") < registry.content.indexOf("createSecondaryMachine"))
        assertTrue(registry.content.contains("\"machine.activate\""))
        assertTrue(registry.content.contains("\"secondary.activate\""))
    }

    @Test
    fun `generator rejects missing project action before producing source`() {
        val fixture = fixture()
        val error = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            SuperstructureKotlinGenerator.generate(
                fixture.document,
                "org.example.subsystems.superstructure",
                "org.example.subsystems.GeneratedSubsystemRegistry",
                listOf(fixture.subsystem),
                emptySet(),
            )
        }
        assertTrue(error.message.orEmpty().contains("not present in the project action catalog"))
    }

    @Test
    fun `empty registry is documented and warning free`() {
        val registry = SuperstructureKotlinGenerator.generateRegistry(
            emptyList(),
            "org.example.subsystems.superstructure",
        )

        assertTrue(registry.content.contains("fun createAll(): List<Subsystem> = emptyList()"))
        assertTrue(registry.content.contains("@Suppress(\"UNUSED_PARAMETER\") actionKey"))
        assertFalse(registry.content.contains("when (actionKey)"))
    }

    private fun fixture(): Fixture {
        val subsystem = SubsystemTemplates.create(
            SubsystemTemplate.SIMPLE_ACTUATOR,
            documentId = "arm",
            kotlinTypeName = "Arm",
            platform = SubsystemPlatform.FTC,
        )
        val target = subsystem.stateFields.first { it.role == SubsystemFieldRole.TARGET }
        val safe = target.defaultNumber ?: target.defaultInt?.toDouble() ?: 0.0
        fun preset(id: String, value: Double) = SuperstructureStatePreset(
            stateId = id,
            subsystemTargets = listOf(
                SuperstructureSubsystemTarget("arm", target.fieldId, constantDoubleValue = value),
            ),
        )
        val document = SuperstructureDocument(
            superstructureId = "main-machine",
            initialStateId = "STOW",
            faultStateId = "FAULT",
            states = listOf(preset("STOW", safe), preset("ACTIVE", 0.5), preset("FAULT", safe)),
            transitions = listOf(
                StateTransitionEdge(
                    transitionId = "activate",
                    sourceStateId = "STOW",
                    targetStateId = "ACTIVE",
                    actionKey = "machine.activate",
                    timeoutSeconds = 0.2,
                    timeoutTargetStateId = "FAULT",
                ),
                StateTransitionEdge(
                    transitionId = "stop",
                    sourceStateId = "ACTIVE",
                    targetStateId = "STOW",
                    actionKey = "machine.stop",
                ),
                StateTransitionEdge(
                    transitionId = "recover",
                    sourceStateId = "FAULT",
                    targetStateId = "STOW",
                    actionKey = "machine.recover",
                ),
            ),
        )
        return Fixture(subsystem, document, setOf("machine.activate", "machine.stop", "machine.recover"))
    }

    private data class Fixture(
        val subsystem: com.areslib.subsystem.SubsystemDocument,
        val document: SuperstructureDocument,
        val actionKeys: Set<String>,
    )
}
