package com.areslib.codegen

import com.areslib.subsystem.*
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinkageMockFailureAuditTest {
    @Test
    fun `compiled linkage mock neutralizes and invalidates feedback before propagating failed steps`() {
        val root = Files.createTempDirectory("ares-linkage-mock-audit")
        val shoulder = SubsystemHardwareScaffolding.create(SubsystemHardwareKind.MOTOR, "shoulder", "Shoulder", SubsystemPlatform.FTC)
        val elbow = SubsystemHardwareScaffolding.create(SubsystemHardwareKind.MOTOR, "elbow", "Elbow", SubsystemPlatform.FTC)
        val documents = listOf(false, true).map { hugeTorque -> SubsystemDocument(
            documentId = if (hugeTorque) "huge-arm" else "normal-arm",
            displayName = "Audit arm",
            kotlinTypeName = if (hugeTorque) "HugeArm" else "NormalArm",
            platform = SubsystemPlatform.FTC,
            hardware = listOf(shoulder.hardware, elbow.hardware),
            stateFields = (shoulder.stateFields + elbow.stateFields).map {
                if (it.fieldId in setOf("shoulderPosition", "elbowPosition")) it.copy(unit = "rad") else it
            },
            controlLoops = shoulder.controlLoops + elbow.controlLoops,
            linkage = SubsystemLinkageDocument(enabled = true, link1LengthMeters = 0.4, link2LengthMeters = 0.3,
                link1MassKg = 1.0, link2MassKg = 0.5, link1CenterOfMassMeters = 0.2, link2CenterOfMassMeters = 0.15,
                joint1ActuatorId = "shoulder", joint2ActuatorId = "elbow", joint1AngleFieldId = "shoulderPosition",
                joint2AngleFieldId = "elbowPosition", joint1TorquePerVoltNm = if (hugeTorque) Double.MAX_VALUE else 1.0,
                joint2TorquePerVoltNm = 1.0),
        ) }
        try {
            val files = documents.flatMap { document ->
                SubsystemKotlinGenerator.generate(document, SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.audit"))
                    .filter { it.artifact in setOf(SubsystemArtifact.IO_CONTRACT, SubsystemArtifact.MOCK_IO) }
                    .map { file -> root.resolve(file.relativePath.substringAfterLast('/')).toFile().apply { writeText(file.content) } }
            }
            val classes = Files.createDirectories(root.resolve("classes"))
            val errors = mutableListOf<String>()
            val result = K2JVMCompiler().exec(object : MessageCollector {
                override fun clear() = errors.clear()
                override fun hasErrors() = errors.isNotEmpty()
                override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                    if (severity.isError) errors += "$location: $message"
                }
            }, Services.EMPTY, K2JVMCompilerArguments().apply {
                freeArgs = files.map { it.path }
                destination = classes.toString()
                classpath = System.getProperty("java.class.path")
                jvmTarget = "17"
                noStdlib = true
                noReflect = true
            })
            assertEquals(ExitCode.OK, result, errors.joinToString("\n"))
            URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
                for (document in documents) {
                    val type = loader.loadClass("org.example.audit.${document.documentId.replace('-', '_')}.Mock${document.kotlinTypeName}IO")
                    val io = type.getConstructor().newInstance()
                    try {
                        type.getMethod("refresh").invoke(io)
                        type.getMethod(shoulder.hardware.commandName(), Double::class.javaPrimitiveType).invoke(io, 12.0)
                        type.getMethod(elbow.hardware.commandName(), Double::class.javaPrimitiveType).invoke(io, 2.0)
                        assertEquals(12.0, type.getMethod("getShoulderCommand").invoke(io))
                        if (document.documentId == "normal-arm") type.getMethod("setSimulationStepSeconds", Double::class.javaPrimitiveType).invoke(io, Double.NaN)
                        val failure = assertFailsWith<InvocationTargetException> { type.getMethod("refresh").invoke(io) }
                        assertTrue(failure.targetException is IllegalArgumentException || failure.targetException is IllegalStateException)
                        assertEquals(false, type.getMethod("getFeedbackValid").invoke(io))
                        assertEquals(false, type.getMethod("getCurrentReadingValid").invoke(io))
                        assertEquals(true, type.getMethod("getOutputFaultLatched").invoke(io))
                        assertEquals(0.0, type.getMethod("getShoulderCommand").invoke(io))
                        assertEquals(0.0, type.getMethod("getElbowCommand").invoke(io))
                        type.getMethod(shoulder.hardware.commandName(), Double::class.javaPrimitiveType).invoke(io, 3.0)
                        assertEquals(0.0, type.getMethod("getShoulderCommand").invoke(io))
                    } finally { type.getMethod("close").invoke(io) }
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
