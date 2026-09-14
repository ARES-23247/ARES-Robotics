package com.areslib.codegen

import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.*
import com.areslib.input.InputFrame
import com.areslib.runtime.GeneratedProjectControlRuntime
import com.areslib.runtime.GeneratedProjectDefinition
import com.areslib.state.RobotState
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Executes generated code through the same shared runtime definition used by robot league hosts. */
class GeneratedRuntimeIsolationAuditTest {
    @TempDir lateinit var root: Path
    @Volatile private var retainedSource: String = ""

    @BeforeEach
    fun compileGeneratedProject() {
        val profile = ControllerProfileDocument(
            documentId = "driver", displayName = "Driver",
            controls = listOf(ControllerControlDocument(
                controlId = "throttle", displayName = "Throttle", type = ControllerControlTypeDocument.AXIS,
                anchor = ControllerAnchorDocument(0.5, 0.5),
                mappings = listOf(ControllerInputMappingDocument(ControllerInputPlatform.FRC, axisIndex = 0)),
            )),
        )
        val scheme = ControlSchemeDocument(
            documentId = "competition", name = "Competition",
            controllers = listOf(ControllerAssignment("driver", "Driver", profile.documentId, 0)),
            bindings = listOf(ControlBindingDocument(
                bindingId = "drive.vx", displayName = "Drive forward",
                source = ControlSourceDocument(ControlSourceKind.AXIS_VALUE, "driver", listOf("throttle"),
                    transform = AxisTransformDocument(deadband = 0.0)),
                event = ControlEvent.VALUE, target = ControlTargetDocument(ControlTargetKind.DRIVE, DriveAxisKeys.VX),
                analogPolicy = AnalogControlPolicyDocument(),
            )),
        )
        val source = AresKotlinProjectGenerator.generate(KotlinProjectCodegenRequest(
            packageName = "audit.generated", catalog = CapabilityCatalogDocument(projectId = "audit"),
            routines = emptyList(), controllerProfiles = listOf(profile), controlSchemes = listOf(scheme),
            targetInputPlatform = ControllerInputPlatform.FRC,
        ))
        val file = root.resolve("Generated.kt")
        Files.writeString(file, source.source)
        val errors = mutableListOf<String>()
        val result = K2JVMCompiler().exec(object : MessageCollector {
            override fun clear() = errors.clear()
            override fun hasErrors() = errors.isNotEmpty()
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) errors += "$location: $message"
            }
        }, Services.EMPTY, K2JVMCompilerArguments().apply {
            freeArgs = listOf(file.toString())
            destination = root.resolve("classes").toString()
            classpath = System.getProperty("java.class.path")
            jvmTarget = "17"
            noStdlib = true
            noReflect = true
        })
        assertEquals(ExitCode.OK, result, errors.joinToString("\n"))
    }

    @Test
    fun `new runtime starts neutral and owns its drive state independently`() = withProject { project ->
        val a = project.host()
        val b = project.host()
        try {
            a.update(0.0, 1_000_000L)
            a.update(0.7, 21_000_000L)
            a.emit()
            assertEquals(0.7, a.vx, 1e-12)
            b.emit()
            assertEquals(0.0, b.vx, 0.0, "A new host must not emit the other host's last command")
            b.update(0.0, 1_000_000L)
            b.update(-0.4, 21_000_000L)
            b.emit()
            a.emit()
            assertEquals(-0.4, b.vx, 1e-12)
            assertEquals(0.7, a.vx, 1e-12)
        } finally {
            a.runtime.cancelAll("test cleanup")
            b.runtime.cancelAll("test cleanup")
        }
    }

    @Test
    fun `cancelling one runtime leaves another runtime drive command intact`() = withProject { project ->
        val a = project.host()
        val b = project.host()
        try {
            a.update(0.0, 1_000_000L)
            b.update(0.0, 1_000_000L)
            a.update(0.7, 21_000_000L)
            b.update(-0.4, 21_000_000L)
            b.emit()
            assertEquals(-0.4, b.vx, 1e-12)
            a.runtime.cancelAll("first robot stopped")
            a.emit()
            b.emit()
            assertEquals(0.0, a.vx, 0.0)
            assertEquals(-0.4, b.vx, 1e-12, "Cancellation must only neutralize the owning host")
        } finally {
            a.runtime.cancelAll("test cleanup")
            b.runtime.cancelAll("test cleanup")
        }
    }

    @Test
    fun `stable runtime metadata does not allocate on repeated telemetry reads`() = withProject { project ->
        val host = project.host()
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        val wasEnabled = bean.isThreadAllocatedMemoryEnabled
        try {
            bean.isThreadAllocatedMemoryEnabled = true
            repeat(20_000) { retainedSource = host.runtime.controlsSource }
            val threadId = Thread.currentThread().id
            val before = bean.getThreadAllocatedBytes(threadId)
            repeat(10_000) { retainedSource = host.runtime.controlsSource }
            val allocated = bean.getThreadAllocatedBytes(threadId) - before
            println("10,000 stable controlsSource reads allocated $allocated bytes")
            assertTrue(allocated in 0..16_384, "Stable runtime metadata allocated $allocated bytes")
            assertTrue(retainedSource.startsWith("generated:competition:"))
            assertEquals(1, host.runtime.activeControllerPortCount)
        } finally {
            bean.isThreadAllocatedMemoryEnabled = wasEnabled
            host.runtime.cancelAll("test cleanup")
        }
    }

    private fun withProject(block: (Project) -> Unit) {
        URLClassLoader(arrayOf(root.resolve("classes").toUri().toURL()), javaClass.classLoader).use { loader ->
            val generated = loader.loadClass("audit.generated.GeneratedAresProject")
            @Suppress("UNCHECKED_CAST")
            val definition = generated.getMethod("getRuntimeDefinition").invoke(generated.getField("INSTANCE").get(null))
                as GeneratedProjectDefinition<Any>
            block(Project(loader, definition))
        }
    }

    private class Project(private val loader: ClassLoader, private val definition: GeneratedProjectDefinition<Any>) {
        fun host(): Host = Host(loader, definition)
    }

    private class Host(loader: ClassLoader, definition: GeneratedProjectDefinition<Any>) {
        var vx = Double.NaN
        private val frame = InputFrame()
        private val capabilities = Proxy.newProxyInstance(loader,
            arrayOf(loader.loadClass("audit.generated.GeneratedAresProjectCapabilities"))) { proxy, method, args ->
            when (method.name) {
                "onDriveCommand" -> { vx = args[0] as Double; null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "Audit capabilities"
                else -> null
            }
        }
        val runtime = GeneratedProjectControlRuntime(definition, ::RobotState, {}, capabilities, 6)
        fun update(value: Double, time: Long) {
            frame.beginSample(true, reportedAxisCount = 1, sampleTimeNanos = time)
            frame.setAxis(0, value)
            runtime.updatePort(0, frame, time)
        }
        fun emit() = runtime.emitDriveCommand()
    }
}
