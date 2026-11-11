package com.areslib.codegen

import com.areslib.controls.*
import com.areslib.drivetrain.*
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.model.*
import com.areslib.routine.*
import com.areslib.subsystem.*
import com.areslib.superstructure.*
import com.areslib.tuning.*
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Compiles the renderer output itself, then executes it through the public API. */
class ExportedKotlinRoundTripTest {
    @TempDir lateinit var temporary: Path

    private fun evaluate(expressions: List<String>): List<*> {
        val source = temporary.resolve("Export.kt")
        Files.writeString(source, """
            import com.areslib.controls.*
            import com.areslib.drivetrain.*
            import com.areslib.project.*
            import com.areslib.project.model.*
            import com.areslib.routine.*
            import com.areslib.subsystem.*
            import com.areslib.superstructure.*
            import com.areslib.tuning.*
            fun exported(): List<Any> = listOf(
            ${expressions.joinToString(",\n")}
            )
        """.trimIndent())
        val errors = mutableListOf<String>()
        val result = K2JVMCompiler().exec(object : MessageCollector {
            override fun clear() = errors.clear()
            override fun hasErrors() = errors.isNotEmpty()
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) errors += "$location: $message"
            }
        }, Services.EMPTY, K2JVMCompilerArguments().apply {
            freeArgs = listOf(source.toString())
            destination = temporary.resolve("classes").toString()
            classpath = System.getProperty("java.class.path")
            jvmTarget = "17"; noStdlib = true; noReflect = true
        })
        assertEquals(ExitCode.OK, result, errors.joinToString("\n") + "\n" + Files.readString(source))
        return URLClassLoader(arrayOf(temporary.resolve("classes").toUri().toURL()), javaClass.classLoader).use {
            it.loadClass("ExportKt").getMethod("exported").invoke(null) as List<*>
        }
    }

    @Test
    fun `both homing polarities survive compiled Kotlin with nondefault dwell timeout and zero`() {
        val originals = listOf(true, false).map { active ->
            subsystem("lift", SubsystemPlatform.FTC) {
                description = "Quoted \"limit\" with dollar \$ and newline\nnext"
                val target = state.targetDouble("target", minimum = -12.0, maximum = 12.0)
                val limit = state.measurementBoolean("limit")
                val motor = hardware.motor("lift")
                hardware.digitalInput("limitSwitch") { measurement(limit, SubsystemMeasurementSource.DIGITAL_STATE) }
                control.openLoop("drive", motor, target)
                safety.homing.digitalSensor(motor, limit, -0.23, activeWhen = active,
                    dwellMs = 123, timeoutMs = 4567, zeroPosition = 0.12)
            }
        }
        val restored = evaluate(originals.map { it.toKotlinDsl() }).map { it as SubsystemDocument }
        originals.zip(restored).forEach { (before, after) ->
            assertEquals(before.safety.homing, after.safety.homing)
            assertEquals(SubsystemDocumentCodec.encode(before), SubsystemDocumentCodec.encode(after))
        }
    }

    @Test
    fun `standard and customized FTC and FRC drivetrains retain every canonical field`() {
        val ftc = ftcMecanumDrivetrain { tuningDouble("drive.kp", 2.5) }
        val customFtc = ftc.copy(components = ftc.components.map {
            if (it.hardwareId == "fl") it.copy(inverted = true, currentLimitAmps = 12.0, controllerModel = "Reviewed motor") else it
        }, localization = ftc.localization.copy(primaryOdometry = ftc.localization.primaryOdometry.copy(xOffsetMeters = 0.123, scalar = 1.01)))
        val frc = frcCtreSwerveDrivetrain(vendorSourcePath = "src/main/java/robot/TunerConstants.java",
            sourceSha256 = "b".repeat(64), drivetrainConstantsClassName = "robot.TunerConstants")
        val customFrc = frc.copy(modules = frc.modules.map { it.copy(xMeters = it.xMeters * 1.2, yMeters = it.yMeters * 0.8) },
            components = frc.components.map { if (it.role == DrivetrainComponentRole.DRIVE_MOTOR) it.copy(inverted = true, currentLimitAmps = 32.0) else it })
        val originals = listOf(ftc, customFtc, frc, customFrc)
        val restored = evaluate(originals.map { it.toKotlinDsl() }).map { it as DrivetrainDocument }
        originals.zip(restored).forEach { (before, after) ->
            assertEquals(DrivetrainDocumentCodec.encode(before), DrivetrainDocumentCodec.encode(after))
        }
    }

    @Test
    fun `whole project export registers each document once and preserves cross document references`() {
        val tuning = tuningComponent("robot.global", "roundtrip") { double("vision.latency", 100.0, unit = "ms") }
        val original = aresProject("roundtrip", AresLeague.FTC, projectRoot = "projects/reviewed") {
            identity("23247", "2026", displayName = "Reviewed robot")
            geometry(0.456789, 0.432109)
            ftcRuntime(limelightProxyEnabled = true)
            ftcMecanumDrivetrain()
            subsystem("lift", SubsystemPlatform.FTC) {
                val target = state.targetDouble("target", minimum = -1.0, maximum = 1.0)
                val motor = hardware.motor("lift")
                control.openLoop("drive", motor, target)
            }
            superstructure("coordinator", "IDLE", "IDLE") {
                val liftTarget = port("lift", "target")
                state("IDLE") { targetDouble(liftTarget, 0.0) }
            }
            action("robot.score")
            condition("robot.ready")
            routine("pause") {
                waitUntil("robot.ready", timeoutSeconds = 1.0)
                parallel { action("robot.score"); waitSeconds(0.2) }
            }
            autonomousCatalog("start") { entry("start", "pause", xMeters = 0.0, yMeters = 0.0, headingDegrees = 0.0) }
            controllerProfile(ControllerProfileDocument(documentId = "reviewed-pad", displayName = "Reviewed pad", controls = listOf(
                ControllerControlDocument("a", "A", ControllerControlTypeDocument.BUTTON, anchor = ControllerAnchorDocument(0.5, 0.5),
                    mappings = listOf(ControllerInputMappingDocument(ControllerInputPlatform.FTC, buttonIndex = 3)))
            )))
            controlScheme("teleop") { controller("driver", "reviewed-pad", 0) { button("a").onPress { routine("pause") } } }
            tuningComponent(tuning)
            tuningProfile(com.areslib.tuning.tuningProfile("competition", "roundtrip") { assignDouble(tuning.parameters.single().uid, 90.0) })
        }
        assertTrue(original.isValid, original.formatValidationReport())
        val restored = evaluate(listOf(original.toKotlinDsl())).single() as EffectiveRobotProject
        assertTrue(restored.isValid, restored.formatValidationReport())
        assertEquals(original.raw.projectRoot, restored.raw.projectRoot)
        assertEquals(original.raw.metadata, restored.raw.metadata)
        assertEquals(original.raw.baseCapabilityCatalog, restored.raw.baseCapabilityCatalog)
        assertEquals(original.raw.controllerProfiles, restored.raw.controllerProfiles)
        assertEquals(original.raw.tuningComponents, restored.raw.tuningComponents)
        assertEquals(original.raw.tuningProfiles, restored.raw.tuningProfiles)
        assertEquals(original.raw.routines, restored.raw.routines)
        assertEquals(original.raw.controlSchemes, restored.raw.controlSchemes)
        assertEquals(original.raw.autonomousCatalog, restored.raw.autonomousCatalog)
        assertEquals(original.raw.superstructures, restored.raw.superstructures)
        assertEquals(original.raw.subsystems, restored.raw.subsystems)
        assertEquals(original.raw.drivetrains, restored.raw.drivetrains)
    }
}
