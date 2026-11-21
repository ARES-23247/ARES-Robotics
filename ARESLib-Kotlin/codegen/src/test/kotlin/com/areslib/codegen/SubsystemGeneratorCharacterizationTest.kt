package com.areslib.codegen

import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.subsystem.supportsPlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * Locks the complete generated artifact set while the subsystem generator is decomposed.
 *
 * Behavioral compilation tests remain authoritative for runtime semantics. This digest adds a
 * deliberately sensitive tripwire for ownership headers, paths, ordering, platform adapters,
 * simulator adapters, generated verification, and registry plumbing.
 */
class SubsystemGeneratorCharacterizationTest {
    // Lifecycle bridges now enforce timestamp ordering, close every owner after failures, and
    // reject post-close work. GeneratedSubsystemLifecycleAuditTest executes those behaviors.
    // Homing dwell tests now refresh feedback on the mock clock before every controller step.
    // Homing adapters now restore FTC RunMode, track FTC home offsets, drive followers, roll back
    // partial FRC constructor ownership, and invert solenoids/mocks consistently.
    // Generated FTC, FRC, and Mock IO adapters now cache prefix-qualified telemetry topic names per
    // prefix so steady-state 50 Hz telemetry logging allocates zero topic strings.
    // Generated controllers now require sustained healthy dwell before resetting automatic recovery
    // retry budgets and invoke io.safe() on non-finite PID outputs; generated IO adapters guard
    // actuator commands and recovery paths against post-close execution.
    @Test
    fun `all supported FTC templates retain their deterministic artifact manifest`() {
        assertEquals("f48a5216e349e658cf1551a38eba4dc2173da14364755dbd2d484fd228ff6e75", manifestDigest(SubsystemPlatform.FTC))
    }

    @Test
    fun `all supported FRC templates retain their deterministic artifact manifest`() {
        assertEquals("6d5fec859b7f8576cb4cda8fc0821f7eca5a58b15282060ad916ae242243f30a", manifestDigest(SubsystemPlatform.FRC))
    }

    private fun manifestDigest(platform: SubsystemPlatform): String {
        val target = SubsystemKotlinCodegenTarget(
            platform = platform,
            basePackage = "org.example.characterization.${platform.name.lowercase()}",
        )
        val documents = SubsystemTemplate.entries
            .filter { it.supportsPlatform(platform) }
            .map { template ->
                val suffix = template.name.lowercase().replace('_', '-')
                val typeName = template.name.lowercase().split('_').joinToString("") { token ->
                    token.replaceFirstChar(Char::uppercaseChar)
                }
                SubsystemTemplates.create(template, "characterization-$suffix", typeName, platform)
            }
        val manifest = buildString {
            documents.sortedBy { it.documentId }.forEach { document ->
                append("DOCUMENT\u0000")
                append(document.documentId)
                append('\n')
                SubsystemKotlinGenerator.generate(document, target).forEach { artifact ->
                    append(artifact.sourceSet.name)
                    append('\u0000')
                    append(artifact.artifact.name)
                    append('\u0000')
                    append(artifact.ownership.name)
                    append('\u0000')
                    append(artifact.relativePath)
                    append('\u0000')
                    append(artifact.content)
                    append('\u0000')
                }
            }
            val registry = SubsystemKotlinGenerator.generateRegistry(documents, target)
            append("REGISTRY\u0000")
            append(registry.content)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(manifest.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
