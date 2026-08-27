package com.areslib.frc

import com.areslib.frc.hardware.FRCClimberHardwareIO
import com.areslib.frc.hardware.FRCCowlHardwareIO
import com.areslib.frc.hardware.FRCFeederHardwareIO
import com.areslib.frc.hardware.FRCFloorHardwareIO
import com.areslib.frc.hardware.FRCFlywheelHardwareIO
import com.areslib.frc.hardware.FRCIntakeHardwareIO
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class RepositorySafetyContractTest {
    private val projectRoot = File(System.getProperty("user.dir"))

    @Test
    fun `mechanism hardware and simulation resources are closeable`() {
        val owners = listOf(
            FRCFlywheelHardwareIO::class.java,
            FRCCowlHardwareIO::class.java,
            FRCIntakeHardwareIO::class.java,
            FRCFeederHardwareIO::class.java,
            FRCFloorHardwareIO::class.java,
            FRCClimberHardwareIO::class.java,
            Dyn4jSimulation::class.java
        )
        owners.forEach { owner ->
            assertTrue(AutoCloseable::class.java.isAssignableFrom(owner), "${owner.simpleName} must own teardown")
        }
    }

    @Test
    fun `only compiled ares routines and supported dependencies ship`() {
        val looseDeployRoot = File(projectRoot, "src/main/deploy/ares")
        assertFalse(looseDeployRoot.exists() && looseDeployRoot.walkTopDown().any(File::isFile))
        assertFalse(File(projectRoot, "vendordeps/WPILibNewCommands.json").exists())
        assertTrue(File(projectRoot, ".ares/action-catalog.json").isFile)
        assertTrue(File(projectRoot, ".ares/routines/do-nothing.aresroutine").isFile)
        assertFalse(File(projectRoot, ".ares/autonomous-catalog.json").readText().contains("sim-drive-and-shoot"))
        assertFalse(File(projectRoot, ".ares/routines/sim-drive-and-shoot.aresroutine").exists())
        assertTrue(File(projectRoot, "src/test/resources/ares/routines/sim-drive-and-shoot.aresroutine").isFile)
    }

    @Test
    fun `offset fetch validates a temporary download before atomic replacement`() {
        val buildScript = File(projectRoot, "build.gradle").readText()
        assertTrue(buildScript.contains("createTempFile"))
        assertTrue(buildScript.contains("JsonSlurper"))
        assertTrue(buildScript.contains("ATOMIC_MOVE"))
        assertTrue(buildScript.contains("throw new GradleException"))
        assertFalse(buildScript.contains("scp\", \"-o\", \"StrictHostKeyChecking=yes\", \"-o\", \"BatchMode=yes\", \"lvuser@10.232.47.2:/home/lvuser/swerve_offsets_runtime.json\", targetFile.path"))
    }

    @Test
    fun `zero-scheme project still installs the generated controller lifecycle safely`() {
        val lifecycleSource = File(
            projectRoot,
            "src/main/kotlin/com/areslib/frc/ARESRobot.kt"
        ).readText()
        assertTrue(lifecycleSource.contains("generatedControlsRuntime.update()"))
        assertTrue(lifecycleSource.contains("cancelGeneratedControls(\"FRC disabled\")"))
        assertTrue(lifecycleSource.contains("FrcGeneratedProjectControlsRuntime"))
        assertTrue(lifecycleSource.contains("definition = GeneratedAresProject.runtimeDefinition"))
        assertFalse(
            File(projectRoot, "src/main/kotlin/com/areslib/frc/generatedruntime/FrcGeneratedControlsRuntime.kt").exists()
        )
        assertFalse(File(projectRoot, "build.gradle").readText().contains("mavenLocal"))
        assertFalse(File(projectRoot, "settings.gradle").readText().contains("mavenLocal"))
    }

    @Test
    fun `FRC simulator remains free of FTC OpMode and Control Hub APIs`() {
        val sources = File(projectRoot, "src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .joinToString("\n") { it.readText() }

        assertFalse(sources.contains("import com.qualcomm.robotcore"))
        assertFalse(sources.contains("import org.firstinspires.ftc"))
    }
}
