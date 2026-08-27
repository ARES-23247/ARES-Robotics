package com.ares.analytics.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class DashboardHealthStatus { HEALTHY, DEGRADED, CRITICAL }

data class DashboardHealthSnapshot(
    val status: DashboardHealthStatus = DashboardHealthStatus.HEALTHY,
    val ingestFramesPerSecond: Double = 0.0,
    val activeTopics: Int = 0,
    val bufferedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val databaseP95Ms: Double = 0.0,
    val databaseMaxMs: Double = 0.0,
    val databaseQueries: Long = 0,
    val replayCacheFrames: Int = 0,
    val replayCacheHitRatio: Double = 0.0,
    val replayTruncatedWindows: Long = 0,
    val reconnects: Long = 0,
    val connected: Boolean = false,
    val robotLogProfile: String = "UNKNOWN",
    val robotLogQueueDepth: Int = 0,
    val robotLogCurrentFileBytes: Long = 0L,
    val robotLogBytesPerSecond: Double = 0.0,
    val robotLogDroppedFrames: Long = 0L,
    val robotLogPrunedFiles: Long = 0L
)

internal data class RobotLoggingHealthSnapshot(
    val profile: String,
    val queueDepth: Int,
    val currentFileBytes: Long,
    val completedBytes: Long,
    val droppedFrames: Long,
    val prunedFiles: Long
)

internal fun readRobotLoggingHealth(store: TelemetryStore): RobotLoggingHealthSnapshot {
    fun longValue(key: String): Long = store.latest(key)?.value
        ?.takeIf(Double::isFinite)
        ?.coerceAtLeast(0.0)
        ?.toLong()
        ?: 0L
    return RobotLoggingHealthSnapshot(
        profile = store.latest("Diagnostics/Logging/Profile")?.stringValue ?: "UNKNOWN",
        queueDepth = longValue("Diagnostics/Logging/QueueDepth").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        currentFileBytes = longValue("Diagnostics/Logging/CurrentFileBytes"),
        completedBytes = longValue("Diagnostics/Logging/CompletedBytes"),
        droppedFrames = longValue("Diagnostics/Logging/DroppedFrames"),
        prunedFiles = longValue("Diagnostics/Logging/PrunedFiles")
    )
}

/** Aggregates the dashboard's own operational metrics into one observable health snapshot. */
class DashboardHealthService(
    private val telemetryStore: TelemetryStore,
    private val databaseMetrics: DatabaseMetrics,
    private val nt4ClientService: Nt4ClientService,
    private val replayEngineService: ReplayEngineService,
    private val clock: MonotonicClock = SystemMonotonicClock
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mutableHealth = MutableStateFlow(DashboardHealthSnapshot())
    val health: StateFlow<DashboardHealthSnapshot> = mutableHealth.asStateFlow()
    private var samplerJob: Job? = null

    init {
        samplerJob = scope.launch {
            var previousFrames = 0L
            var previousRobotLogBytes = 0L
            var previousSampleNanos = clock.nowNanos()
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                val now = clock.nowNanos()
                val telemetry = telemetryStore.snapshotMetrics()
                val elapsedSeconds = ((now - previousSampleNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
                val ingestRate = (telemetry.acceptedFrames - previousFrames).coerceAtLeast(0L) / elapsedSeconds
                previousFrames = telemetry.acceptedFrames
                previousSampleNanos = now

                val database = databaseMetrics.snapshot()
                val connection = nt4ClientService.connectionMetrics()
                val replay = replayEngineService.cacheMetrics.value
                val robotLogging = readRobotLoggingHealth(telemetryStore)
                val robotLogBytes = robotLogging.currentFileBytes + robotLogging.completedBytes
                val robotLogRate = (robotLogBytes - previousRobotLogBytes).coerceAtLeast(0L) / elapsedSeconds
                previousRobotLogBytes = robotLogBytes
                val cacheLookups = replay.windowLoads + replay.prefetchHits
                val hitRatio = if (cacheLookups == 0L) 0.0 else replay.prefetchHits.toDouble() / cacheLookups
                val status = when {
                    replay.truncatedWindows > 0 || database.p95QueryMs >= CRITICAL_QUERY_P95_MS ||
                        robotLogging.queueDepth >= CRITICAL_LOG_QUEUE_DEPTH -> DashboardHealthStatus.CRITICAL
                    replay.droppedEmissionFrames > 0 || database.p95QueryMs >= DEGRADED_QUERY_P95_MS ||
                        connection.reconnects >= DEGRADED_RECONNECTS || robotLogging.droppedFrames > 0L ||
                        robotLogging.queueDepth >= DEGRADED_LOG_QUEUE_DEPTH -> DashboardHealthStatus.DEGRADED
                    else -> DashboardHealthStatus.HEALTHY
                }
                mutableHealth.value = DashboardHealthSnapshot(
                    status = status,
                    ingestFramesPerSecond = ingestRate,
                    activeTopics = telemetry.activeTopics,
                    bufferedFrames = telemetry.bufferedFrames,
                    droppedFrames = replay.droppedEmissionFrames,
                    databaseP95Ms = database.p95QueryMs,
                    databaseMaxMs = database.maxQueryMs,
                    databaseQueries = database.queryCount,
                    replayCacheFrames = replay.cachedFrames,
                    replayCacheHitRatio = hitRatio,
                    replayTruncatedWindows = replay.truncatedWindows,
                    reconnects = connection.reconnects,
                    connected = connection.connected,
                    robotLogProfile = robotLogging.profile,
                    robotLogQueueDepth = robotLogging.queueDepth,
                    robotLogCurrentFileBytes = robotLogging.currentFileBytes,
                    robotLogBytesPerSecond = robotLogRate,
                    robotLogDroppedFrames = robotLogging.droppedFrames,
                    robotLogPrunedFiles = robotLogging.prunedFiles
                )
            }
        }
    }

    fun dispose() {
        samplerJob?.cancel()
        scope.cancel()
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val DEGRADED_QUERY_P95_MS = 75.0
        const val CRITICAL_QUERY_P95_MS = 250.0
        const val DEGRADED_RECONNECTS = 3L
        const val DEGRADED_LOG_QUEUE_DEPTH = 500
        const val CRITICAL_LOG_QUEUE_DEPTH = 900
    }
}
