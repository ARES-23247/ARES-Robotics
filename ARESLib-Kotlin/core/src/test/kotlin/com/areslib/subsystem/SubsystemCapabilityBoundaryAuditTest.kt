package com.areslib.subsystem

import com.areslib.catalog.*
import com.areslib.hardware.actuator.IndicatorLightColor
import kotlin.test.*
import org.junit.jupiter.api.Test

class SubsystemCapabilityBoundaryAuditTest {
    @Test fun nonLightingTypedTargetsRetainDefaultsUnitsAndLimits() {
        val original = indicator()
        val fields = listOf(
            SubsystemStateFieldDocument("count", "Count", SubsystemValueType.INT, SubsystemFieldRole.TARGET,
                unit = "items", defaultInt = 2, minimum = 0.0, maximum = 10.0),
            SubsystemStateFieldDocument("ready", "Ready", SubsystemValueType.BOOLEAN, SubsystemFieldRole.TARGET,
                defaultBoolean = false),
            SubsystemStateFieldDocument("label", "Label", SubsystemValueType.STRING, SubsystemFieldRole.TARGET,
                defaultText = "pickup"))
        val document = original.copy(stateFields = original.stateFields + fields)
        assertTrue(SubsystemSchema.validate(document).isEmpty(), SubsystemSchema.validate(document).toString())
        val parameters = subsystemTargetCapabilities(listOf(document))
            .filter { it.operation == SubsystemCapabilityOperation.SET_FIELD }
            .associate { it.fieldId to it.descriptor.parameters.single() }
        val count = parameters.getValue("count")
        assertEquals(CapabilityParameterType.NUMBER, count.type); assertEquals(2.0, count.defaultNumber)
        assertEquals("items", count.unit); assertEquals(0.0, count.minimum); assertEquals(10.0, count.maximum)
        assertEquals(CapabilityParameterType.BOOLEAN, parameters.getValue("ready").type)
        assertEquals(false, parameters.getValue("ready").defaultBoolean)
        assertEquals(CapabilityParameterType.TEXT, parameters.getValue("label").type)
        assertEquals("pickup", parameters.getValue("label").defaultText)
    }

    @Test fun prismSupportsIntegerAndDoubleNamedDefaults() {
        val original = SubsystemTemplates.create(SubsystemTemplate.PRISM_LED_DRIVER,
            documentId = "prism", kotlinTypeName = "Prism", platform = SubsystemPlatform.FTC)
        for (type in listOf(SubsystemValueType.INT, SubsystemValueType.DOUBLE)) {
            val document = original.copy(stateFields = original.stateFields.map {
                if (it.role != SubsystemFieldRole.TARGET) it else it.copy(type = type,
                    defaultInt = if (type == SubsystemValueType.INT) 1055 else null,
                    defaultNumber = if (type == SubsystemValueType.DOUBLE) 1055.0 else null,
                    minimum = 1055.0, maximum = 1100.0)
            })
            assertTrue(SubsystemSchema.validate(document).isEmpty(), SubsystemSchema.validate(document).toString())
            val parameter = subsystemTargetCapabilities(listOf(document)).single {
                it.operation == SubsystemCapabilityOperation.SET_FIELD
            }.descriptor.parameters.single()
            assertEquals(listOf("SOLID_OFF", "SOLID_RED"), parameter.options)
            assertEquals("SOLID_OFF", parameter.defaultText)
        }
    }

    @Test fun homingIsAnExplicitBooleanRequestWithAFalseDefault() {
        val document = SubsystemTemplates.create(SubsystemTemplate.ELEVATOR_LIFT,
            documentId = "lift", kotlinTypeName = "Lift", platform = SubsystemPlatform.FTC)
        assertNotEquals(SubsystemHomingMethod.NONE, document.safety.homing.method)
        val homing = subsystemTargetCapabilities(listOf(document)).single {
            it.operation == SubsystemCapabilityOperation.SET_HOMING_REQUEST
        }
        assertEquals("subsystem.lift.set.homingRequested", homing.descriptor.key)
        assertEquals(SubsystemValueType.BOOLEAN, homing.valueType)
        assertEquals(CapabilityContext.entries, homing.descriptor.allowedContexts)
        assertEquals(false, homing.descriptor.parameters.single().defaultBoolean)
        assertEquals("subsystem.lift", homing.descriptor.resources.single().resourceKey)
    }

    private fun indicator(id: String = "indicator") = SubsystemTemplates.create(
        SubsystemTemplate.INDICATOR_LIGHT_PWM, documentId = id, kotlinTypeName = "Indicator", platform = SubsystemPlatform.FTC)

