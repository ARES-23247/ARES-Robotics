package com.ares.analytics.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    /** Null until dashboard fan-out loss is measured. Robot logging has its own counter. */
    val droppedFrames: Long? = null,
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
    val prunedFiles: Long,
    val byteCountersAvailable: Boolean
)

internal fun readRobotLoggingHealth(store: TelemetryStore): RobotLoggingHealthSnapshot {
    fun longValue(value: Double?): Long = value?.takeIf(Double::isFinite)
        ?.coerceAtLeast(0.0)?.toLong() ?: 0L
    val currentBytes = store.latest("Diagnostics/Logging/CurrentFileBytes")?.value
    val completedBytes = store.latest("Diagnostics/Logging/CompletedBytes")?.value
    return RobotLoggingHealthSnapshot(
        profile = store.latest("Diagnostics/Logging/Profile")?.stringValue ?: "UNKNOWN",
        queueDepth = longValue(store.latest("Diagnostics/Logging/QueueDepth")?.value)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        currentFileBytes = longValue(currentBytes),
        completedBytes = longValue(completedBytes),
        droppedFrames = longValue(store.latest("Diagnostics/Logging/DroppedFrames")?.value),
        prunedFiles = longValue(store.latest("Diagnostics/Logging/PrunedFiles")?.value),
        byteCountersAvailable = currentBytes?.isFinite() == true && completedBytes?.isFinite() == true
    )
}

/** Aggregates the dashboard's own operational metrics into one observable health snapshot. */
class DashboardHealthService(
    private val telemetryStore: TelemetryStore,
    private val databaseMetrics: DatabaseMetrics,
    private val nt4ClientService: Nt4ClientService,
    private val replayEngineService: ReplayEngineService,
    private val clock: MonotonicClock = SystemMonotonicClock,
    samplerDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(samplerDispatcher + SupervisorJob())
    private val mutableHealth = MutableStateFlow(DashboardHealthSnapshot())
    val health: StateFlow<DashboardHealthSnapshot> = mutableHealth.asStateFlow()

    init {
        scope.launch {
            val ingestCounter = DashboardCounterRate()
            val robotLogCounter = DashboardCounterRate()
            fun sampleRobotBytes(logging: RobotLoggingHealthSnapshot, now: Long, epoch: Long): Double {
                if (!logging.byteCountersAvailable) {
                    robotLogCounter.reset()
                    return 0.0
                }
                val total = logging.currentFileBytes.toULong() + logging.completedBytes.toULong()
                return robotLogCounter.sample(total, now, epoch)
            }
            val initialNanos = clock.nowNanos()
            val initialEpoch = telemetryStore.currentTargetEpoch()
            ingestCounter.sample(telemetryStore.snapshotMetrics().acceptedFrames.coerceAtLeast(0).toULong(),
                initialNanos, initialEpoch)
            sampleRobotBytes(readRobotLoggingHealth(telemetryStore), initialNanos, initialEpoch)
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                val now = clock.nowNanos()
                val epoch = telemetryStore.currentTargetEpoch()
                val telemetry = telemetryStore.snapshotMetrics()
                val ingestRate = ingestCounter.sample(telemetry.acceptedFrames.coerceAtLeast(0).toULong(), now, epoch)
                val database = databaseMetrics.snapshot()
                val connection = nt4ClientService.connectionMetrics()
                val replay = replayEngineService.cacheMetrics.value
                val robotLogging = readRobotLoggingHealth(telemetryStore)
                val robotLogRate = sampleRobotBytes(robotLogging, now, epoch)
                // Every installed prefetch increments windowLoads too: hits are a subset.
                val hitRatio = if (replay.windowLoads <= 0L) 0.0 else
                    (replay.prefetchHits.toDouble() / replay.windowLoads.toDouble()).coerceIn(0.0, 1.0)
                val status = when {
                    replay.truncatedWindows > 0 || database.p95QueryMs >= CRITICAL_QUERY_P95_MS ||
                        robotLogging.queueDepth >= CRITICAL_LOG_QUEUE_DEPTH -> DashboardHealthStatus.CRITICAL
                    database.p95QueryMs >= DEGRADED_QUERY_P95_MS ||
                        connection.reconnects >= DEGRADED_RECONNECTS || robotLogging.droppedFrames > 0L ||
                        robotLogging.queueDepth >= DEGRADED_LOG_QUEUE_DEPTH -> DashboardHealthStatus.DEGRADED
                    else -> DashboardHealthStatus.HEALTHY
                }
                mutableHealth.value = DashboardHealthSnapshot(
                    status = status,
                    ingestFramesPerSecond = ingestRate,
                    activeTopics = telemetry.activeTopics,
                    bufferedFrames = telemetry.bufferedFrames,
                    droppedFrames = null,
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
