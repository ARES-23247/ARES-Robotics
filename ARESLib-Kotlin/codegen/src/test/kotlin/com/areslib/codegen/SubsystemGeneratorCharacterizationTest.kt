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
    // Template ownership now preserves intake interaction metadata. Only the generated registry
    // document fingerprint changes; SubsystemTemplateOwnershipAuditTest covers the retained values.
    @Test
    fun `all supported FTC templates retain their deterministic artifact manifest`() {
        assertEquals("47c4bdd2fcc499238d333742d201b6812e2810b31ce526088cc6fbaf4d69a5b1", manifestDigest(SubsystemPlatform.FTC))
    }

    @Test
    fun `all supported FRC templates retain their deterministic artifact manifest`() {
        assertEquals("ba0b2a74eb6a3fa2c046fbe19b23b004560cc279222893c943b8f4011f8ed996", manifestDigest(SubsystemPlatform.FRC))
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
