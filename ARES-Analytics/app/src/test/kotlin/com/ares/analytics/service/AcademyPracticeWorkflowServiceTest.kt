package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AcademyPracticeWorkflowServiceTest {
    @Test
    fun `practice workflow imports each synthetic run once and reuses it on retry`() = runTest {
        val project = Files.createTempDirectory("academy-workflow").toFile().apply {
            resolve(".ares").mkdirs()
        }
        val sessions = mutableListOf<Session>()
        var importCalls = 0
        val workflow = AcademyPracticeWorkflowService(
            packService = AcademyPracticePackService(),
            listSessions = { sessions.toList() },
            importFile = { file, identity, tags ->
                importCalls++
                Session(
                    sessionId = "session-$importCalls",
                    teamId = identity.teamId,
                    seasonId = identity.seasonId,
                    robotId = identity.robotId,
                    createdAt = file.lastModified(),
                    tags = tags,
                ).also(sessions::add)
            },
        )
        val identity = AcademyPracticeIdentity("23247", "2026", "academy-bot")

        val first = workflow.installAndImport(project, identity)
        val second = workflow.installAndImport(project, identity)

        assertEquals(2, first.importedCount)
        assertEquals(0, first.reusedCount)
        assertEquals(0, second.importedCount)
        assertEquals(2, second.reusedCount)
        assertEquals(first.sessionIds, second.sessionIds)
        assertEquals(2, importCalls)
        assertTrue(sessions.all { AcademyPracticeWorkflowService.ACADEMY_SYNTHETIC_TAG in it.tags })
        assertTrue(sessions.all { session -> session.tags.any { it.startsWith(AcademyPracticeWorkflowService.ACADEMY_SOURCE_TAG_PREFIX) } })
    }

    @Test
    fun `practice workflow isolates imports by robot identity`() = runTest {
        val project = Files.createTempDirectory("academy-workflow-identity").toFile().apply {
            resolve(".ares").mkdirs()
        }
        val sessions = mutableListOf<Session>()
        var sequence = 0
        val workflow = AcademyPracticeWorkflowService(
            packService = AcademyPracticePackService(),
            listSessions = { sessions.toList() },
            importFile = { file, identity, tags ->
                Session(
                    sessionId = "session-${++sequence}",
                    teamId = identity.teamId,
                    seasonId = identity.seasonId,
                    robotId = identity.robotId,
                    createdAt = file.lastModified(),
                    tags = tags,
                ).also(sessions::add)
            },
        )

        workflow.installAndImport(project, AcademyPracticeIdentity("23247", "2026", "robot-a"))
        val otherRobot = workflow.installAndImport(project, AcademyPracticeIdentity("23247", "2026", "robot-b"))

        assertEquals(2, otherRobot.importedCount)
        assertEquals(4, sessions.size)
        assertEquals(setOf("robot-a", "robot-b"), sessions.mapTo(mutableSetOf(), Session::robotId))
    }

    @Test
    fun `practice workflow rejects incomplete workspace identity before import`() = runTest {
        val project = Files.createTempDirectory("academy-workflow-invalid").toFile().apply {
            resolve(".ares").mkdirs()
        }
        val workflow = AcademyPracticeWorkflowService(
            packService = AcademyPracticePackService(),
            listSessions = { emptyList() },
            importFile = { _, _, _ -> error("must not import") },
        )

        assertFailsWith<IllegalArgumentException> {
            workflow.installAndImport(project, AcademyPracticeIdentity("", "2026", "robot"))
        }
    }
}
