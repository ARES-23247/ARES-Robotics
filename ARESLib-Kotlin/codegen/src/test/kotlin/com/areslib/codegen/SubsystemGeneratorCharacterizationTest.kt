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
    // The linkage mock now neutralizes, invalidates and latches before propagating a
    // failed plant step. LinkageMockFailureAuditTest executes that generated boundary.
    @Test
    fun `all supported FTC templates retain their deterministic artifact manifest`() {
        assertEquals("52a39c6c226100051f4074c48dc3611a456ec4ee3309fc288f09bb1c94b581d2", manifestDigest(SubsystemPlatform.FTC))
    }

    @Test
    fun `all supported FRC templates retain their deterministic artifact manifest`() {
        assertEquals("b89e268d816521c1b799279c55baaa6fd17ad84f1f2b5e888a2a6e898880d0b1", manifestDigest(SubsystemPlatform.FRC))
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
