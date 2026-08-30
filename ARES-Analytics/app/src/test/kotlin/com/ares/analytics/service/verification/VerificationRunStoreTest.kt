package com.ares.analytics.service.verification

import com.ares.analytics.shared.models.League
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VerificationRunStoreTest {
    @Test
    fun `run result is normalized under local evidence and reloads exactly`() {
        withProject { root ->
            val command = listOf("gradlew", "test", "--rerun-tasks")
            val pending = VerificationRunStore.begin(root, command, "9.13.1-dev")
            val provenance = VerificationRunStore.complete(pending, 0)
            val report = RobotVerificationReport(
                projectPath = root.canonicalPath,
                league = League.FTC,
                provenance = provenance,
                items = listOf(
                    VerificationReportItem(
                        id = "project.build",
                        layer = VerificationLayer.BUILD,
                        title = "Build",
                        explanation = "Compiled",
                        status = VerificationResultStatus.PASSED,
                        evidenceLevel = VerificationEvidenceLevel.COMPILED_SUCCESSFULLY,
                        source = "Gradle",
                    ),
                ),
            )

            val loaded = VerificationRunStore.saveAndReload(root, report)

            assertEquals(report, loaded)
            assertTrue(File(root, ".ares/local/verification/${pending.runId}/report.json").isFile)
            assertEquals(command, loaded.provenance.command)
        }
    }

    @Test
    fun `canonical edits invalidate a pending run but local and history files do not`() {
        withProject { root ->
            val pending = VerificationRunStore.begin(root, listOf("gradlew", "test"), "9.13.1-dev")
            File(root, ".ares/local/note.txt").apply { parentFile.mkdirs(); writeText("local") }
            File(root, ".ares/history/project/old.json").apply { parentFile.mkdirs(); writeText("old") }
            File(root, ".ares/evidence/hardware/review.json").apply { parentFile.mkdirs(); writeText("physical") }
            assertEquals(pending.canonicalContentHash, VerificationRunStore.canonicalContentHash(root))

            File(root, ".ares/subsystems/light.aressubsystem").writeText("changed")
            val report = RobotVerificationReport(
                root.path,
                League.FTC,
                VerificationRunStore.complete(pending, 0),
                emptyList(),
            )

            assertFailsWith<IllegalArgumentException> { VerificationRunStore.saveAndReload(root, report) }
        }
    }

    private inline fun withProject(block: (File) -> Unit) {
        val root = Files.createTempDirectory("ares-verification-run-").toFile()
        try {
            File(root, ".ares/subsystems").mkdirs()
            File(root, ".ares/project.json").writeText("canonical")
            File(root, ".ares/subsystems/light.aressubsystem").writeText("light")
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
