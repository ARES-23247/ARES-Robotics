package com.ares.analytics.ui.components.robotstudio

import com.ares.analytics.shared.models.League
import com.areslib.drivetrain.ftcMecanumDrivetrain
import com.areslib.project.AresProjectAuthoringModel
import com.areslib.project.model.EffectiveRobotProject
import com.areslib.project.model.formatValidationReport
import com.areslib.routine.routine
import com.areslib.subsystem.*
import com.areslib.superstructure.superstructure
import java.net.URLClassLoader
import java.nio.file.Files
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import kotlin.test.*

class RobotProjectKotlinRoundTripTest {
    @Test
    fun `Studio output compiles and reconstructs one of each document without rounding geometry`() {
        val snapshot = RobotProjectSpecSnapshot("studio-roundtrip", "23247", "2026", "robot", "Test robot", League.FTC,
            AresProjectAuthoringModel.GUI_OWNED, 0.456789, 0.432109,
            drivetrain = ftcMecanumDrivetrain(),
            subsystems = listOf(subsystem("lift", SubsystemPlatform.FTC) {
                val target = state.targetDouble("target", minimum = -1.0, maximum = 1.0)
                val motor = hardware.motor("lift")
                control.openLoop("drive", motor, target)
            }),
            superstructures = listOf(superstructure(id = "coordinator", initialStateId = "IDLE", faultStateId = "IDLE") {
                val liftTarget = port("lift", "target")
                state("IDLE") { targetDouble(liftTarget, 0.0) }
            }),
            routines = listOf(routine("pause") { waitSeconds(0.2) }),
        )
        val root = Files.createTempDirectory("studio-kotlin-export")
        try {
            val source = root.resolve("Export.kt")
            Files.writeString(source, """
                import com.areslib.controls.*
                import com.areslib.drivetrain.*
                import com.areslib.project.*
                import com.areslib.project.model.*
                import com.areslib.routine.*
                import com.areslib.subsystem.*
                import com.areslib.superstructure.*
                fun exported() = ${renderRobotProjectKotlinDsl(snapshot)}
            """.trimIndent())
            val errors = mutableListOf<String>()
            val result = K2JVMCompiler().exec(object : MessageCollector {
                override fun clear() = errors.clear()
                override fun hasErrors() = errors.isNotEmpty()
                override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                    if (severity.isError) errors += "$location: $message"
                }
            }, Services.EMPTY, K2JVMCompilerArguments().apply {
                freeArgs = listOf(source.toString()); destination = root.resolve("classes").toString()
                classpath = System.getProperty("java.class.path"); jvmTarget = "17"; noStdlib = true; noReflect = true
            })
            assertEquals(ExitCode.OK, result, errors.joinToString("\n"))
            URLClassLoader(arrayOf(root.resolve("classes").toUri().toURL()), javaClass.classLoader).use {
                val actual = it.loadClass("ExportKt").getMethod("exported").invoke(null) as EffectiveRobotProject
                assertTrue(actual.isValid, actual.formatValidationReport())
                assertEquals(snapshot.routines, actual.raw.routines)
                assertEquals(snapshot.subsystems, actual.raw.subsystems)
                assertEquals(snapshot.superstructures, actual.raw.superstructures)
                assertEquals(listOf(snapshot.drivetrain), actual.raw.drivetrains)
                assertEquals(snapshot.robotLengthMeters, actual.raw.metadata!!.robotLengthMeters)
                assertEquals(snapshot.robotWidthMeters, actual.raw.metadata!!.robotWidthMeters)
                assertEquals(snapshot.authoringModel, actual.raw.metadata!!.authoringModel)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
