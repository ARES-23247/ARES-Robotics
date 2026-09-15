package com.ares.analytics.service.tuning

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

data class ExternalTuningProposal(
    val source: String,
    val summary: String,
    val values: Map<String, Double>,
    val evidencePath: String? = null,
    val evidenceSha256: String? = null
)

/**
 * Bounded, process-local review queue. Accepted proposals survive absent subscribers, but not
 * application exit. Submission never writes a profile, source file, vendor file, or robot topic.
 * The loaded Tuning board validates a complete proposal before staging any of its values.
 */
class TuningProposalInbox {
    private val pending = ArrayDeque<ExternalTuningProposal>()
    private val lock = Any()
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    fun submit(proposal: ExternalTuningProposal): Boolean = synchronized(lock) {
        if (pending.size >= CAPACITY) return@synchronized false
        val values = LinkedHashMap(proposal.values)
        if (values.isEmpty() || values.any { (key, value) -> key.isBlank() || !value.isFinite() }) return@synchronized false
        val snapshot = proposal.copy(values = Collections.unmodifiableMap(values))
        pending.addLast(snapshot)
        _pendingCount.value = pending.size
        true
    }

    /**
     * Transfers one proposal to a synchronous local-state receiver. No suspension may separate
     * staging and acknowledgement. Returning false defers delivery; a throwing receiver also
     * leaves the proposal queued for retry.
     * Concurrent boards compete for delivery; a proposal is never broadcast to both boards.
     */
    internal fun deliverNext(receiver: (ExternalTuningProposal) -> Boolean): Boolean = synchronized(lock) {
        val proposal = pending.firstOrNull() ?: return@synchronized false
        if (!receiver(proposal)) return@synchronized false
        pending.removeFirst()
        _pendingCount.value = pending.size
        true
    }

    companion object { const val CAPACITY = 8 }
}
