package org.aresfirst.starter.frc

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class StarterRepositoryArchitectureTest {
    private val root = File(System.getProperty("user.dir")).canonicalFile

    @Test
    fun `starter delegates generated scheduling and has explicit dependency provenance`() {
        val runtime = File(
            root,
            "src/main/kotlin/org/aresfirst/starter/frc/generatedruntime/FrcGeneratedControlsRuntime.kt",
        ).readText()
        val build = File(root, "build.gradle").readText()

        assertTrue(runtime.contains("FrcGeneratedProjectControlsRuntime"))
        assertTrue(runtime.contains("definition = GeneratedAresProject.runtimeDefinition"))
        assertFalse(runtime.contains("TaskExecutor"))
        assertTrue(build.contains("org.aresfirst.ares:frc-runtime"))
        assertFalse(build.contains("mavenLocal"))
        assertFalse(File(root, "src/main/kotlin/org/aresfirst/starter/frc/generated").exists())
    }

    @Test
    fun `FRC starter simulator remains free of FTC OpMode and Control Hub APIs`() {
        val sources = File(root, "src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .joinToString("\n") { it.readText() }

        assertFalse(sources.contains("import com.qualcomm.robotcore"))
        assertFalse(sources.contains("import org.firstinspires.ftc"))
    }
}
