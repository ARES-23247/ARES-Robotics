package com.ares.analytics.ui

import androidx.compose.ui.ImageComposeScene
import com.ares.analytics.ui.components.core.AresFileChooserContent
import com.ares.analytics.ui.components.core.AresFileChooserMode
import com.ares.analytics.ui.theme.AresTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class AresFileChooserVisualTest {

    @Test
    fun renderAresFileChooserDirectoryModeScreenshot() {
        val tempDir = createTempDirectory("ares-chooser-visual-test").toFile()
        try {
            // Setup sample robot projects and files
            File(tempDir, "Lightbot-FTC").apply {
                mkdirs()
                File(this, "TeamCode").mkdirs()
                File(this, ".ares").mkdirs()
            }
            File(tempDir, "Marvin-FRC").apply {
                mkdirs()
                File(this, "src/main/deploy").mkdirs()
            }
            File(tempDir, "XRP-Starter").apply {
                mkdirs()
                File(this, "ares_micro").mkdirs()
            }
            File(tempDir, "Generic-Gradle").apply {
                mkdirs()
                File(this, "settings.gradle.kts").createNewFile()
            }
            File(tempDir, "telemetry_run_01.jsonl").createNewFile()
            File(tempDir, "field_background.png").createNewFile()
            File(tempDir, "match_replay.mp4").createNewFile()

            val scene = ImageComposeScene(940, 640)
            try {
            scene.setContent {
                AresTheme {
                    AresFileChooserContent(
                        mode = AresFileChooserMode.DIRECTORY,
                        dialogTitle = "Choose where to create the robot project",
                        initialDirectory = tempDir,
                        filterDescription = "Robotics Project Directory",
                        approveButtonText = "Select Folder",
                        onConfirm = {},
                        onCancel = {},
                    )
                }
            }

            val image = scene.render()
            val data = image.encodeToData(EncodedImageFormat.PNG)
            val outputDir = File("build/diagnostics/file-chooser-tests").apply { mkdirs() }
            val outputFile = File(outputDir, "ares_file_chooser_modern.png")
            if (data != null) {
                outputFile.writeBytes(data.bytes)
                println("Rendered AresFileChooser screenshot to: ${outputFile.absolutePath}")
            }

            data?.close()
            image.close()
            assertTrue(outputFile.exists() && outputFile.length() > 10_000, "Screenshot should be rendered with content")
            } finally { scene.close() }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
