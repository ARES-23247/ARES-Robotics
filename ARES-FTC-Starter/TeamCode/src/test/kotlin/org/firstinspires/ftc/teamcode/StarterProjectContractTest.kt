// ARES OWNERSHIP: GENERATED STARTER
package org.firstinspires.ftc.teamcode

import org.firstinspires.ftc.teamcode.generated.GeneratedAresProject
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class StarterProjectContractTest {
    @Test
    fun generatedProjectAlwaysKeepsTheExplicitNeutralRecoveryPath() {
        assertTrue("drivetrain.recoverNeutral" in GeneratedAresProject.knownActionKeys)
        assertTrue(GeneratedAresProject.knownActionKeys.all(String::isNotBlank))
    }

    @Test
    fun generatedMecanumDrivebaseKeepsFourDistinctMotorsAndSupportedLocalization() {
        val motorHardwareIds = listOf(
            GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_FL.HARDWARE_ID,
            GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_FR.HARDWARE_ID,
            GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_RL.HARDWARE_ID,
            GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_RR.HARDWARE_ID,
        )
        assertTrue(motorHardwareIds.all(String::isNotBlank))
        assertTrue(motorHardwareIds.distinct().size == 4)
        assertTrue(
            GeneratedAresDrivebaseConfig.Localization.PRIMARY_ODOMETRY.KIND in
                setOf("WHEEL_ENCODERS_IMU", "PINPOINT"),
        )
        assertTrue(GeneratedAresDrivebaseConfig.Localization.PRIMARY_ODOMETRY.COMPONENT_UIDS.isNotEmpty())
    }

    @Test
    fun starterKeepsOnlyAThinFtcAdapterAndNoCheckedInMechanicalOutput() {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(workingDirectory).canonicalFile, File::getParentFile)
            .first { File(it, "TeamCode").isDirectory && File(it, ".ares/project.json").isFile }
        val runtimeFile = listOf(
            File(
                root,
                "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/dsl/FtcGeneratedProjectRuntime.kt",
            ),
            File(
                root.parentFile,
                "templates/ftc/runtime/src/main/kotlin/org/firstinspires/ftc/teamcode/dsl/FtcGeneratedProjectRuntime.kt",
            ),
        ).firstOrNull(File::isFile)
        assertNotNull("FTC runtime must come from the standalone mirror or canonical monorepo template", runtimeFile)
        val runtime = requireNotNull(runtimeFile).readText()

        assertTrue(runtime.contains("GeneratedProjectControlRuntime"))
        assertTrue(runtime.contains("GeneratedAresProject.runtimeDefinition"))
        assertFalse(runtime.contains("private val directTaskExecutor"))
        val autoHost = File(
            root,
            "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/dsl/AresAutoDSL.kt",
        ).readText()
        assertTrue(autoHost.contains("FtcGeneratedAutonomousOpMode"))
        assertFalse(autoHost.contains("override fun loop()"))
        assertFalse(File(root, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/generated").exists())
    }

    @Test
    fun ftcStarterSimulatorDoesNotImportFrcLifecycleOrVendorApis() {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(workingDirectory).canonicalFile, File::getParentFile)
            .first { File(it, "TeamCode").isDirectory && File(it, ".ares/project.json").isFile }
        val sources = File(root, "simulator/src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .joinToString("\n") { it.readText() }

        assertFalse(sources.contains("import edu.wpi.first.wpilibj.TimedRobot"))
        assertFalse(sources.contains("import com.ctre.phoenix"))
    }
}
