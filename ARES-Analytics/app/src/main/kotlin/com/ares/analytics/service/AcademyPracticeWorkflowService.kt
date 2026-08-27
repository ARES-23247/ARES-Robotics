package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class AcademyPracticeIdentity(
    val teamId: String,
    val seasonId: String,
    val robotId: String,
)

data class AcademyPracticeImportResult(
    val pack: AcademyPracticePackResult,
    val sessionIds: List<String>,
    val importedCount: Int,
    val reusedCount: Int,
)

/**
 * Installs and imports the bundled synthetic teaching runs as one idempotent classroom workflow.
 * Stable source tags prevent repeated clicks from duplicating the same practice session.
 */
class AcademyPracticeWorkflowService internal constructor(
    private val packService: AcademyPracticePackService,
    private val listSessions: suspend () -> List<Session>,
    private val importFile: suspend (File, AcademyPracticeIdentity, List<String>) -> Session,
) {
    private val workflowMutex = Mutex()

    constructor(
        packService: AcademyPracticePackService,
        databaseService: DatabaseService,
        logParserService: LogParserService,
    ) : this(
        packService = packService,
        listSessions = databaseService::getSessions,
        importFile = { file, identity, tags ->
            logParserService.parseLogFile(
                file = file,
                teamId = identity.teamId,
                seasonId = identity.seasonId,
                robotId = identity.robotId,
                tags = tags,
            )
        },
    )

    suspend fun installAndImport(
        projectRoot: File,
        identity: AcademyPracticeIdentity,
    ): AcademyPracticeImportResult = withContext(Dispatchers.IO) {
        validateIdentity(identity)
        workflowMutex.withLock {
            val pack = packService.install(projectRoot)
            val csvFiles = pack.files.filter { it.extension.equals("csv", ignoreCase = true) }.sortedBy(File::getName)
            val existing = listSessions()
            var imported = 0
            var reused = 0
            val sessionIds = csvFiles.map { file ->
                val sourceTag = sourceTag(file)
                val prior = existing.firstOrNull { session ->
                    session.teamId == identity.teamId &&
                        session.seasonId == identity.seasonId &&
                        session.robotId == identity.robotId &&
                        sourceTag in session.tags
                }
                if (prior != null) {
                    reused++
                    prior.sessionId
                } else {
                    imported++
                    importFile(file, identity, listOf(ACADEMY_SYNTHETIC_TAG, sourceTag)).sessionId
                }
            }
            AcademyPracticeImportResult(pack, sessionIds, imported, reused)
        }
    }

    private fun validateIdentity(identity: AcademyPracticeIdentity) {
        require(identity.teamId.isNotBlank()) { "The selected workspace has no team ID" }
        require(identity.seasonId.isNotBlank()) { "The selected workspace has no season ID" }
        require(identity.robotId.isNotBlank()) { "The selected workspace has no robot ID" }
    }

    private fun sourceTag(file: File): String = "$ACADEMY_SOURCE_TAG_PREFIX${file.name}"

    companion object {
        const val ACADEMY_SYNTHETIC_TAG = "academy-synthetic-data"
        const val ACADEMY_SOURCE_TAG_PREFIX = "academy-practice-source:"
    }
}
