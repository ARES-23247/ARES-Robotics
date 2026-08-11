package com.areslib.codegen

import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.mergeSubsystemCapabilities
import com.areslib.subsystem.subsystem
import com.areslib.subsystem.subsystemTargetCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubsystemKotlinGeneratorTest {
    @Test
    fun `generated suite exposes readable DSL typed runtime and safe cached IO`() {
        val document = subsystem("intake", "Intake", SubsystemPlatform.FTC) {
            description = "Student \"intake\"\nwith notes"
            requiredAtStartup = false
            val power = state.double("power", "Power", SubsystemFieldRole.TARGET, 0.0)
            val motor = hardware.motor("roller", "Roller") { hardwareMapName = "intake" }
            control.direct("rollerControl", "Roller control", motor, power) {
                minimumOutput = -12.0
                maximumOutput = 12.0
            }
        }

        val files = SubsystemKotlinGenerator.generate(
            document,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.generated.subsystems"),
        )
        assertEquals(
            files.sortedWith(compareBy<GeneratedSubsystemFile> { it.sourceSet.ordinal }.thenBy { it.relativePath }),
            files,
        )
        val definition = files.single { it.relativePath.endsWith("IntakeDefinition.kt") }.content
        val io = files.single { it.relativePath.endsWith("FtcIntakeIO.kt") }.content
        val subsystem = files.single { it.relativePath.endsWith("IntakeSubsystem.kt") }.content
        val controller = files.single { it.relativePath.endsWith("IntakeController.kt") }.content
        assertTrue(definition.contains("val document = subsystem("))
        assertTrue(definition.contains("Student \\\"intake\\\"\\nwith notes"))
        assertTrue(io.contains("value.takeIf(Double::isFinite) ?: 0.0"))
        assertTrue(!io.contains("catch (_: Exception) { safe() }"))
        assertTrue(subsystem.contains("UpdateNamedSubsystemState"))
        assertTrue(controller.contains("takeIf(Double::isFinite) ?: 0.0"))
        assertTrue(files.any { it.sourceSet == GeneratedSubsystemSourceSet.TEST })

        val registry = SubsystemKotlinGenerator.generateRegistry(
            listOf(document),
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.generated.subsystems"),
        ).content
        assertTrue(registry.contains("subsystem.intake.set.power"))
        assertTrue(registry.contains("StateActionTask"))
        assertTrue(registry.contains("current.copy(power = typedValue)"))
        assertTrue(registry.contains("install(\"intake\", false)"))
        assertTrue(registry.contains("Optional generated subsystem"))
    }

    @Test
    fun `FRC generation uses native addressing and never FTC hardware map`() {
        val document = subsystem("climber", "Climber", SubsystemPlatform.FRC) {
            val volts = state.double("volts", "Voltage", SubsystemFieldRole.TARGET, 0.0)
            val motor = hardware.motor("winch", "Winch") { canId = 17; canBus = "CAN2" }
            control.direct("manual", "Manual", motor, volts)
        }
        val files = SubsystemKotlinGenerator.generate(
            document,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FRC, "com.example.generated.subsystems"),
        )
        val io = files.single { it.relativePath.endsWith("FrcClimberIO.kt") }.content
        assertTrue(io.contains("TalonFX(17, \"CAN2\")"))
        assertTrue(!io.contains("HardwareMap"))
    }

    @Test
    fun `PID generation makes sensor conversion filtering and anti-windup explicit`() {
        val document = subsystem("elevator", "Elevator", SubsystemPlatform.FTC) {
            val target = state.double("targetMeters", "Target", SubsystemFieldRole.TARGET, 0.0, "m", 0.0, 1.2)
            val position = state.double("positionMeters", "Position", SubsystemFieldRole.MEASUREMENT, 0.0, "m")
            val motor = hardware.motor("leader", "Leader") {
                hardwareMapName = "elevator"
                measurement(
                    position,
                    com.areslib.subsystem.SubsystemMeasurementSource.MOTOR_POSITION_NATIVE,
                    scale = 0.02,
                )
            }
            control.positionPid("position", "Position", motor, target, position) {
                kP = 4.0
                kI = 0.5
                kD = 0.1
            }
        }
        val files = SubsystemKotlinGenerator.generate(
            document,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.generated.subsystems"),
        )
        val io = files.single { it.relativePath.endsWith("FtcElevatorIO.kt") }.content
        val controller = files.single { it.relativePath.endsWith("ElevatorController.kt") }.content

        assertTrue(io.contains("* 0.02 + 0.0"))
        assertTrue(controller.contains("DerivativeAlpha"))
        assertTrue(controller.contains("CandidateIntegral"))
        assertTrue(controller.contains("Unclamped =="))
        assertTrue(controller.contains("!positionTarget.isFinite()"))
        assertTrue(controller.contains("coerceIn(0.0, 1.2)"))
    }

    @Test
    fun `project generator implements derived subsystem actions through generated registry`() {
        val document = subsystem("intake", "Intake", SubsystemPlatform.FTC) {
            val power = state.double("power", "Power", SubsystemFieldRole.TARGET, 0.0, minimum = -12.0, maximum = 12.0)
            val motor = hardware.motor("roller", "Roller") { hardwareMapName = "intake" }
            control.direct("rollerControl", "Roller control", motor, power)
        }
        val subsystemActions = subsystemTargetCapabilities(listOf(document))
        val catalog = mergeSubsystemCapabilities(
            CapabilityCatalogDocument(projectId = "robot"),
            listOf(document),
        )
        val source = AresKotlinProjectGenerator.generate(
            KotlinProjectCodegenRequest(
                packageName = "org.example.generated",
                catalog = catalog,
                routines = emptyList(),
                subsystemActions = subsystemActions,
                subsystemRegistryFqn = "org.example.generated.subsystems.GeneratedSubsystemRegistry",
            )
        ).source

        assertTrue(source.contains("fun actionSubsystemIntakeSetPower(value: Double): Task = requireNotNull("))
        assertTrue(source.contains("GeneratedSubsystemRegistry.createActionTask(\"subsystem.intake.set.power\", value)"))
    }
}
