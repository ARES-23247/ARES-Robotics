package com.areslib.codegen

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import java.nio.file.Files
import java.nio.file.Path
import java.net.URLClassLoader
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KotlinCodegenNameAuditTest {
    @TempDir lateinit var root: Path

    @Test
    fun `underscore-only declarations are rejected before project rendering`() {
        for (name in listOf("_", "__", "___")) {
            assertFalse(name.isKotlinIdentifier(), name)
            assertThrows(IllegalArgumentException::class.java) {
                AresKotlinProjectGenerator.generate(request().copy(objectName = name))
            }
            assertThrows(IllegalArgumentException::class.java) {
                AresKotlinProjectGenerator.generate(request().copy(registryInterfaceName = name))
            }
        }
    }

    @Test
    fun `project registry references reject keyword segments and source fragments`() {
        for (fqn in listOf("org.when.Registry", "org.example.__", "org.example.Registry; error(\"injected\")", "Registry", "")) {
            assertThrows(IllegalArgumentException::class.java, {
                AresKotlinProjectGenerator.generate(request().copy(subsystemRegistryFqn = fqn))
            }, fqn)
            assertThrows(IllegalArgumentException::class.java, {
                AresKotlinProjectGenerator.generate(request().copy(generatedActionRegistryBindings = mapOf("test.action" to fqn)))
            }, fqn)
        }
    }

    @Test
    fun `subsystem sources and standalone registries share package validation`() {
        val document = SubsystemTemplates.create(SubsystemTemplate.INTAKE_CONVEYOR,
            "audit-intake", "AuditIntake", SubsystemPlatform.FTC)
        for (name in listOf("org.when", "org.__", "org.example; bad", "", "org..example")) {
            val target = SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, name)
            assertThrows(IllegalArgumentException::class.java, {
                SubsystemKotlinGenerator.generate(document, target)
            }, name)
            assertThrows(IllegalArgumentException::class.java, {
                SubsystemKotlinGenerator.generateRegistry(emptyList(), target)
            }, name)
        }
    }

    @Test
    fun `compiler confirms reserved names and accepts a valid generated project`() {
        val invalid = root.resolve("Reserved.kt")
        Files.writeString(invalid, "package audit\nobject __\n")
        assertEquals(ExitCode.COMPILATION_ERROR, compile(invalid))
        val generated = AresKotlinProjectGenerator.generate(request().copy(objectName = "_Robot2"))
        val valid = root.resolve("Generated.kt")
        Files.writeString(valid, generated.source)
        assertEquals(ExitCode.OK, compile(valid))
        assertTrue(AresKotlinProjectGenerator.hasValidEmbeddedSourceHash(generated.source))
    }

    @Test
    fun `verification JSON survives UTF8 writing across a surrogate chunk boundary`() {
        val prefix = "{\"payload\":\""
        val payload = prefix + "x".repeat(7_999 - prefix.length) + "\uD83D\uDE80" + "\"}"
        val generated = ProjectVerificationKotlinGenerator.generate(ProjectVerificationCodegenRequest(
            packageName = "org.example.audit",
            platform = ControllerInputPlatform.FRC,
            projectJson = payload,
            catalogJson = "{}",
            drivetrainJson = emptyList(),
            subsystemJson = emptyList(),
            superstructureJson = emptyList(),
            controllerProfileJson = emptyList(),
            controlSchemeJson = emptyList(),
            routineJson = emptyList(),
            autonomousCatalogJson = null,
        ))
        val source = root.resolve("Verification.kt")
        Files.writeString(source, generated.content)
        assertEquals(ExitCode.OK, compile(source))
        URLClassLoader(arrayOf(root.resolve("Verification.kt.classes").toUri().toURL()), javaClass.classLoader).use { loader ->
            val type = loader.loadClass("org.example.audit.GeneratedAresProjectContractTest")
            val field = type.getDeclaredField("PROJECT_JSON").apply { isAccessible = true }
            assertEquals(payload, field.get(null))
        }
    }

    @Test
    fun `project verification rejects invalid Kotlin packages`() {
        val request = ProjectVerificationCodegenRequest("org.example.audit", ControllerInputPlatform.FRC,
            "{}", "{}", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
        for (name in listOf("org.when", "org.__", "org..example", "org.example; bad")) {
            assertThrows(IllegalArgumentException::class.java) {
                ProjectVerificationKotlinGenerator.generate(request.copy(packageName = name))
            }
        }
    }

    private fun request() = KotlinProjectCodegenRequest(
        packageName = "org.example.audit",
        catalog = CapabilityCatalogDocument(projectId = "audit-project",
            actions = listOf(ActionDescriptor("test.action", "Test", "Test action"))),
        routines = emptyList(),
    )

    private fun compile(source: Path): ExitCode {
        val errors = mutableListOf<String>()
        val result = K2JVMCompiler().exec(object : MessageCollector {
            override fun clear() = errors.clear()
            override fun hasErrors() = errors.isNotEmpty()
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) errors += "$location: $message"
            }
        }, Services.EMPTY, K2JVMCompilerArguments().apply {
            freeArgs = listOf(source.toString())
            destination = root.resolve(source.fileName.toString() + ".classes").toString()
            classpath = System.getProperty("java.class.path")
            jvmTarget = "17"
            noStdlib = true
            noReflect = true
        })
        println("${source.fileName}: $result ${errors.joinToString("; ")}")
        return result
    }
}
