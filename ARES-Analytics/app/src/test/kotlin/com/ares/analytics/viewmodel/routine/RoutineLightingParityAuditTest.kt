package com.ares.analytics.viewmodel.routine

import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.hardware.actuator.PrismPwmPreset
import com.areslib.subsystem.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class RoutineLightingParityAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun indicator(default: Double = 0.0, minimum: Double = 0.0, maximum: Double = 1.0): SubsystemDocument {
        val doc = SubsystemTemplates.create(SubsystemTemplate.INDICATOR_LIGHT_PWM, "status", "StatusLights", SubsystemPlatform.FTC)
        return doc.copy(stateFields = doc.stateFields.map { if (it.fieldId == "targetColor")
            it.copy(defaultNumber = default, minimum = minimum, maximum = maximum) else it })
    }
    private fun model(vararg documents: SubsystemDocument): RoutineLightingPreviewModel {
        val project = temporary.newFolder()
        val directory = project.resolve(".ares/subsystems").apply { mkdirs() }
        documents.forEachIndexed { index, doc -> directory.resolve("$index.aressubsystem").writeText(SubsystemDocumentCodec.encode(doc)) }
        return RoutineLightingPreviewModel.load(project.path)
    }
    private fun action(operation: String, value: String? = null, time: Double = 0.0) = RoutinePreviewAction(
        time, "step", "subsystem.status.$operation.targetColor", value?.let { mapOf("value" to it) } ?: emptyMap(),
    )
    private fun position(model: RoutineLightingPreviewModel, vararg actions: RoutinePreviewAction, time: Double = 0.0) =
        model.compile(actions.toList()).at(time).indicators.single().position

    @Test fun `cycling forward from off selects red as generated code does`() {
        assertEquals(IndicatorLightColor.RED.position, position(model(indicator()), action("cycleForward")))
    }

    @Test fun `cycling between named positions selects the next ordered value not nearest plus one`() {
        assertEquals(IndicatorLightColor.ORANGE.position, position(model(indicator(0.32)), action("cycleForward")))
        assertEquals(IndicatorLightColor.GREEN.position, position(model(indicator(0.49)), action("cycleBackward")))
    }

    @Test fun `cycle traversal obeys field limits in both directions`() {
        val green = IndicatorLightColor.GREEN.position
        val cyan = IndicatorLightColor.CYAN.position
        assertEquals(green, position(model(indicator(cyan, green, cyan)), action("cycleForward")))
        assertEquals(cyan, position(model(indicator(green, green, cyan)), action("cycleBackward")))
    }

    @Test fun `numeric strings nonfinite values and unsupported rainbow never become generated light targets`() {
        val preview = model(indicator())
        for (raw in listOf("NaN", "Infinity", "0.99", "RAINBOW", "not a color")) {
            assertEquals(0.0, position(preview, action("set", raw)), raw)
        }
    }

    @Test fun `named indicator actions respect declared field bounds`() {
        val green = IndicatorLightColor.GREEN.position
        val preview = model(indicator(green, green, IndicatorLightColor.CYAN.position))
        assertEquals(green, position(preview, action("set", "RED")))
        assertEquals(IndicatorLightColor.CYAN.position, position(preview, action("set", "CYAN")))
    }

    @Test fun `Prism choices respect the generated named value and bound contract`() {
        val doc = SubsystemTemplates.create(SubsystemTemplate.PRISM_LED_DRIVER, "prism", "PrismLights", SubsystemPlatform.FTC)
        val bounded = doc.copy(stateFields = doc.stateFields.map { if (it.fieldId == "targetPulseWidthUs") it.copy(maximum = 1100.0) else it })
        val preview = model(bounded)
        fun set(value: String) = RoutinePreviewAction(0.0, "prism", "subsystem.prism.set.targetPulseWidthUs", mapOf("value" to value))
        assertEquals(1000.0, preview.compile(listOf(set("SOLID_WHITE"))).at(0.0).prismPulseWidthUs)
        assertEquals(1000.0, preview.compile(listOf(set("615"))).at(0.0).prismPulseWidthUs)
        assertEquals(PrismPwmPreset.FTC_TIMER.pulseWidthUs.toDouble(), preview.compile(listOf(set("FTC_TIMER"))).at(0.0).prismPulseWidthUs)
    }

    @Test fun `events apply at their timestamp and never slightly early`() {
        val preview = model(indicator())
        val future = action("set", "GREEN", 1e-10)
        assertEquals(0.0, position(preview, future, time = 0.0))
        assertEquals(IndicatorLightColor.GREEN.position, position(preview, future, time = 1e-10))
    }

    @Test fun `invalid event timestamps are ignored`() {
        val preview = model(indicator())
        for (time in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(0.0, position(preview, action("set", "GREEN", time)))
        }
    }

    @Test fun `unsorted and simultaneous actions retain chronological then source order`() {
        val preview = model(indicator())
        assertEquals(IndicatorLightColor.CYAN.position, position(preview,
            action("set", "BLUE", 1.0), action("set", "GREEN", 0.5), action("cycleBackward", time = 1.0), time = 1.0))
        assertEquals(IndicatorLightColor.GREEN.position, position(preview,
            action("set", "BLUE", 1.0), action("set", "GREEN", 0.5), time = 0.75))
    }

    @Test fun `duplicate subsystem identities cannot render multiple invented runtime targets`() {
        assertTrue(model(indicator(), indicator()).compile(emptyList()).at(0.0).indicators.isEmpty())
    }
    @Test fun `compiled history owns inputs and reuses frames during forward and backward seeks`() {
        val values = listOf(0.279, 0.333, 0.388, 0.472, 0.511, 0.611, 0.722, 0.833)
        var inputClosed = false
        val actions = object : AbstractList<RoutinePreviewAction>() {
            override val size = 128
            override fun get(index: Int): RoutinePreviewAction {
                check(!inputClosed) { "Playback traversed its input action list" }
                return action("cycleForward", time = index + 1.0)
            }
        }
        val timeline = model(indicator()).compile(actions)
        inputClosed = true
        for (time in listOf(128.5, 1.5, 64.5, 2.5, 17.5, 0.5, 1.5)) {
            val count = time.toInt()
            val expected = if (count == 0) 0.0 else values[(count - 1) % values.size]
            assertEquals(expected, timeline.at(time).indicators.single().position)
        }
        val first = timeline.at(1.1)
        repeat(10_000) { assertSame(first, timeline.at(1.9)) }
    }

    @Test fun `compiled action values cannot be changed by later input mutation`() {
        val arguments = mutableMapOf("value" to "GREEN")
        val actions = mutableListOf(action("set").copy(arguments = arguments))
        val timeline = model(indicator()).compile(actions)
        arguments["value"] = "RED"
        actions.clear()
        assertEquals(IndicatorLightColor.GREEN.position, timeline.at(0.0).indicators.single().position)
    }

    @Test fun `simultaneous no-op changes reuse the preceding frame`() {
        val timeline = model(indicator()).compile(listOf(action("set", "RED", 1.0), action("set", "OFF", 1.0)))
        assertSame(timeline.at(0.0), timeline.at(1.0))
    }

    @Test fun `signed zero timestamps preserve source order at the same instant`() {
        val timeline = model(indicator()).compile(listOf(action("set", "GREEN", 0.0), action("cycleForward", time = -0.0)))
        assertEquals(IndicatorLightColor.CYAN.position, timeline.at(0.0).indicators.single().position)
    }

    @Test fun `nonfinite playback time returns the finite descriptor defaults`() {
        val timeline = model(indicator()).compile(listOf(action("set", "GREEN")))
        for (time in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(0.0, timeline.at(time).indicators.single().position)
        }
    }

    @Test fun `empty missing or malformed descriptor sources produce no invented lights`() {
        assertTrue(RoutineLightingPreviewModel.load(null).compile(emptyList()).at(0.0).indicators.isEmpty())
        val root = temporary.newFolder()
        assertTrue(RoutineLightingPreviewModel.load(root.path).compile(emptyList()).at(0.0).indicators.isEmpty())
        val directory = root.resolve(".ares/subsystems").apply { mkdirs() }
        directory.resolve("broken.aressubsystem").writeText("not a descriptor")
        assertTrue(RoutineLightingPreviewModel.load(root.path).compile(emptyList()).at(0.0).indicators.isEmpty())
    }

    @Test fun `hand-authored descriptors cannot imply generated action semantics`() {
        val owned = indicator().copy(generateMockIo = false, generateTest = false,
            implementation = SubsystemImplementationDocument(
                kind = SubsystemImplementationKind.HAND_AUTHORED, ownership = SubsystemSourceOwnership.USER_OWNED,
                modulePath = ":TeamCode", sourceFiles = listOf("TeamCode/src/main/java/example/Status.kt"),
                subsystemClassName = "example.StatusSubsystem", ioContractClassName = "example.StatusIO",
                hardwareAdapterClassName = "example.StatusHardware",
                simulation = SubsystemSimulationDocument(SubsystemSimulationSupport.UNAVAILABLE),
            ), capabilityActionKeys = listOf("status.set"))
        assertTrue(model(owned).compile(listOf(action("set", "GREEN"))).at(0.0).indicators.isEmpty())
    }

}
