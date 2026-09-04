package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.service.drivebase.canonicalTemplate
import com.ares.analytics.viewmodel.controls.describeControlsChanges
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderViewModel
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlSourceDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetDocument
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControllerAssignment
import com.areslib.drivetrain.DrivetrainDocumentCodec
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesignAssistantsTest {
    @Test
    fun `drivebase proposal preserves repository identity and evidence`() {
        val current = canonicalTemplate("team", DrivebaseKind.FTC_MECANUM, League.FTC)
        val proposed = current.copy(
            uid = "ai-replaced-uid",
            drivebaseId = "ai-drive",
            displayName = "Student mecanum",
            canonicalProfileUid = "ai-profile",
            description = "Four-wheel practice robot",
            parameters = current.parameters.map { parameter ->
                if (parameter.componentUid == current.uid) parameter.copy(componentUid = "ai-replaced-uid")
                else parameter
            },
        )
        val response = envelope("Updated the form", DrivetrainDocumentCodec.encode(proposed))

        val result = parseDrivebaseDesignProposalResponse(current, response)

        assertEquals(current.uid, result.candidate.uid)
        assertEquals(current.drivebaseId, result.candidate.drivebaseId)
        assertEquals(current.canonicalProfileUid, result.candidate.canonicalProfileUid)
        assertEquals(current.parameters, result.candidate.parameters)
        assertEquals("Student mecanum", result.candidate.displayName)
    }

    @Test
    fun `controls proposal preserves document and controller identity`() {
        val current = ControlSchemeDocument(
            documentId = "student-controls",
            name = "Student controls",
            controllers = listOf(ControllerAssignment("driver", "Driver", "xbox", devicePort = 0)),
            bindings = emptyList(),
        )
        val proposed = current.copy(documentId = "changed", name = "Practice controls")
        val context = ControlsDesignContext(setOf("intake.run"), emptySet(), mapOf("xbox" to setOf("rightBumper")))

        val result = parseControlsDesignProposalResponse(current, context, envelope("Rename", ControlSchemeCodec.encode(proposed)))

        assertEquals(current.documentId, result.candidate.documentId)
        assertEquals(current.controllers, result.candidate.controllers)
        assertEquals("Practice controls", result.candidate.name)
        assertTrue(describeControlsChanges(current, result.candidate).single().startsWith("Rename scheme"))
    }

    @Test
    fun `controls proposal rejects action keys outside the project catalog`() {
        val current = ControlSchemeDocument(
            documentId = "student-controls",
            name = "Student controls",
            controllers = listOf(ControllerAssignment("driver", "Driver", "xbox", devicePort = 0)),
            bindings = emptyList(),
        )
        val binding = ControlBindingDocument(
            bindingId = "unknown-action",
            displayName = "Unknown action",
            source = ControlSourceDocument(ControlSourceKind.BUTTON, "driver", listOf("rightBumper")),
            event = ControlEvent.PRESS,
            target = ControlTargetDocument(ControlTargetKind.ACTION, "invented.action"),
        )
        val proposed = current.copy(bindings = listOf(binding))
        val context = ControlsDesignContext(setOf("intake.run"), emptySet(), mapOf("xbox" to setOf("rightBumper")))

        assertFailsWith<IllegalArgumentException> {
            parseControlsDesignProposalResponse(current, context, envelope("Bad action", ControlSchemeCodec.encode(proposed)))
        }
    }

    @Test
    fun `drivebase assistant applies only after review`() = runBlocking {
        val root = Files.createTempDirectory("ares-drivebase-ai").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val assistant = DrivebaseDesignAssistant { current, _ ->
            DrivebaseDesignProposal("Rename", listOf("Use a student-friendly name"), current.copy(displayName = "Practice drive"))
        }
        try {
            val viewModel = DrivebaseBuilderViewModel(root.path, "team", League.FTC, scope, designAssistant = assistant)
            withTimeout(5_000) { viewModel.state.first { !it.loading } }

            viewModel.requestAiProposal("Give this a clearer name")
            val reviewed = withTimeout(5_000) { viewModel.state.first { it.aiProposal != null } }
            assertEquals("Practice drive", reviewed.aiProposal?.candidate?.displayName)
            assertTrue(!reviewed.dirty)

            viewModel.applyAiProposal()
            assertEquals("Practice drive", viewModel.state.value.draft.displayName)
            assertTrue(viewModel.state.value.dirty)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `malformed assistant envelopes fail closed`() {
        val current = canonicalTemplate("team", DrivebaseKind.FTC_MECANUM, League.FTC)
        assertFailsWith<IllegalArgumentException> {
            parseDrivebaseDesignProposalResponse(current, """{"summary":"missing document"}""")
        }
    }

    @Test
    fun `string binding target is repaired on the second attempt`() = runBlocking {
        val current = ControlSchemeDocument(
            documentId = "student-controls",
            name = "Student controls",
            controllers = listOf(ControllerAssignment("driver", "Driver", "xbox", devicePort = 0)),
            bindings = emptyList(),
        )
        val context = ControlsDesignContext(setOf("intake.run"), emptySet(), mapOf("xbox" to setOf("rightBumper")))
        // First attempt mirrors the field failure: a plain string where the target object belongs.
        val malformed = """{"summary":"Draft","explanations":[],"proposedDocument":""" +
            """{"schemaVersion":2,"documentId":"student-controls","revision":1,"name":"Student controls",""" +
            """"controllers":[{"slot":"driver","displayName":"Driver","profileId":"xbox","devicePort":0}],""" +
            """"bindings":[{"bindingId":"b1","displayName":"Run intake",""" +
            """"source":{"kind":"BUTTON","controllerSlot":"driver","controlIds":["rightBumper"]},""" +
            """"event":"PRESS","target":"intake.run"}]}}"""
        val fixed = envelope(
            "Repaired",
            ControlSchemeCodec.encode(
                current.copy(
                    bindings = listOf(
                        ControlBindingDocument(
                            bindingId = "b1",
                            displayName = "Run intake",
                            source = ControlSourceDocument(ControlSourceKind.BUTTON, "driver", listOf("rightBumper")),
                            event = ControlEvent.PRESS,
                            target = ControlTargetDocument(ControlTargetKind.ACTION, "intake.run"),
                        ),
                    ),
                ),
            ),
        )
        val requests = mutableListOf<String>()

        val result = requestDesignProposalWithRepair("base prompt", { prompt ->
            requests.add(prompt)
            if (requests.size == 1) malformed else fixed
        }) { parseControlsDesignProposalResponse(current, context, it) }

        assertEquals(1, result.candidate.bindings.size)
        assertEquals(ControlTargetKind.ACTION, result.candidate.bindings.single().target.kind)
        assertEquals("intake.run", result.candidate.bindings.single().target.key)
        assertEquals(2, requests.size)
        assertTrue(requests[1].contains("rejected"))
        assertTrue(requests[1].contains("Expected BEGIN_OBJECT but was STRING"))
    }

    @Test
    fun `unrepairable proposals surface the second rejection`() = runBlocking {
        val current = ControlSchemeDocument(
            documentId = "student-controls",
            name = "Student controls",
            controllers = listOf(ControllerAssignment("driver", "Driver", "xbox", devicePort = 0)),
            bindings = emptyList(),
        )
        val context = ControlsDesignContext(setOf("intake.run"), emptySet(), mapOf("xbox" to setOf("rightBumper")))
        val malformed = """{"summary":"broken","proposedDocument":{"schemaVersion":2,"nope":true}}"""

        val failure = kotlin.runCatching {
            requestDesignProposalWithRepair("base prompt", { malformed }) {
                parseControlsDesignProposalResponse(current, context, it)
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure.message.orEmpty().contains("one repair attempt"))
    }

    private fun envelope(summary: String, documentJson: String): String =
        """{"summary":"$summary","explanations":["Review every change"],"proposedDocument":$documentJson}"""
}
