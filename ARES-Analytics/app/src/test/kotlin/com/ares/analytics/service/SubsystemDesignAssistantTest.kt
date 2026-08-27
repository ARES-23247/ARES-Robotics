package com.ares.analytics.service

import com.areslib.subsystem.SubsystemHardwareScaffolding
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemSourceOwnership
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.google.gson.GsonBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SubsystemDesignAssistantTest {
    @Test
    fun `untrusted proposal cannot replace identity ownership platform or existing editor uids`() {
        val current = SubsystemTemplates.create(
            SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
            "arm",
            "Arm",
            SubsystemPlatform.FTC,
        )
        val extra = SubsystemHardwareScaffolding.create(
            SubsystemHardwareKind.MOTOR,
            "follower",
            "Follower",
            SubsystemPlatform.FTC,
        )
        val proposed = current.copy(
            documentId = "escaped-project",
            uid = "replaced-uid",
            platform = SubsystemPlatform.FRC,
            implementation = current.implementation.copy(
                kind = SubsystemImplementationKind.HAND_AUTHORED,
                ownership = SubsystemSourceOwnership.USER_OWNED,
            ),
            hardware = current.hardware.map { it.copy(uid = "rewritten-existing") } + extra.hardware,
            stateFields = current.stateFields + extra.stateFields,
            controlLoops = current.controlLoops + extra.controlLoops,
        )

        val sanitized = sanitizeSubsystemDesignCandidate(current, proposed)

        assertEquals(current.documentId, sanitized.documentId)
        assertEquals(current.uid, sanitized.uid)
        assertEquals(current.platform, sanitized.platform)
        assertEquals(current.implementation, sanitized.implementation)
        assertEquals(current.hardware.first().uid, sanitized.hardware.first().uid)
        assertNotEquals(extra.hardware.uid, sanitized.hardware.last().uid)
        assertTrue(sanitized.hardware.last().uid.startsWith("ai-hardware-"))
    }

    @Test
    fun `structured response parser accepts fenced JSON and protects form ownership`() {
        val current = SubsystemTemplates.create(
            SubsystemTemplate.SIMPLE_ACTUATOR,
            "intake",
            "Intake",
            SubsystemPlatform.FTC,
        )
        val untrusted = current.copy(
            displayName = "AI intake",
            documentId = "different-project",
            implementation = current.implementation.copy(
                kind = SubsystemImplementationKind.HAND_AUTHORED,
                ownership = SubsystemSourceOwnership.USER_OWNED,
            ),
        )
        val proposedJson = GsonBuilder().create().toJson(untrusted)
        val response = """
            ```json
            {"summary":"Safer intake","explanations":["Kept a neutral output"],"proposedDocument":$proposedJson}
            ```
        """.trimIndent()

        val parsed = parseSubsystemDesignProposalResponse(current, response)

        assertEquals("Safer intake", parsed.summary)
        assertEquals(listOf("Kept a neutral output"), parsed.explanations)
        assertEquals("AI intake", parsed.candidate.displayName)
        assertEquals(current.documentId, parsed.candidate.documentId)
        assertEquals(current.implementation, parsed.candidate.implementation)
    }
}
