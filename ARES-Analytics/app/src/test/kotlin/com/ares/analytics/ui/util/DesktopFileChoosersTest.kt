package com.ares.analytics.ui.util

import com.ares.analytics.ui.components.core.AresFileChooserLauncher
import com.ares.analytics.ui.components.core.RobotProjectFlavor
import com.ares.analytics.ui.components.core.detectRobotFlavor
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `detectRobotFlavor correctly identifies robotics project flavors`() {
        withTemporaryDirectory { root ->
            val aresProject = File(root, "ares-bot").apply { mkdirs(); File(this, ".ares").mkdirs() }
            assertEquals(RobotProjectFlavor.ARES, detectRobotFlavor(aresProject))

            val ftcProject = File(root, "ftc-bot").apply { mkdirs(); File(this, "TeamCode").mkdirs() }
            assertEquals(RobotProjectFlavor.FTC, detectRobotFlavor(ftcProject))

            val frcProject = File(root, "frc-bot").apply { mkdirs(); File(this, "src/main/deploy").mkdirs() }
            assertEquals(RobotProjectFlavor.FRC, detectRobotFlavor(frcProject))

            val xrpProject = File(root, "xrp-bot").apply { mkdirs(); File(this, "ares_micro").mkdirs() }
            assertEquals(RobotProjectFlavor.XRP, detectRobotFlavor(xrpProject))

            val gradleProject = File(root, "gradle-bot").apply { mkdirs(); File(this, "settings.gradle.kts").createNewFile() }
            assertEquals(RobotProjectFlavor.GRADLE, detectRobotFlavor(gradleProject))

            val plainDir = File(root, "plain-folder").apply { mkdirs() }
            assertNull(detectRobotFlavor(plainDir))
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

