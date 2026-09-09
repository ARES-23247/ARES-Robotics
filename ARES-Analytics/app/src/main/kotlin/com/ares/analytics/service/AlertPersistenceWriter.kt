package com.ares.analytics.service

import com.ares.analytics.shared.models.AlertRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Pending is a count of distinct unsaved occurrences, including any in-flight write. */
data class AlertPersistenceStatus(val pending: Int = 0, val failed: Boolean = false, val stopped: Boolean = false)

/** One writer, two retained records per unsaved occurrence, and a conflated wakeup signal. */
internal class AlertPersistenceWriter(scope: CoroutineScope, private val write: suspend (AlertRecord) -> Unit) {
    private class Pending(var initial: AlertRecord?, var latest: AlertRecord)
    private val lock = Any()
    private val pending = LinkedHashMap<String, Pending>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var accepting = true
    private val mutableStatus = MutableStateFlow(AlertPersistenceStatus())
    val status: StateFlow<AlertPersistenceStatus> = mutableStatus.asStateFlow()
    private val job = scope.launch {
        var retryMs = 250L
        try {
            while (isActive) {
                val next = synchronized(lock) { pending.values.firstOrNull()?.let { it.initial ?: it.latest } }
                if (next == null) { wake.receive(); continue }
                try {
                    write(next)
                    ensureActive()
                    synchronized(lock) {
                        val entry = pending.remove(next.alertId)!!
                        if (entry.initial === next) entry.initial = null
                        // A newer peak, acknowledgment or resolution may have arrived during IO.
                        if (entry.latest != next) pending[next.alertId] = entry
                        mutableStatus.value = mutableStatus.value.copy(
                            pending = pending.size,
                            failed = pending.isNotEmpty() && mutableStatus.value.failed,
                        )
                    }
                    retryMs = 250L
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    synchronized(lock) {
                        // A single rejected occurrence must not monopolize the writer.
                        pending.remove(next.alertId)?.let { pending[next.alertId] = it }
                        mutableStatus.value = mutableStatus.value.copy(failed = true)
                    }
                    delay(retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(30_000L)
                }
            }
        } finally {
            synchronized(lock) {
                accepting = false
                mutableStatus.value = mutableStatus.value.copy(stopped = true)
            }
        }
    }

    fun submit(alert: AlertRecord) {
        if (alert.sessionId == "live-telemetry") return
        synchronized(lock) {
            check(accepting && !mutableStatus.value.stopped) { "Alert persistence is closed" }
            val existing = pending[alert.alertId]
            if (existing == null) pending[alert.alertId] = Pending(alert, alert)
            else existing.latest = alert
            mutableStatus.value = mutableStatus.value.copy(pending = pending.size)
        }
        wake.trySend(Unit)
    }

    /** Failed drains retain their queue and retry worker so an owner can retry shutdown. */
    suspend fun finish(timeoutMs: Long = 5_000L): Boolean {
        require(timeoutMs > 0)
        synchronized(lock) { accepting = false }
        val drained = withTimeoutOrNull(timeoutMs) {
            status.first { it.pending == 0 || it.stopped }.pending == 0
        } ?: false
        if (!drained) return false
        close()
        job.join()
        return true
    }

    /** Immediate cancellation for emergency/disposable owners; never reports pending data as saved. */
    fun close() {
        synchronized(lock) {
            accepting = false
            mutableStatus.value = mutableStatus.value.copy(stopped = true)
        }
        job.cancel()
        wake.close()
    }
}
