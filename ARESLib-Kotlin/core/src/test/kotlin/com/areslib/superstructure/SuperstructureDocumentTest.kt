package com.areslib.superstructure

import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SuperstructureDocumentTest {
    @Test
    fun `fault preset has an implicit runtime failure edge`() {
        val document = SuperstructureDocument(
            superstructureId = "simple-coordinator",
            initialStateId = "idle",
            states = listOf(
                SuperstructureStatePreset("idle"),
                SuperstructureStatePreset("fault"),
            ),
            faultStateId = "fault",
        )

        assertTrue(validateSuperstructureDocument(document).none { it.path == "states" && "fault" in it.message })
    }

    @Test
    fun `dynamic lookup cannot command a boolean target`() {
        val subsystem = SubsystemTemplates.create(
            template = SubsystemTemplate.SIMPLE_ACTUATOR,
            documentId = "arm",
            kotlinTypeName = "Arm",
            platform = SubsystemPlatform.FTC,
        ).copy(
            stateFields = listOf(
                com.areslib.subsystem.SubsystemStateFieldDocument(
                    "enabled",
                    "Enabled",
                    com.areslib.subsystem.SubsystemValueType.BOOLEAN,
                    SubsystemFieldRole.TARGET,
                    defaultBoolean = false,
                ),
                com.areslib.subsystem.SubsystemStateFieldDocument(
                    "position",
                    "Position",
                    com.areslib.subsystem.SubsystemValueType.DOUBLE,
                    SubsystemFieldRole.MEASUREMENT,
                    defaultNumber = 0.0,
                ),
            ),
        )
        val target = SuperstructureSubsystemTarget(
            target = SuperstructureFieldReference(subsystem.uid, subsystem.stateFields.single { it.fieldId == "enabled" }.uid),
            targetMode = SuperstructureTargetMode.DYNAMIC_LUT,
            lutId = "position-map",
            source = SuperstructureFieldReference(subsystem.uid, subsystem.stateFields.single { it.fieldId == "position" }.uid),
        )
        val document = SuperstructureDocument(
            superstructureId = "invalid-output",
            initialStateId = "idle",
            states = listOf(
                SuperstructureStatePreset("idle", subsystemTargets = listOf(target)),
                SuperstructureStatePreset("fault", subsystemTargets = listOf(target.copy(
                    targetMode = SuperstructureTargetMode.CONSTANT,
                    constantBooleanValue = false,
                    lutId = null,
                    source = null,
                ))),
            ),
            luts = listOf(SuperstructureDynamicLut("position-map", controlPoints = listOf(LutControlPoint(0.0, 0.0), LutControlPoint(1.0, 1.0)))),
            faultStateId = "fault",
        )

        assertTrue(validateSuperstructureProject(document, listOf(subsystem), emptySet()).any {
            it.message.contains("only numeric TARGET")
        })
    }

    @Test
    fun `LUT sampling clamps and interpolates without accepting invalid input`() {
        val lut = SuperstructureDynamicLut(
            lutId = "distance-to-output",
            interpolation = LutInterpolationMethod.LINEAR,
            controlPoints = listOf(
                LutControlPoint(1.0, 10.0),
                LutControlPoint(2.0, 20.0),
                LutControlPoint(4.0, 60.0),
            ),
        )
        assertEquals(10.0, lut.sample(0.5), 1e-9)
        assertEquals(15.0, lut.sample(1.5), 1e-9)
        assertEquals(40.0, lut.sample(3.0), 1e-9)
        assertEquals(60.0, lut.sample(10.0), 1e-9)
        assertTrue(lut.sample(Double.NaN).isNaN())
    }

    @Test
    fun `codec requires schema v3 and rejects unknown fields`() {
        val document = validFixture().document
        val encoded = SuperstructureDocumentCodec.encode(document)
        assertEquals(document, SuperstructureDocumentCodec.decode(encoded))
        assertEquals(64, SuperstructureDocumentCodec.contentHash(document).length)

        val unknown = encoded.replaceFirst("{", "{\"timeoutFallbackStateId\":\"FAULT\",")
        assertTrue(assertThrows<IllegalArgumentException> {
            SuperstructureDocumentCodec.decode(unknown)
        }.message.orEmpty().contains("Unknown fields"))

        val missingSchema = encoded.replace(Regex("\\s*\\\"schemaVersion\\\"\\s*:\\s*3,?"), "")
        assertTrue(assertThrows<IllegalArgumentException> {
            SuperstructureDocumentCodec.decode(missingSchema)
        }.message.orEmpty().contains("schemaVersion"))
    }

    @Test
    fun `project validation resolves actions fields types and neutral fault preset`() {
        val fixture = validFixture()
        assertTrue(
            validateSuperstructureProject(
                fixture.document,
                listOf(fixture.subsystem),
                fixture.actionKeys,
            ).none { it.severity == SuperstructureIssueSeverity.ERROR },
        )

        val missingAction = validateSuperstructureProject(
            fixture.document,
            listOf(fixture.subsystem),
            fixture.actionKeys - "machine.activate",
        )
        assertTrue(missingAction.any { it.path.endsWith("actionKey") && it.message.contains("not present") })

        val unsafeFault = fixture.document.copy(
            states = fixture.document.states.map { state ->
                if (state.stateId == "FAULT") {
                    state.copy(
                        subsystemTargets = state.subsystemTargets.map {
                            it.copy(constantDoubleValue = 0.75)
                        },
                    )
                } else state
            },
        )
        assertTrue(
            validateSuperstructureProject(unsafeFault, listOf(fixture.subsystem), fixture.actionKeys)
                .any { it.path.startsWith("faultStateId") && it.message.contains("safe default") },
        )
    }

    @Test
    fun `state target sets and transition graph fail closed`() {
        val fixture = validFixture()
        val missingTarget = fixture.document.copy(
            states = fixture.document.states.map { state ->
                if (state.stateId == "ACTIVE") state.copy(subsystemTargets = emptyList()) else state
            },
        )
        assertTrue(validateSuperstructureDocument(missingTarget).any {
            it.path.contains("subsystemTargets") && it.message.contains("same target fields")
        })

        val unreachable = fixture.document.copy(
            transitions = fixture.document.transitions
                .filterNot { it.targetStateId == "ACTIVE" }
                .map { it.copy(timeoutSeconds = null, timeoutTargetStateId = null) },
        )
        assertTrue(validateSuperstructureDocument(unreachable).any {
            it.path == "states" && it.message.contains("unreachable")
        })
    }

    @Test
    fun `stable port references survive code id renames`() {
        val fixture = validFixture()
        val renamed = fixture.subsystem.copy(
            documentId = "renamed-arm",
            stateFields = fixture.subsystem.stateFields.map { field ->
                if (field.role == SubsystemFieldRole.TARGET) field.copy(fieldId = "renamedTarget") else field
            },
        )

        val errors = validateSuperstructureProject(fixture.document, listOf(renamed), fixture.actionKeys)
            .filter { it.severity == SuperstructureIssueSeverity.ERROR }

        assertTrue(errors.isEmpty(), errors.joinToString { "${it.path}: ${it.message}" })
    }

    @Test
    fun `lifecycle actions must exist in the project catalog`() {
        val fixture = validFixture()
        val withLifecycle = fixture.document.copy(
            states = fixture.document.states.map { state ->
                if (state.stateId == "ACTIVE") state.copy(onEntryActionKeys = listOf("indicator.active")) else state
            },
        )

        val errors = validateSuperstructureProject(withLifecycle, listOf(fixture.subsystem), fixture.actionKeys)

        assertTrue(errors.any { it.path.contains("onEntryActionKeys") && it.message.contains("indicator.active") })
    }

    @Test
    fun `request and lifecycle actions must be parameterless`() {
        val fixture = validFixture()
        val withLifecycle = fixture.document.copy(
            states = fixture.document.states.map { state ->
                if (state.stateId == "ACTIVE") state.copy(onEntryActionKeys = listOf("machine.activate")) else state
            },
        )

        val errors = validateSuperstructureProject(
            withLifecycle,
            listOf(fixture.subsystem),
            fixture.actionKeys,
            emptySet(),
        )

        assertTrue(errors.any { it.path.endsWith("actionKey") && it.message.contains("parameterless") })
        assertTrue(errors.any { it.path.contains("onEntryActionKeys") && it.message.contains("parameterless") })
    }

    @Test
    fun `dynamic LUT canonical units must match both connected ports`() {
        val fixture = validFixture()
        val target = fixture.subsystem.stateFields.first { it.role == SubsystemFieldRole.TARGET }
        val measurement = fixture.subsystem.stateFields.first { it.role == SubsystemFieldRole.MEASUREMENT && it.type.name in setOf("DOUBLE", "INT") }
        val lut = SuperstructureDynamicLut(
            lutId = "wrong-units",
            inputUnit = "m",
            outputUnit = "rpm",
            controlPoints = listOf(LutControlPoint(0.0, 0.0), LutControlPoint(1.0, 1.0)),
        )
        val mismatched = fixture.document.copy(
            luts = listOf(lut),
            states = fixture.document.states.map { state ->
                if (state.stateId != "ACTIVE") state else state.copy(
                    subsystemTargets = listOf(
                        SuperstructureSubsystemTarget(
                            target = SuperstructureFieldReference(fixture.subsystem.uid, target.uid),
                            targetMode = SuperstructureTargetMode.DYNAMIC_LUT,
                            lutId = lut.lutId,
                            source = SuperstructureFieldReference(fixture.subsystem.uid, measurement.uid),
                        )
                    )
                )
            },
        )

        val errors = validateSuperstructureProject(mismatched, listOf(fixture.subsystem), fixture.actionKeys)

        assertTrue(errors.any { it.path.endsWith("lutId") && it.message.contains("unit") })
    }

    private fun validFixture(): Fixture {
        val subsystem = SubsystemTemplates.create(
            template = SubsystemTemplate.SIMPLE_ACTUATOR,
            documentId = "arm",
            kotlinTypeName = "Arm",
            platform = SubsystemPlatform.FTC,
        )
        val target = subsystem.stateFields.first { it.role == SubsystemFieldRole.TARGET }
        val measurement = subsystem.stateFields.first {
            it.role == SubsystemFieldRole.MEASUREMENT && it.type.name in setOf("DOUBLE", "INT")
        }
        val safeDefault = target.defaultNumber ?: target.defaultInt?.toDouble() ?: 0.0
        fun preset(id: String, value: Double) = SuperstructureStatePreset(
            stateId = id,
            subsystemTargets = listOf(
                SuperstructureSubsystemTarget(
                    target = SuperstructureFieldReference(subsystem.uid, target.uid),
                    constantDoubleValue = value,
                ),
            ),
        )
        val document = SuperstructureDocument(
            superstructureId = "main-machine",
            initialStateId = "STOW",
            faultStateId = "FAULT",
            states = listOf(
                preset("STOW", safeDefault),
                preset("ACTIVE", 0.5),
                preset("FAULT", safeDefault),
            ),
            transitions = listOf(
                StateTransitionEdge(
                    transitionId = "activate",
                    sourceStateId = "STOW",
                    targetStateId = "ACTIVE",
                    actionKey = "machine.activate",
                    guards = listOf(
                        TransitionGuard(
                            guardId = "feedback-ready",
                            source = SuperstructureFieldReference(subsystem.uid, measurement.uid),
                            comparison = InterlockComparison.GREATER_THAN,
                            expectedDoubleValue = 0.1,
                        ),
                    ),
                    timeoutSeconds = 0.2,
                    timeoutTargetStateId = "FAULT",
                ),
                StateTransitionEdge(
                    transitionId = "deactivate",
                    sourceStateId = "ACTIVE",
                    targetStateId = "STOW",
                    actionKey = "machine.deactivate",
                ),
                StateTransitionEdge(
                    transitionId = "recover",
                    sourceStateId = "FAULT",
                    targetStateId = "STOW",
                    actionKey = "machine.recover",
                ),
            ),
        )
        return Fixture(
            subsystem,
            document,
            setOf("machine.activate", "machine.deactivate", "machine.recover"),
        )
    }

    private data class Fixture(
        val subsystem: com.areslib.subsystem.SubsystemDocument,
        val document: SuperstructureDocument,
        val actionKeys: Set<String>,
    )
}
