package com.ares.analytics.service

import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import com.ares.analytics.shared.models.allowsAutomaticExternalUpdates
import com.ares.analytics.service.db.*
import com.ares.analytics.service.integration.IntegrationRepository
import com.ares.analytics.service.integration.IntegrationStore
import com.ares.analytics.service.integration.IntegrationEventRecorder
import com.ares.analytics.service.integration.IntegrationRoutingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Statement
import java.sql.Connection
import java.sql.DriverManager

internal data class DuckDbResourceSettings(
    val memoryLimit: String,
    val workerThreads: Int,
)

/** Resolves validated DuckDB caps without opening a database, allowing deterministic tests. */
internal fun resolveDuckDbResourceSettings(
    requestedMemoryLimit: String?,
    requestedWorkerThreads: Int?,
    maxJvmMemoryBytes: Long,
    availableProcessors: Int,
): DuckDbResourceSettings {
    val memoryLimit = requestedMemoryLimit?.trim()?.uppercase()?.also { configured ->
        require(configured.matches(Regex("[1-9][0-9]*(KB|MB|GB)"))) {
            "DuckDB memory limit must use a positive KB, MB, or GB value"
        }
    } ?: run {
        val adaptiveMegabytes = (maxJvmMemoryBytes / (4L * 1024L * 1024L)).coerceIn(256L, 768L)
        "${adaptiveMegabytes}MB"
    }
    val workerThreads = requestedWorkerThreads
        ?: (availableProcessors.coerceAtLeast(1) / 2).coerceIn(1, 4)
    require(workerThreads in 1..64) { "DuckDB worker thread count must be in 1..64" }
    return DuckDbResourceSettings(memoryLimit, workerThreads)
}

/**
 * High-level embedded relational database service wrapping the DuckDB C++ engine over JDBC.
 *
 * Manages persistent on-disk database files (`telemetry.duckdb`) and fast ephemeral in-memory databases (`jdbc:duckdb:`).
 * Orchestrates schema migrations via [SchemaMigrationManager], session metadata through
 * [SessionMetadataRepository], telemetry through [MatchLogRepository], and Parquet import/export
 * through [DatabaseBackupExporter].
 *
 * ### Database Engine Specifications:
 * - Driver: `org.duckdb.DuckDBDriver`
 * - File Path: [dbPath] (defaults to `~/.ares-analytics/telemetry.duckdb`)
 * - Native Extensions Loaded: `parquet` (for high-speed binary trace ingestion)
 *
 * ### Thread Safety & Performance Guarantees:
 * Multi-thread safe. All write and query transactions are synchronized through an asynchronous coroutine [dbMutex] lock.
 * Domain repositories share one [DatabaseTransactionCoordinator].
 *
 * @param dbPath Absolute filesystem path to the DuckDB database file.
 *
 * @see SchemaMigrationManager
 * @see MatchLogRepository
 * @see DatabaseBackupExporter
 */
