package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.models.League
import org.ares.biobuzz.BiobuzzField
import com.ares.analytics.viewmodel.FieldEditorIntent
import com.ares.analytics.viewmodel.FieldEditorViewModel
import kotlinx.coroutines.*
import kotlin.test.*

class BiobuzzFieldIntegrationTest {
    @Test fun `documented tagless BIOBUZZ field can be pushed from the editor`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var published: String? = null
        try {
            val editor = FieldEditorViewModel(scope, fieldConfigPublisher = { published = it; true })
            editor.onIntent(FieldEditorIntent.LoadBiobuzzPreset)
            assertTrue(editor.state.value.validationIssues.none { it.severity == FieldValidationSeverity.ERROR })
            editor.onIntent(FieldEditorIntent.PushToSimulator)
            val pushed = com.areslib.state.RobotFieldDocument.decode(assertNotNull(published))
            assertEquals("ftc-2026-2027-biobuzz", pushed.id)
            assertTrue(pushed.apriltags.isEmpty())
            assertEquals(56, pushed.elements.size)
        } finally { scope.cancel() }
    }

    @Test fun `bundled BIOBUZZ is an editable Robot Builder example with generated mechanisms and controls`() {
        val template = com.ares.analytics.service.project.RobotProjectTemplateService.OFFICIAL_PROJECT_TEMPLATES.single {
            it.kind == com.ares.analytics.service.project.RobotProjectTemplateKind.BIOBUZZ_EXAMPLE
        }
        val entries = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(javaClass.getResourceAsStream(template.bundledResourcePath!!)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; if (!entry.isDirectory) entries[entry.name] = zip.readBytes() }
        }
        fun source(suffix: String) = entries.entries.single { it.key.endsWith(suffix) }.value.toString(Charsets.UTF_8)
        for (name in listOf("intake", "shooter")) {
            val descriptor = com.areslib.subsystem.SubsystemDocumentCodec.decode(source(".ares/subsystems/biobuzz-$name.aressubsystem"))
            assertEquals(com.areslib.subsystem.SubsystemImplementationKind.DECLARATIVE_GENERATED, descriptor.implementation.kind)
            assertEquals(com.areslib.subsystem.SubsystemSimulationSupport.GENERATED_MOCK, descriptor.implementation.simulation.support)
            assertTrue(descriptor.generateMockIo)
            assertTrue(descriptor.hardware.all { it.measurements.isNotEmpty() })
            assertTrue(descriptor.safety.latchOutputFaults)
        }
        val controls = com.areslib.controls.ControlSchemeCodec.decode(source(".ares/controls/driver.arescontrols"))
        assertTrue(controls.bindings.any { it.target.key == "subsystem.biobuzz-intake.set.intakeVoltage" })
        assertTrue(controls.bindings.any { it.target.key == "subsystem.biobuzz-shooter.set.transferVoltage" })
        val dashboardLaunch = source("TeamCode/build.gradle")
        assertTrue(dashboardLaunch.contains("org.ares.biobuzz.BiobuzzSimLauncher"))
        assertTrue(dashboardLaunch.contains("project(':simulator').sourceSets.main.runtimeClasspath"))
        assertFalse(entries.keys.any { it.endsWith("BiobuzzMechanisms.kt") })
    }

    @Test fun `editor renders catalog ball sizes and colors instead of generic markers`() {
        val field = BiobuzzField.document()
        val pieces = FieldDocumentMapper.gamePieces(field)
        for (type in field.elementTypes) {
            val rendered = pieces.first { it.typeId == type.id }
            assertEquals(type.shape, rendered.simulationShape)
            assertEquals(type.diameter, rendered.widthMeters)
            assertEquals(type.color.removePrefix("#").toInt(16), rendered.colorRgb)
        }
    }

    @Test fun `bundled preset and background load without external files and undo restores original document`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val editor = FieldEditorViewModel(scope)
            val original = editor.state.value
            editor.onIntent(FieldEditorIntent.LoadBiobuzzPreset)
            assertEquals("ftc-2026-2027-biobuzz", editor.state.value.document?.id)
            assertEquals(6, editor.state.value.fieldWaypoints.size)
            assertNotNull(editor.state.value.fieldImage)
            assertTrue(editor.state.value.canUndo)
            editor.onIntent(FieldEditorIntent.Undo)
            assertEquals(original.fieldImage, editor.state.value.fieldImage)
            assertEquals(original.obstacles, editor.state.value.obstacles)
            assertEquals(original.fieldImageConfig, editor.state.value.fieldImageConfig)
            editor.onIntent(FieldEditorIntent.Redo)
            assertEquals("ftc-2026-2027-biobuzz", editor.state.value.document?.id)
            val image = FieldImageLoader.load("", League.FTC, BiobuzzField.document().image?.imagePath).getOrThrow()
            assertEquals(1440, image?.width)
        } finally { scope.cancel() }
    }
}
