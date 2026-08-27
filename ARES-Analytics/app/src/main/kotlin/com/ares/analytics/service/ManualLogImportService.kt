package com.ares.analytics.service

import com.ares.analytics.shared.Session
import com.ares.analytics.shared.WorkspaceConfig
import com.ares.analytics.service.log.HootDecoderService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ManualLogImportOutcome(
    val session: Session,
    val reports: List<ImportReport>,
    val wasAlreadyImported: Boolean,
) {
    val acceptedRecords: Long get() = reports.maxOfOrNull(ImportReport::acceptedRecords) ?: 0L
    val detectedTopicCount: Int get() = reports.flatMap(ImportReport::detectedTopics).distinct().size
}

/**
 * Student-facing import boundary used by the Log Import Center.
 *
 * Selected evidence is copied into the active workspace before decoding; the original files are
 * never moved or changed. The parser's database-backed source hashes make retrying the same
 * selection idempotent even after an application restart. Failed archived copies move to the
 * workspace quarantine with a machine-readable report, while cancellation removes only copies
 * created by the cancelled attempt.
 */
class ManualLogImportService(
    private val databaseService: DatabaseService,
    private val logParserService: LogParserService,
    private val hootDecoderService: HootDecoderService,
    private val autoImportService: AutoImportService,
) {
    private val importMutex = Mutex()

    suspend fun importFiles(
        selectedFiles: List<File>,
        workspace: WorkspaceConfig,
    ): ManualLogImportOutcome = importMutex.withLock {
        withContext(Dispatchers.IO) {
            require(workspace.projectPath.isNotBlank()) { "Choose a robot workspace before importing logs" }
            val sources = canonicalLogImportFiles(selectedFiles)
            require(sources.isNotEmpty()) { "Choose at least one completed robot or simulator log" }
            require(sources.none { it.name.endsWith(".hoot", ignoreCase = true) } || sources.size == 1) {
                "Import CTRE Hoot logs one at a time so their conversion evidence stays unambiguous"
            }

            val archiveDirectory = File(workspace.projectPath, "logs/imported").canonicalFile
            archiveDirectory.mkdirs()
            require(archiveDirectory.isDirectory) { "ARES could not create this workspace's log archive" }

            val archived = mutableListOf<ArchivedSelection>()
            try {
                sources.forEach { source ->
                    val sha256 = logParserService.sha256(source)
                    val target = autoImportService.safeArchiveFile(archiveDirectory, sha256, source.name)
                    val created = copyVerifiedIfMissing(source, target, sha256)
                    archived += ArchivedSelection(source, target, sha256, created)
                    copyDriverStationCompanion(source, target, archived)
                }

                val sourceHashes = archived.mapTo(linkedSetOf(), ArchivedSelection::sha256)
                val existing = databaseService.findCompletedSessionBySourceHashes(
                    workspace.teamId,
                    workspace.seasonId,
                    workspace.robotId,
                    sourceHashes,
                )

                val session = if (archived.singleOrNull()?.archived?.name?.endsWith(".hoot", ignoreCase = true) == true) {
                    val hoot = archived.single()
                    if (existing != null) {
                        existing
                    } else {
                        val imported = hootDecoderService.importHootLog(
                            hoot.archived,
                            workspace.teamId,
                            workspace.seasonId,
                            workspace.robotId,
                            sourceName = hoot.source.name,
                        )
                        imported.session
                    }
                } else {
                    logParserService.parseLogFiles(
                        files = archived
                            .filterNot { isDriverStationEventCompanionName(it.archived.name) }
                            .map(ArchivedSelection::archived),
                        teamId = workspace.teamId,
                        seasonId = workspace.seasonId,
                        robotId = workspace.robotId,
                        tags = listOf("manual-import", "archived-raw"),
                    )
                }

                val reports = if (existing != null) {
                    databaseService.getSessionImportReports(session.sessionId)
                } else {
                    archived.map { selection ->
                        logParserService.buildImportReport(
                            selection.archived,
                            session.sessionId,
                            selection.archived.length(),
                            selection.sha256,
                            decoderOverride = selection.archived
                                .takeIf { it.name.endsWith(".hoot", ignoreCase = true) }
                                ?.let { "hoot" },
                        ).copy(sourceName = selection.source.name)
                    }.also { generated ->
                        logParserService.persistExternalImportReports(session.sessionId, generated)
                    }
                }
                reports.forEachIndexed { index, report ->
                    archived.getOrNull(index)?.let { selection ->
                        autoImportService.writeImportReport(selection.archived, report)
                    }
                }
                ManualLogImportOutcome(session, reports, wasAlreadyImported = existing != null)
            } catch (cancelled: CancellationException) {
                archived.filter(ArchivedSelection::created).forEach { selection ->
                    selection.archived.delete()
                    File(selection.archived.parentFile, selection.archived.name + AutoImportService.IMPORT_REPORT_SUFFIX).delete()
                }
                throw cancelled
            } catch (failure: Throwable) {
                archived.filter(ArchivedSelection::created).forEach { selection ->
                    runCatching {
                        autoImportService.quarantineFailedImport(
                            workspace,
                            selection.archived,
                            selection.sha256,
                            failure,
                            selection.source.name,
                        )
                    }.onFailure(failure::addSuppressed)
                }
                throw failure
            }
        }
    }

    private fun copyVerifiedIfMissing(source: File, target: File, expectedSha256: String): Boolean {
        if (target.isFile && logParserService.sha256(target) == expectedSha256) return false
        val partial = File(target.parentFile, ".${target.name}.partial")
        partial.delete()
        try {
            Files.copy(source.toPath(), partial.toPath(), StandardCopyOption.REPLACE_EXISTING)
            require(partial.length() == source.length() && logParserService.sha256(partial) == expectedSha256) {
                "The archived copy of ${source.name} did not match the selected file"
            }
            moveAtomically(partial, target)
            return true
        } finally {
            partial.delete()
        }
    }

    private fun copyDriverStationCompanion(
        source: File,
        archivedPrimary: File,
        archived: MutableList<ArchivedSelection>,
    ) {
        if (!source.name.endsWith(".dslog", ignoreCase = true)) return
        val companion = File(source.parentFile, source.nameWithoutExtension + ".dsevents")
        if (!companion.isFile) return
        val sha256 = logParserService.sha256(companion)
        val target = File(archivedPrimary.parentFile, archivedPrimary.nameWithoutExtension + ".dsevents")
        val created = copyVerifiedIfMissing(companion, target, sha256)
        archived += ArchivedSelection(companion, target, sha256, created)
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class ArchivedSelection(
        val source: File,
        val archived: File,
        val sha256: String,
        val created: Boolean,
    )
}
