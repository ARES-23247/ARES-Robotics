package com.ares.analytics.viewmodel.subsystem

import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemImplementationDocument
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemSourceOwnership
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class XrpExtensionScaffoldTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val root: Path get() = temporary.root.toPath()

    private fun document(path: String = "extensions/arm.py") = SubsystemDocument(
        documentId = "arm", displayName = "Arm", kotlinTypeName = "Arm",
        platform = SubsystemPlatform.XRP,
        implementation = SubsystemImplementationDocument(
            kind = SubsystemImplementationKind.HAND_AUTHORED,
            ownership = SubsystemSourceOwnership.USER_OWNED,
            sourceFiles = listOf(path),
        ),
    )

    @Test
    fun `new scaffold executes both factories and neutral recovery in Python`() {
        val python = listOf("python", "python3").firstOrNull { command ->
            runCatching {
                val probe = ProcessBuilder(command, "--version").redirectErrorStream(true).start()
                try { probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0 }
                finally { if (probe.isAlive) probe.destroyForcibly() }
            }.getOrDefault(false)
        }
        assumeTrue("Python interpreter required to execute the generated XRP source", python != null)
        ensureXrpExtensionScaffold(root.toString(), document())
        val verificationScript = """
            import runpy, sys
            module = runpy.run_path(sys.argv[1])
            hardware = object()
            for factory in ('create_subsystem', 'create_simulated_subsystem'):
                subsystem = module[factory](hardware)
                assert subsystem.hardware_factory is hardware
                assert subsystem.document_id == 'arm'
                assert subsystem.capability_action_keys == ()
                assert subsystem.faulted is False
                subsystem.periodic(0.02)
                subsystem.stop()
                subsystem.faulted = True
                assert subsystem.recover_neutral() is True
                assert subsystem.faulted is False
                try:
                    subsystem.handle_action('unsupported', {})
                except ValueError:
                    pass
                else:
                    raise AssertionError('Unknown action was accepted')
        """.trimIndent()
        val log = root.resolve("python.log").toFile()
        val process = ProcessBuilder(python!!, "-c", verificationScript, root.resolve("extensions/arm.py").toString())
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            check(process.waitFor(15, TimeUnit.SECONDS)) { "Generated Python execution timed out" }
            assertEquals(0, process.exitValue(), log.readText())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test
    fun `existing user source remains byte identical`() {
        val file = root.resolve("extensions/arm.py").toFile()
        file.parentFile.mkdirs()
        file.writeText("# USER-OWNED custom implementation\r\n")
        val bytes = file.readBytes().toList()
        ensureXrpExtensionScaffold(root.toString(), document())
        assertEquals(bytes, file.readBytes().toList())
    }

    @Test
    fun `wrong platform or generated ownership does not create a scaffold`() {
        ensureXrpExtensionScaffold(root.toString(), document().copy(platform = SubsystemPlatform.FTC))
        val generated = document().copy(implementation = SubsystemImplementationDocument())
        ensureXrpExtensionScaffold(root.toString(), generated)
        assertFalse(root.resolve("extensions/arm.py").toFile().exists())
    }

    @Test
    fun `ambiguous non Python and escaping source paths are rejected`() {
        for (paths in listOf(emptyList(), listOf("one.py", "two.py"))) {
            val invalid = document().copy(implementation = document().implementation.copy(sourceFiles = paths))
            assertFailsWith<IllegalStateException> {
                ensureXrpExtensionScaffold(root.toString(), invalid)
            }
        }
        for (path in listOf("arm.kt", "../outside.py")) {
            assertFailsWith<IllegalArgumentException> {
                ensureXrpExtensionScaffold(root.toString(), document(path))
            }
        }
    }
}
