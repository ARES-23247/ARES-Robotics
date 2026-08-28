package com.ares.analytics.service.integration

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.SessionSummary
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.NotebookReviewState
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineeringNotebookDraftServiceTest {
    @Test
    fun `deterministic session draft works without AI and remains review gated`() = runTest {
        withDatabase { database ->
            val service = EngineeringNotebookDraftService(
                database,
                aiProvider = null,
                clock = Clock.fixed(Instant.ofEpochMilli(2_000L), ZoneOffset.UTC),
            )
            val entry = service.createSessionDraft(summary(), emptyList(), "student-1", useAi = false)

            assertTrue(entry.markdownBody.contains("Minimum battery voltage: 10.8 V"))
            assertTrue(entry.markdownBody.contains("Add human interpretation"))
            assertEquals(NotebookReviewState.DRAFT, entry.reviewState)
            assertNull(entry.aiProvenance)
            assertEquals(entry, database.integrations.getLatestNotebookRevision(entry.entryId))
        }
}
    @Test
    fun `invalid AI improvement claim falls back to deterministic draft`() = runTest {
        withDatabase { database ->
            val provider = object : StructuredDraftProvider {
                override val providerId = "test-ai"
                override val model = "test-model"
                override suspend fun rewrite(request: StructuredDraftRequest) = StructuredDraftResponse(
                    markdown = "# Invented improvement",
                    claims = listOf(
                        DraftClaim(
                            "Performance improved",
                            listOf("commit:1"),
                            DraftClaimClassification.VERIFIED_IMPROVEMENT,
                        )
                    ),
                )
            }
            val service = EngineeringNotebookDraftService(
                database,
                provider,
                Clock.fixed(Instant.ofEpochMilli(2_000L), ZoneOffset.UTC),
            )
            val entry = service.createSoftwareDigest(
                SoftwareChangeEvidence(
                    workspace = IntegrationWorkspaceIdentity("23247", "2026", "marvin"),
                    commitRange = "abc..def",
                    commits = listOf(DraftEvidenceFact("commit:1", "commit", "Changed current limiter")),
                ),
                authorId = "student-1",
                useAi = true,
            )

            assertFalse(entry.markdownBody.contains("Invented improvement"))
            assertTrue(entry.markdownBody.contains("improvement has not yet been verified"))
            assertNull(entry.aiProvenance)
        }
    }

    @Test
    fun `valid structured AI rewrite retains provenance and cited evidence`() = runTest {
        withDatabase { database ->
            val provider = object : StructuredDraftProvider {
                override val providerId = "test-ai"
                override val model = "test-model"
                override suspend fun rewrite(request: StructuredDraftRequest) = StructuredDraftResponse(
                    markdown = "# Reviewed draft\n\nThe regression test passed. `[test:1]`",
                    claims = listOf(
                        DraftClaim(
                            "The regression test passed",
                            listOf("test:1"),
                            DraftClaimClassification.OBSERVATION,
                        )
                    ),
                )
            }
            val service = EngineeringNotebookDraftService(
                database,
                provider,
                Clock.fixed(Instant.ofEpochMilli(2_000L), ZoneOffset.UTC),
            )
            val entry = service.createSoftwareDigest(
                SoftwareChangeEvidence(
                    workspace = IntegrationWorkspaceIdentity("23247", "2026", "marvin"),
                    commitRange = "abc..def",
                    commits = listOf(DraftEvidenceFact("commit:1", "commit", "Changed current limiter")),
                    tests = listOf(DraftEvidenceFact("test:1", "test", "CurrentLimiterTest passed")),
                ),
                authorId = "student-1",
                useAi = true,
            )

            assertTrue(entry.markdownBody.contains("Reviewed draft"))
            assertEquals("test-ai", entry.aiProvenance?.provider)
            assertEquals("test-model", entry.aiProvenance?.model)
            assertEquals(NotebookReviewState.DRAFT, entry.reviewState)
        }
    }

    @Test
    fun `evidence redaction removes credentials before drafting`() = runTest {
        withDatabase { database ->
            var observedEvidence = ""
            val provider = object : StructuredDraftProvider {
                override val providerId = "test-ai"
                override val model = "test-model"
                override suspend fun rewrite(request: StructuredDraftRequest): StructuredDraftResponse {
                    observedEvidence = request.evidence.single().statement
                    return StructuredDraftResponse(
                        markdown = "# Safe draft",
                        claims = listOf(
                            DraftClaim("A note was recorded", listOf("note:1"), DraftClaimClassification.OBSERVATION)
                        ),
                    )
                }
            }
            val service = EngineeringNotebookDraftService(database, provider)
            service.createSoftwareDigest(
                SoftwareChangeEvidence(
                    workspace = IntegrationWorkspaceIdentity("23247", "2026", "marvin"),
                    commitRange = "abc..def",
                    commits = listOf(DraftEvidenceFact("note:1", "commit", "api_key=super-secret-value")),
                ),
                authorId = null,
                useAi = true,
            )

            assertTrue(observedEvidence.contains("[REDACTED]"))
            assertFalse(observedEvidence.contains("super-secret-value"))
        }
    }

    private fun summary() = SessionSummary(
        sessionId = "session-1",
        teamId = "23247",
        seasonId = "2026",
        robotId = "marvin",
        createdAt = 1_000L,
        minBatteryVoltage = 10.8,
        maxEkfDrift = 0.12,
        p95LoopTimeMs = 24.0,
    )

    private suspend fun withDatabase(block: suspend (DatabaseService) -> Unit) {
        val tempDirectory = Files.createTempDirectory("ares-draft-service").toFile()
        val database = DatabaseService(tempDirectory.resolve("telemetry.duckdb").absolutePath)
        try {
            block(database)
        } finally {
            database.close()
            tempDirectory.deleteRecursively()
        }
    }
}
