package com.ares.analytics.service.project.persistence

import com.areslib.routine.*
import java.nio.file.Files
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class BiobuzzAutoBundleTest {
    @Test fun `imports the ZIP downloaded from the browser auto editor`() {
        val file = File(requireNotNull(javaClass.getResource("/biobuzz/browser-auto.zip")).toURI())
        val draft = BiobuzzAutoBundle.read(file)
        assertEquals("Browser hive auto", draft.routine.name)
        assertEquals(RoutinePose(-1.1, 1.8288 - kotlin.math.hypot(0.225, 0.225), -Math.PI / 4), draft.entry.startingPose)
        assertEquals(RoutineAlliance.RED, draft.entry.authoredAlliance)
        assertFalse(draft.entry.mirrorForOppositeAlliance)
        assertEquals(6, draft.routine.steps.count { it.kind == RoutineStepKind.DRIVE_TO })
        assertTrue(draft.routine.steps.any { it.actionKey == "subsystem.biobuzz-intake.set.intakeVoltage" && it.arguments["value"] == "12" })
        assertEquals(4, draft.routine.steps.count { it.actionKey == "subsystem.biobuzz-shooter.set.transferVoltage" && it.arguments["value"] == "12" })
        assertEquals(draft.routine, AresRoutineCodec.decode(AresRoutineCodec.encode(draft.routine)))
    }
    private val routine = RoutineDocument(documentId = "web-auto", name = "Web auto", steps = listOf(
        RoutineStep.wait(0.5, stepId = "wait-1"),
    ))
    private val catalog = AutonomousCatalogDocument(projectId = "biobuzz-reference", entries = listOf(
        AutonomousCatalogEntry(entryId = "web-auto", displayName = "Web auto", routineId = "web-auto",
            startingPose = RoutinePose(-1.1, 1.6038, -Math.PI / 2), authoredAlliance = RoutineAlliance.RED,
            mirrorForOppositeAlliance = false),
    ))
    private fun archive(extra: Pair<String, String>? = null, body: (File) -> Unit) {
        val directory = Files.createTempDirectory("biobuzz-auto-import-").toFile()
        try {
            val zip = File(directory, "auto.zip")
            ZipOutputStream(zip.outputStream()).use { stream ->
                val files = listOf(".ares/routines/web-auto.aresroutine" to AresRoutineCodec.encode(routine),
                    ".ares/autonomous-catalog.json" to AutonomousCatalogCodec.encode(catalog)) + listOfNotNull(extra)
                for ((name, text) in files) {
                    stream.putNextEntry(ZipEntry(name)); stream.write(text.toByteArray()); stream.closeEntry()
                }
            }
            body(zip)
        } finally { directory.deleteRecursively() }
    }
    @Test fun `imports native documents with a fresh identity and preserved pose`() = archive { file ->
        val first = BiobuzzAutoBundle.read(file)
        val second = BiobuzzAutoBundle.read(file)
        assertNotEquals(first.routine.documentId, second.routine.documentId)
        assertEquals(first.routine.documentId, first.entry.routineId)
        assertEquals(catalog.entries.single().startingPose, first.entry.startingPose)
        assertFalse(first.entry.mirrorForOppositeAlliance)
        assertEquals(routine.steps, first.routine.steps)
    }
    @Test fun `rejects archive traversal without extraction`() = archive("../outside.txt" to "bad") {
        assertFailsWith<IllegalArgumentException> { BiobuzzAutoBundle.read(it) }
    }
    @Test fun `bounds expanded archive entries`() = archive("README.txt" to "x".repeat(300_000)) {
        assertFailsWith<IllegalArgumentException> { BiobuzzAutoBundle.read(it) }
    }
    @Test fun `rejects unrecognized archive content`() = archive("script.js" to "alert(1)") {
        assertFailsWith<IllegalArgumentException> { BiobuzzAutoBundle.read(it) }
    }
}
