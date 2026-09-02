package com.ares.analytics.ui.util

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopFileChoosersTest {
    @Test
    fun `save target keeps an accepted extension case insensitively`() {
        withTemporaryDirectory { directory ->
            val selected = File(directory, "report.JSON")

            assertEquals(selected.canonicalFile, DesktopFileChoosers.ensureExtension(selected, listOf("json")))
        }
    }

    @Test
    fun `save target appends the first normalized extension`() {
        withTemporaryDirectory { directory ->
            val selected = File(directory, "robot-project")

            assertEquals(
                File(directory, "robot-project.aresproject.zip").canonicalFile,
                DesktopFileChoosers.ensureExtension(selected, listOf(".aresproject.zip", "zip")),
            )
        }
    }

    @Test
    fun `save target remains unchanged when no extension is required`() {
        withTemporaryDirectory { directory ->
            val selected = File(directory, "README")

            assertEquals(selected.canonicalFile, DesktopFileChoosers.ensureExtension(selected, emptyList()))
        }
    }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val directory = createTempDirectory("ares-file-chooser-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
