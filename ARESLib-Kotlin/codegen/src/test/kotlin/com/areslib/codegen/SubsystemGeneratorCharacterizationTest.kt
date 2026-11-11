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
    @Test
    fun `all supported FTC templates retain their deterministic artifact manifest`() {
        assertEquals("d3d766803172abd666924f9d147012d20c78654e24c7612848029a0682dc4031", manifestDigest(SubsystemPlatform.FTC))
    }

    @Test
    fun `all supported FRC templates retain their deterministic artifact manifest`() {
        assertEquals("cf75e70b0a8e906c779d48f13e84c8673aee6b0ce18a73e9a2eebca06f628327", manifestDigest(SubsystemPlatform.FRC))
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