    @Test fun duplicateManualKeysCannotHideGeneratedCollision() {
        val document = indicator()
        val derived = subsystemTargetCapabilities(listOf(document)).first().descriptor
        val conflict = derived.copy(displayName = "Incorrect manual action")
        assertFailsWith<IllegalArgumentException> {
            mergeSubsystemCapabilities(CapabilityCatalogDocument(projectId = "robot", actions = listOf(conflict, derived)), listOf(document))
        }
    }

    @Test fun duplicateManualKeysWithoutGeneratedActionsAreRejected() {
        val action = ActionDescriptor("manual.run", "Run", "Run manual action")
        assertFailsWith<IllegalArgumentException> {
            mergeSubsystemCapabilities(CapabilityCatalogDocument(projectId = "robot", actions = listOf(action, action)), emptyList())
        }
    }

    @Test fun duplicateSubsystemIdsCannotSilentlyDiscardLaterDefinitions() {
        val first = indicator()
        val second = first.copy(displayName = "Different behavior")
        assertFailsWith<IllegalArgumentException> { subsystemTargetCapabilities(listOf(first, second)) }
    }

    @Test fun namedDefaultMustBelongToRangeFilteredOptions() {
        val document = indicator()
        val nearRed = IndicatorLightColor.RED.position + 1e-10
        val modified = document.copy(stateFields = document.stateFields.map {
            if (it.role == SubsystemFieldRole.TARGET) it.copy(defaultNumber = nearRed, minimum = nearRed) else it
        })
        assertTrue(SubsystemSchema.validate(modified).isEmpty(), SubsystemSchema.validate(modified).toString())
        val parameter = subsystemTargetCapabilities(listOf(modified)).single {
            it.operation == SubsystemCapabilityOperation.SET_FIELD
        }.descriptor.parameters.single()
        assertEquals(CapabilityParameterType.ENUM, parameter.type)
        assertTrue("RED" !in parameter.options)
        assertTrue(parameter.defaultText in parameter.options, parameter.toString())
        assertTrue(validateCapabilityCatalog(mergeSubsystemCapabilities(
            CapabilityCatalogDocument(projectId = "robot"), listOf(modified))).none { it.severity == CatalogValidationSeverity.ERROR })
    }

    @Test fun mergeIsDeterministicIdempotentAndPreservesCatalogMetadata() {
        val docs = listOf(indicator("zulu"), indicator("alpha"))
        val manual = ActionDescriptor("manual.run", "Run", "Run manual action")
        val catalog = CapabilityCatalogDocument(projectId = "robot", revision = 7, actions = listOf(manual),
            conditions = listOf(ConditionDescriptor("manual.ready", "Ready", "Ready to run")))
        val merged = mergeSubsystemCapabilities(catalog, docs)
        assertEquals(merged, mergeSubsystemCapabilities(catalog, docs.reversed()))
        assertEquals(merged, mergeSubsystemCapabilities(merged, docs))
        assertEquals(catalog.conditions, merged.conditions); assertEquals(7, merged.revision)
        assertSame(manual, merged.actions.single { it.key == manual.key })
        assertEquals(merged.actions.map { it.key }.sorted(), merged.actions.map { it.key })
        assertEquals(listOf(manual), catalog.actions)
    }

    @Test fun invalidDocumentsFailBeforeExposingCapabilities() {
        assertFailsWith<IllegalArgumentException> { subsystemTargetCapabilities(listOf(indicator().copy(documentId = "INVALID ID"))) }
    }

    @Test fun lightingRangeWithoutNamedOptionsFallsBackToTypedNumericTarget() {
        val document = indicator()
        val modified = document.copy(stateFields = document.stateFields.map {
            if (it.role == SubsystemFieldRole.TARGET) it.copy(defaultNumber = 0.1, minimum = 0.05, maximum = 0.15) else it
        })
        assertTrue(SubsystemSchema.validate(modified).isEmpty())
        val actions = subsystemTargetCapabilities(listOf(modified))
        val parameter = actions.single { it.operation == SubsystemCapabilityOperation.SET_FIELD }.descriptor.parameters.single()
        assertEquals(CapabilityParameterType.NUMBER, parameter.type)
        assertEquals(0.1, parameter.defaultNumber)
        assertEquals(0.05, parameter.minimum); assertEquals(0.15, parameter.maximum)
        assertTrue(actions.none { it.operation == SubsystemCapabilityOperation.CYCLE_INDICATOR_COLOR_FORWARD })
    }
}
