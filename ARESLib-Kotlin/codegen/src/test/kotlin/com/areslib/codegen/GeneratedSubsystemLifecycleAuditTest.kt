package com.areslib.codegen

import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.subsystem.Subsystem
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
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

/** Executes the emitted lifecycle bridge with generated contracts and explicit controller/IO doubles. */
class GeneratedSubsystemLifecycleAuditTest {
    @TempDir lateinit var root: Path

    @BeforeEach
    fun compileLifecycle() {
        val files = mutableListOf<String>()
        for (name in listOf("timed", "unlimited")) {
            val base = SubsystemTemplates.create(SubsystemTemplate.SIMPLE_ACTUATOR, "arm", "Arm", SubsystemPlatform.FTC)
            val document = base.copy(safety = base.safety.copy(feedbackTimeoutMs = if (name == "timed") 250L else null))
            val pkg = "audit.$name.arm"
            val sources = mapOf(
                "State" to SubsystemContractRenderer.stateSource(document, pkg),
                "IO" to SubsystemContractRenderer.ioSource(document, pkg),
                "Lifecycle" to SubsystemLifecycleRenderer.render(document, pkg),
                "Controller" to """
                    package $pkg
                    import com.areslib.tuning.TuningValue
                    class ArmController(private val io: ArmIO) {
                        fun reset() = Unit
                        fun update(state: ArmState, scale: Double, permit: Boolean) { io.safe() }
                        fun supportsTuningParameter(uid: String) = false
                        fun applyTuningParameter(uid: String, value: TuningValue) = false
                    }
                """.trimIndent(),
                "Registry" to "package audit.$name\nobject GeneratedSubsystemRegistry",
            )
            for ((part, source) in sources) {
                val file = root.resolve("$name$part.kt")
                Files.writeString(file, source)
                files += file.toString()
            }
        }
        val messages = mutableListOf<String>()
        val result = K2JVMCompiler().exec(object : MessageCollector {
            override fun clear() = messages.clear()
            override fun hasErrors() = messages.isNotEmpty()
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) messages += "$location: $message"
            }
        }, Services.EMPTY, K2JVMCompilerArguments().apply {
            freeArgs = files
            destination = root.resolve("classes").toString()
            classpath = System.getProperty("java.class.path")
            jvmTarget = "17"
            noStdlib = true
            noReflect = true
        })
        assertEquals(ExitCode.OK, result, messages.joinToString("\n"))
    }

    @Test
    fun `snapshot validity rejects timestamp overflow and future input even without a timeout`() = withLoader { loader ->
        for (name in listOf("timed", "unlimited")) {
            val host = Host(loader, name)
            val store = Store()
            for ((timestamp, now, valid) in listOf(
                Triple(100L, 100L, true), Triple(100L, 99L, false),
                Triple(Long.MIN_VALUE, 0L, false), Triple(-1L, Long.MAX_VALUE, false),
                Triple(-250L, 0L, true),
            )) {
                host.timestamp = timestamp
                host.subsystem.readSensors(store, now)
                val state = store.state.superstructure.subsystems.getValue("arm")
                assertEquals(valid, state.javaClass.getMethod("getFeedbackValid").invoke(state),
                    "$name feedback=$timestamp now=$now")
            }
            host.timestamp = 100L
            host.subsystem.readSensors(store, 351L)
            val state = store.state.superstructure.subsystems.getValue("arm")
            assertEquals(name == "unlimited", state.javaClass.getMethod("getFeedbackValid").invoke(state))
            host.subsystem.close()
        }
    }

    @Test
    fun `failed neutral write still closes IO and repeated close is idempotent`() = withLoader { loader ->
        val host = Host(loader, "timed")
        val failure = IllegalStateException("neutral failed")
        val closeFailure = IllegalArgumentException("close failed")
        host.safeFailure = failure
        host.closeFailure = closeFailure
        assertSame(failure, assertThrows(IllegalStateException::class.java) { host.subsystem.close() })
        assertTrue(failure.suppressed.any { it === closeFailure })
        assertEquals(1, host.closeCalls)
        assertDoesNotThrow { host.subsystem.close() }
        assertEquals(1, host.closeCalls)
        assertEquals(1, host.safeCalls)
    }

    @Test
    fun `closed lifecycle cannot resume sensor or output work`() = withLoader { loader ->
        val host = Host(loader, "timed")
        val store = Store()
        host.subsystem.close()
        host.subsystem.readSensors(store, 1L)
        host.subsystem.writeOutputs(RobotState(), 1.0)
        assertTrue(store.state.superstructure.subsystems.isEmpty())
        assertEquals(1, host.safeCalls)
        assertEquals(1, host.closeCalls)
    }

    private fun withLoader(block: (ClassLoader) -> Unit) {
        URLClassLoader(arrayOf(root.resolve("classes").toUri().toURL()), javaClass.classLoader).use(block)
    }

    private class Host(loader: ClassLoader, name: String) {
        var timestamp = 0L
        var safeCalls = 0
        var closeCalls = 0
        var safeFailure: Throwable? = null
        var closeFailure: Throwable? = null
        private val ioClass = loader.loadClass("audit.$name.arm.ArmIO")
        private val io = Proxy.newProxyInstance(loader, arrayOf(ioClass)) { _, method, _ ->
            when (method.name) {
                "getFeedbackTimestampMs" -> timestamp
                "safe" -> { safeCalls++; safeFailure?.let { throw it }; null }
                "close" -> { closeCalls++; closeFailure?.let { throw it }; null }
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> true
                    Double::class.javaPrimitiveType -> 0.0
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    String::class.java -> ""
                    else -> null
                }
            }
        }
        val subsystem = loader.loadClass("audit.$name.arm.ArmSubsystem").getConstructor(ioClass).newInstance(io) as Subsystem
    }
}
