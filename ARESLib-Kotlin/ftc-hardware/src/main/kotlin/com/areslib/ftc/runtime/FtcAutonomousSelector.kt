package com.areslib.ftc.runtime

import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.AutonomousCatalogResolver
import com.areslib.state.Alliance

/** Deterministic INIT selector over enabled generated autonomous entries. */
class FtcAutonomousSelector(
    entries: List<AutonomousCatalogEntry>,
    defaultEntryId: String?,
    initialAlliance: Alliance,
    private val lockedEntryId: String? = null,
    private val lockedAlliance: Alliance? = null,
) {
    val entries: List<AutonomousCatalogEntry> = AutonomousCatalogResolver(entries, defaultEntryId).enabledEntries

    private var index = selectInitialIndex(lockedEntryId ?: defaultEntryId)
    private var previousLeft = false
    private var previousRight = false
    private var previousAllianceToggle = false

    var alliance: Alliance = lockedAlliance ?: initialAlliance
        private set

    val selected: AutonomousCatalogEntry?
        get() = entries.getOrNull(index)

    fun selectEntry(entryId: String): Boolean {
        if (lockedEntryId != null) return false
        val requestedIndex = entries.indexOfFirst { it.entryId == entryId }
        if (requestedIndex < 0 || requestedIndex == index) return false
        index = requestedIndex
        return true
    }

    fun selectAlliance(requestedAlliance: Alliance): Boolean {
        if (lockedAlliance != null || requestedAlliance == alliance) return false
        alliance = requestedAlliance
        return true
    }

    fun update(left: Boolean, right: Boolean, toggleAlliance: Boolean): Boolean {
        var changed = false
        if (lockedEntryId == null && entries.size > 1) {
            if (left && !previousLeft) {
                index = (index - 1 + entries.size) % entries.size
                changed = true
            }
            if (right && !previousRight) {
                index = (index + 1) % entries.size
                changed = true
            }
        }
        if (lockedAlliance == null && toggleAlliance && !previousAllianceToggle) {
            alliance = if (alliance == Alliance.RED) Alliance.BLUE else Alliance.RED
            changed = true
        }
        previousLeft = left
        previousRight = right
        previousAllianceToggle = toggleAlliance
        return changed
    }

    private fun selectInitialIndex(requestedId: String?): Int {
        if (entries.isEmpty()) return -1
        val requestedIndex = entries.indexOfFirst { it.entryId == requestedId }
        if (requestedIndex >= 0) return requestedIndex
        return if (lockedEntryId != null) -1 else 0
    }
}