class DatabaseService(
    val dbPath: String = AppDataPaths.file("telemetry.duckdb").path,
    duckDbMemoryLimit: String? = null,
    duckDbWorkerThreads: Int? = null,
) : TelemetryAnalyticsRepository {

    internal val duckDbResourceSettings = resolveDuckDbResourceSettings(
        requestedMemoryLimit = duckDbMemoryLimit
            ?: System.getProperty(DUCKDB_MEMORY_LIMIT_PROPERTY)
            ?: System.getenv(DUCKDB_MEMORY_LIMIT_ENV),
        requestedWorkerThreads = duckDbWorkerThreads ?: configuredDuckDbWorkerThreads(),
        maxJvmMemoryBytes = Runtime.getRuntime().maxMemory(),
        availableProcessors = Runtime.getRuntime().availableProcessors(),
    )

    private val conn: Connection
    private val readConn: Connection
    private val ephemeralConn: Connection
    private val ephemeralReadConn: Connection
    private val dbMutex = Mutex()
    private val readMutex = Mutex()
    val metrics = DatabaseMetrics()

    private val schemaManager: SchemaMigrationManager
    private val transactionCoordinator: DatabaseTransactionCoordinator
    private val sessionMetadataRepo: SessionMetadataRepository
    private val matchLogRepo: MatchLogRepository
    private val backupExporter: DatabaseBackupExporter
    private val integrationRepository: IntegrationRepository

    val integrations: IntegrationStore
        get() = integrationRepository
    val integrationRouting = IntegrationRoutingPolicy()
    val integrationEvents: IntegrationEventRecorder

    private val checkpointScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var checkpointJob: Job

    init {
        Class.forName("org.duckdb.DuckDBDriver")
        val dbFile = File(dbPath)
        val defaultDbFile = AppDataPaths.file("telemetry.duckdb")
            .canonicalFile
        // Legacy import belongs only to the process' real application database. Unit tests and
        // alternate workspaces must never ingest a user's home telemetry.db by coincidence.
        val legacyDbPath = if (dbFile.canonicalFile == defaultDbFile) {
            File(defaultDbFile.parentFile, "telemetry.db").absolutePath
        } else {
            null
        }
        dbFile.parentFile?.mkdirs()

        if (dbFile.exists() && dbFile.length() == 0L) {
            dbFile.delete()
        }

        val appDataDir = dbFile.parentFile?.absolutePath ?: AppDataPaths.rootDirectory().absolutePath
        conn = DriverManager.getConnection("jdbc:duckdb:${dbFile.absolutePath}")
        readConn = conn.unwrap(org.duckdb.DuckDBConnection::class.java).duplicate()

        // Ensure parquet extension is loaded for export and configure DuckDB settings
        val tmpDirFile = File(appDataDir, "duckdb_tmp")
        tmpDirFile.mkdirs()
        conn.createStatement().use { st ->
            st.execute("SET memory_limit='${duckDbResourceSettings.memoryLimit}'")
            st.execute("SET threads=${duckDbResourceSettings.workerThreads}")
            st.execute("SET preserve_insertion_order=false")
            val safeTmpDir = tmpDirFile.absolutePath.replace("\\", "/").replace("'", "''")
            st.execute("SET temp_directory='$safeTmpDir'")
            st.execute("INSTALL parquet;")
            st.execute("LOAD parquet;")
        }

        ephemeralConn = DriverManager.getConnection("jdbc:duckdb:")
        ephemeralConn.createStatement().use { st ->
            st.execute("SET memory_limit='${duckDbResourceSettings.memoryLimit}'")
            st.execute("SET threads=${duckDbResourceSettings.workerThreads}")
            st.execute("SET preserve_insertion_order=false")
        }
        ephemeralReadConn = ephemeralConn.unwrap(org.duckdb.DuckDBConnection::class.java).duplicate()

        schemaManager = SchemaMigrationManager(conn, ephemeralConn)
        transactionCoordinator = DatabaseTransactionCoordinator(
            conn,
            readConn,
            ephemeralConn,
            ephemeralReadConn,
            dbMutex,
            readMutex,
            metrics,
        )
        sessionMetadataRepo = SessionMetadataRepository(transactionCoordinator)
        matchLogRepo = MatchLogRepository(transactionCoordinator, sessionMetadataRepo)
        backupExporter = DatabaseBackupExporter(conn, dbMutex)
        integrationRepository = IntegrationRepository(conn, dbMutex)
        integrationEvents = IntegrationEventRecorder(integrationRepository, integrationRouting)

        schemaManager.runMigrations(legacyDbPath)

        // Periodic WAL checkpoint — replaces the per-appender-batch CHECKPOINT that dominated
        // import time with fsyncs on every frame batch. A 60s cadence bounds WAL growth for
        // live streaming and bulk import alike; connection close still flushes on shutdown.
        checkpointJob = checkpointScope.launch {
            while (isActive) {
                delay(CHECKPOINT_INTERVAL_MS)
                runCatching { matchLogRepo.checkpoint() }
            }
        }
    }

    suspend fun checkpoint() = matchLogRepo.checkpoint()

    suspend fun executeNativeCsvImport(sql: String) = matchLogRepo.executeNativeCsvImport(sql)
    suspend fun executeQueryRaw(
        sql: String,
        rowLimit: Int = QueryResult.DEFAULT_RAW_QUERY_ROW_LIMIT
    ): QueryResult = matchLogRepo.executeQueryRaw(sql, rowLimit)
    suspend fun executeAiQuery(
        sql: String,
        rowLimit: Int = QueryResult.DEFAULT_RAW_QUERY_ROW_LIMIT,
    ): QueryResult = matchLogRepo.executeAiQuery(sql, rowLimit)
    suspend fun executeQueryWithParams(sql: String, params: List<Any>): QueryResult = matchLogRepo.executeQueryWithParams(sql, params)
    suspend fun insertSession(session: Session) = sessionMetadataRepo.insertSession(session)
    internal suspend fun insertImportSession(session: Session) = sessionMetadataRepo.insertImportSession(session)
    suspend fun getSessions(): List<Session> = sessionMetadataRepo.getSessions()
    suspend fun getSessionsForWorkspace(teamId: String, seasonId: String, robotId: String): List<Session> =
        sessionMetadataRepo.getSessionsForWorkspace(teamId, seasonId, robotId)
    internal suspend fun findCompletedSessionBySourceHashes(
        teamId: String,
        seasonId: String,
        robotId: String,
        sourceHashes: Set<String>,
    ): Session? = sessionMetadataRepo.findCompletedSessionBySourceHashes(teamId, seasonId, robotId, sourceHashes)
    suspend fun deleteSession(sessionId: String) = sessionMetadataRepo.deleteSession(sessionId)
    suspend fun insertSessionSummary(summary: SessionSummary) {
        sessionMetadataRepo.insertSessionSummary(summary)
        integrationEvents.analysisReady(summary)
    }
    suspend fun saveEngineeringNotebookRevision(
        entry: com.ares.analytics.shared.models.EngineeringNotebookEntry,
        commitRange: String? = null,
        externalUpdatesAllowed: Boolean = true,
    ) {
        integrationRepository.saveNotebookRevision(entry)
        if (commitRange == null) integrationEvents.notebookDraftReady(entry, externalUpdatesAllowed)
        else integrationEvents.softwareDigestReady(entry, commitRange)
    }
    override suspend fun getSessionSummary(sessionId: String): SessionSummary? = sessionMetadataRepo.getSessionSummary(sessionId)
    override suspend fun getAllSessionSummaries(): List<SessionSummary> = sessionMetadataRepo.getAllSessionSummaries()
    suspend fun insertTelemetryFrames(frames: List<TelemetryFrame>) = matchLogRepo.insertTelemetryFrames(frames)
    suspend fun insertRobotActionsBulk(actions: List<com.ares.analytics.shared.models.RobotActionRecord>) = matchLogRepo.insertRobotActionsBulk(actions)
    suspend fun getActionsForSession(sessionId: String): List<com.ares.analytics.shared.models.RobotActionRecord> = matchLogRepo.getActionsForSession(sessionId)
    override suspend fun getSessionTimestampRange(sessionId: String): Pair<Long, Long>? = matchLogRepo.getSessionTimestampRange(sessionId)
    suspend fun getTelemetryRange(sessionId: String, startMs: Long, endMs: Long): List<TelemetryFrame> = matchLogRepo.getTelemetryRange(sessionId, startMs, endMs)
    suspend fun getLatestTelemetryBefore(sessionId: String, timestampMs: Long): List<TelemetryFrame> = matchLogRepo.getLatestTelemetryBefore(sessionId, timestampMs)
    suspend fun getTelemetryRangeBatched(sessionId: String, startMs: Long, endMs: Long, limit: Long, offset: Long): List<TelemetryFrame> = matchLogRepo.getTelemetryRangeBatched(sessionId, startMs, endMs, limit, offset)
    suspend fun countTelemetryFrames(sessionId: String): Long = matchLogRepo.countTelemetryFrames(sessionId)
    suspend fun getTelemetryForKey(sessionId: String, key: String): List<TelemetryFrame> = matchLogRepo.getTelemetryForKey(sessionId, key)
    override suspend fun getTelemetrySeries(
        sessionId: String,
        key: String,
        startMs: Long,
        endMs: Long,
        maxPoints: Int
    ): List<TelemetryFrame> = matchLogRepo.getTelemetrySeries(sessionId, key, startMs, endMs, maxPoints)
    suspend fun getTelemetryPageForKeys(
        sessionId: String,
        keys: List<String>,
        startMs: Long,
        endMs: Long,
        limit: Int = 5_000,
        offset: Long = 0
    ): List<TelemetryFrame> = matchLogRepo.getTelemetryPageForKeys(sessionId, keys, startMs, endMs, limit, offset)
    suspend fun getTelemetryExportPreflight(
        sessionId: String,
        keys: List<String>,
        maximumFrames: Int,
    ): TelemetryExportPreflight = matchLogRepo.getTelemetryExportPreflight(sessionId, keys, maximumFrames)
    suspend fun getTelemetryExportValueTypes(
        sessionId: String,
        keys: List<String>,
    ): Map<String, TelemetryExportValueType> = matchLogRepo.getTelemetryExportValueTypes(sessionId, keys)
    suspend fun getTelemetryExportPage(
        sessionId: String,
        keys: List<String>,
        after: TelemetryExportCursor?,
        limit: Int,
    ): List<TelemetryFrame> = matchLogRepo.getTelemetryExportPage(sessionId, keys, after, limit)
    override suspend fun getDistinctTelemetryKeys(sessionId: String): List<String> = matchLogRepo.getDistinctTelemetryKeys(sessionId)
    suspend fun getTelemetryForKeyPatterns(sessionId: String, patterns: List<String>): List<TelemetryFrame> =
        matchLogRepo.getTelemetryForKeyPatterns(sessionId, patterns)
    suspend fun getDiagnosticsTelemetry(sessionId: String): List<TelemetryFrame> = matchLogRepo.getDiagnosticsTelemetry(sessionId)
    suspend fun replaceAnalysisDiagnostics(sessionId: String, diagnostics: List<AnalysisDiagnostic>) =
        matchLogRepo.replaceAnalysisDiagnostics(sessionId, diagnostics)
    suspend fun getAnalysisDiagnostics(sessionId: String): List<AnalysisDiagnostic> =
        matchLogRepo.getAnalysisDiagnostics(sessionId)
    internal suspend fun replaceSessionImportReports(sessionId: String, reports: List<ImportReport>) =
        matchLogRepo.replaceSessionImportReports(sessionId, reports)
    internal suspend fun completeSessionImport(session: Session, reports: List<ImportReport>) {
        matchLogRepo.completeSessionImport(session, reports)
        // A completed import is a durable boundary. Checkpoint here instead of per batch so a
        // power loss never leaves an arbitrarily large WAL, while normal ingestion still avoids
        // an fsync on every frame batch.
        checkpointAfterDurableBoundary("completed session import")
        integrationEvents.sessionImported(session, reports)
    }
    internal suspend fun getSessionImportReports(sessionId: String): List<ImportReport> =
        matchLogRepo.getSessionImportReports(sessionId)
    suspend fun getTelemetryForFilters(
        sessionId: String,
        keys: List<String>,
        prefixes: List<String>,
        maxFrames: Int = 100_000,
        maxFramesPerTopic: Int = 2_048,
    ): List<TelemetryFrame> = matchLogRepo.getTelemetryForFilters(
        sessionId,
        keys,
        prefixes,
        maxFrames,
        maxFramesPerTopic,
    )
    suspend fun getDistinctTimestamps(sessionId: String): List<Long> = matchLogRepo.getDistinctTimestamps(sessionId)
    suspend fun countTimestampGaps(sessionId: String, minimumGapMs: Long): Long =
        matchLogRepo.countTimestampGaps(sessionId, minimumGapMs)
    suspend fun deleteTelemetryFrames(sessionId: String) = matchLogRepo.deleteTelemetryFrames(sessionId)
    suspend fun pruneTelemetryFrames(sessionId: String, cutoffMs: Long) = matchLogRepo.pruneTelemetryFrames(sessionId, cutoffMs)
    suspend fun insertAnnotation(annotation: SessionAnnotation) = sessionMetadataRepo.insertAnnotation(annotation)
    suspend fun getAnnotations(sessionId: String): List<SessionAnnotation> = sessionMetadataRepo.getAnnotations(sessionId)
    suspend fun updateSessionTags(sessionId: String, tags: List<String>) = sessionMetadataRepo.updateSessionTags(sessionId, tags)
    suspend fun updateSessionMatchDetails(sessionId: String, matchNumber: Int?, allianceColor: String?) = sessionMetadataRepo.updateSessionMatchDetails(sessionId, matchNumber, allianceColor)
    suspend fun associateSessionWithMatch(sessionId: String, matchNumber: Int, allianceColor: String, opponentTeams: List<String>) = sessionMetadataRepo.associateSessionWithMatch(sessionId, matchNumber, allianceColor, opponentTeams)
    suspend fun insertAlert(alert: AlertRecord) {
        sessionMetadataRepo.insertAlert(alert)
        val session = sessionMetadataRepo.getSessions().firstOrNull { it.sessionId == alert.sessionId }
        if (session != null) {
            integrationEvents.alertPersisted(
                alert,
                com.ares.analytics.shared.models.IntegrationWorkspaceIdentity(
                    session.teamId,
                    session.seasonId,
                    session.robotId,
                ),
                session.allowsAutomaticExternalUpdates(),
            )
        }
    }
    suspend fun getAlerts(sessionId: String): List<AlertRecord> = sessionMetadataRepo.getAlerts(sessionId)
    suspend fun insertTopology(topology: HardwareTopology) = sessionMetadataRepo.insertTopology(topology)
    suspend fun getTopology(robotId: String): HardwareTopology? = sessionMetadataRepo.getTopology(robotId)
    suspend fun insertConsoleMessages(messages: List<ConsoleMessage>, sessionId: String) = sessionMetadataRepo.insertConsoleMessages(messages, sessionId)
    suspend fun getConsoleMessages(sessionId: String): List<ConsoleMessage> = sessionMetadataRepo.getConsoleMessages(sessionId)
    suspend fun getTelemetryDensity(sessionId: String, buckets: Int = 100): List<Float> = matchLogRepo.getTelemetryDensity(sessionId, buckets)

    suspend fun importParquet(file: File) {
        backupExporter.importParquet(file)
        checkpointAfterDurableBoundary("Parquet import")
    }

    suspend fun importParquetAsSession(
        file: File,
        sessionId: String,
    ): DatabaseBackupExporter.ParquetImportResult {
        val result = backupExporter.importParquetAsSession(file, sessionId)
        checkpointAfterDurableBoundary("session Parquet import")
        return result
    }

    suspend fun importCloudSessionAtomically(
        file: File,
        summary: SessionSummary,
        session: Session,
    ): DatabaseBackupExporter.ParquetImportResult {
        val result = backupExporter.importCloudSessionAtomically(file, summary, session)
        checkpointAfterDurableBoundary("cloud session import")
        return result
    }
    internal suspend fun importCloudSessionBundleAtomically(
        file: File,
        summary: SessionSummary,
        session: Session,
        actions: List<RobotActionRecord>,
        annotations: List<SessionAnnotation>,
        alerts: List<AlertRecord>,
        consoleMessages: List<ConsoleMessage>,
        analysisDiagnostics: List<AnalysisDiagnostic>,
        importReports: List<ImportReport>,
    ): DatabaseBackupExporter.ParquetImportResult {
        val result = backupExporter.importCloudSessionBundleAtomically(
            file,
            summary,
            session,
            CloudSessionAncillaryData(
                actions,
                annotations,
                alerts,
                consoleMessages,
                analysisDiagnostics,
                importReports,
            ),
        )
        checkpointAfterDurableBoundary("cloud session bundle import")
        return result
    }

    private suspend fun checkpointAfterDurableBoundary(operation: String) {
        runCatching { matchLogRepo.checkpoint() }
            .onFailure { failure ->
                // The transaction is already committed to DuckDB's WAL. A checkpoint failure must
                // not relabel a successful, recoverable import as failed or quarantine its source.
                System.err.println(
                    "[ARES-Analytics] DuckDB checkpoint after $operation was deferred; " +
                        "the committed WAL was preserved for recovery: " +
                        (failure.message ?: failure::class.java.simpleName)
                )
            }
    }
    internal fun setCloudImportFailureInjector(injector: ((CloudImportStage) -> Unit)?) {
        backupExporter.cloudImportFailureInjector = injector
    }
    internal fun setExportReplaceFailureInjector(injector: BeforeAtomicReplace?) {
        backupExporter.exportReplaceFailureInjector = injector
    }
    suspend fun exportSessionToParquet(sessionId: String, file: File) =
        backupExporter.exportSessionToParquet(sessionId, file)
    suspend fun exportSessionsToZip(sessionIds: List<String>, file: File) =
        backupExporter.exportSessionsToZip(sessionIds, file)

    /** Coroutine-aware teardown used by production shutdown paths. */
    suspend fun closeAndJoin() {
        // Stop the periodic checkpoint timer first so it can't fire mid-teardown.
        checkpointScope.cancel()
        checkpointJob.cancelAndJoin()
        withContext(Dispatchers.IO) {
            // readMutex drains in-flight queries (bounded by their statement timeouts) so a
            // reader cannot have its connection closed mid-result; dbMutex orders against
            // writers. Lock order here matches the repository's established write->read order.
            dbMutex.withLock {
                readMutex.withLock {
                    matchLogRepo.dispose()
                    sessionMetadataRepo.dispose()
                    if (!readConn.isClosed) { readConn.close() }
                    if (!conn.isClosed) {
                        runCatching { conn.createStatement().use { it.execute("CHECKPOINT") } }
                            .onFailure { failure ->
                                System.err.println(
                                    "[ARES-Analytics] Final DuckDB checkpoint failed; the WAL was preserved for recovery: " +
                                        (failure.message ?: failure::class.java.simpleName)
                                )
                            }
                        conn.close()
                    }
                    if (!ephemeralReadConn.isClosed) { ephemeralReadConn.close() }
                    if (!ephemeralConn.isClosed) { ephemeralConn.close() }
                }
            }
        }
    }

    /** Blocking compatibility bridge for non-coroutine owners and existing test fixtures. */
    fun close() = runBlocking { closeAndJoin() }

    companion object {
        /** Periodic CHECKPOINT cadence (ms). */
        private const val CHECKPOINT_INTERVAL_MS = 60_000L
        private const val DUCKDB_MEMORY_LIMIT_PROPERTY = "ares.analytics.duckdb.memoryLimit"
        private const val DUCKDB_WORKER_THREADS_PROPERTY = "ares.analytics.duckdb.threads"
        private const val DUCKDB_MEMORY_LIMIT_ENV = "ARES_DUCKDB_MEMORY_LIMIT"
        private const val DUCKDB_WORKER_THREADS_ENV = "ARES_DUCKDB_THREADS"

        private fun configuredDuckDbWorkerThreads(): Int? {
            val configured = System.getProperty(DUCKDB_WORKER_THREADS_PROPERTY)
                ?: System.getenv(DUCKDB_WORKER_THREADS_ENV)
                ?: return null
            return configured.toIntOrNull()
                ?: throw IllegalArgumentException("DuckDB worker thread override must be an integer")
        }
    }
}
