package com.ares.analytics.domain.navigation

/** Named tabs exposed inside the Pit Operations Hub (`NavigationTarget.PIT_DIAGNOSTICS`). */
enum class PitOperationsSubTab(
    val index: Int,
    val label: String,
) {
    READINESS(0, "Readiness & Sign-Off"),
    BATTERY_FLEET(1, "Battery Fleet"),
    MATCH_QUEUE(2, "Match Queue"),
    ALERT_THRESHOLDS(3, "Alert Thresholds"),
    ;

    companion object {
        fun fromIndex(index: Int): PitOperationsSubTab =
            entries.firstOrNull { it.index == index } ?: READINESS
    }
}

/**
 * One-shot deep-link request targeting a specific Pit Operations sub-tab or highlighted entity.
 * Incrementing [nonce] triggers tab focus inside the live Pit Operations view.
 */
data class PitDeepLinkRequest(
    val tab: PitOperationsSubTab = PitOperationsSubTab.READINESS,
    val highlightedBatteryId: String? = null,
    val highlightedMatchNumber: Int? = null,
    val nonce: Int = 0,
)

/** One segment of the top header contextual breadcrumb trail. */
data class BreadcrumbSegment(
    val label: String,
    val target: NavigationTarget? = null,
    val isCurrent: Boolean = false,
)

private val robotStudioChildTargets = setOf(
    NavigationTarget.PROJECT_IDENTITY,
    NavigationTarget.DRIVEBASE_BUILDER,
    NavigationTarget.SUBSYSTEM_GEN,
    NavigationTarget.HARDWARE_SETUP,
    NavigationTarget.HARDWARE_STUDIO,
    NavigationTarget.SUPERSTRUCTURE_STUDIO,
)

/**
 * Builds deterministic clickable hierarchical breadcrumbs for the active Studio destination
 * and optional active entity/tab sub-context (e.g., active subsystem, battery tab, or match debrief).
 */
fun buildContextualBreadcrumbs(
    sectionLabel: String?,
    parentTarget: NavigationTarget?,
    activeTarget: NavigationTarget,
    subContextLabel: String? = null,
): List<BreadcrumbSegment> {
    val cleanSub = subContextLabel?.trim()?.takeIf(String::isNotBlank)
    val segments = mutableListOf<BreadcrumbSegment>()

    if (activeTarget in robotStudioChildTargets && activeTarget != NavigationTarget.ROBOT_STUDIO) {
        segments += BreadcrumbSegment(
            label = NavigationTarget.ROBOT_STUDIO.label,
            target = NavigationTarget.ROBOT_STUDIO,
            isCurrent = false,
        )
    } else if (!sectionLabel.isNullOrBlank() && parentTarget != activeTarget) {
        segments += BreadcrumbSegment(
            label = sectionLabel,
            target = parentTarget,
            isCurrent = false,
        )
    }

    segments += BreadcrumbSegment(
        label = activeTarget.label,
        target = activeTarget,
        isCurrent = cleanSub == null,
    )

    if (cleanSub != null) {
        segments += BreadcrumbSegment(
            label = cleanSub,
            target = null,
            isCurrent = true,
        )
    }

    return segments
}

/** Single entry on the per-workspace Studio navigation stack. */
data class NavigationHistoryEntry(
    val target: NavigationTarget,
    val subLabel: String? = null,
)

/**
 * Bounded browser-style Back/Forward navigation stack synchronized per workspace.
 * Pure value type free of UI or service dependencies so transitions are deterministically tested.
 */
data class NavigationHistoryStack(
    val entries: List<NavigationHistoryEntry> = listOf(NavigationHistoryEntry(NavigationTarget.DASHBOARD)),
    val currentIndex: Int = 0,
    val maxEntries: Int = 32,
) {
    init {
        require(maxEntries >= 2) { "maxEntries must be at least 2." }
    }

    private val safeEntries: List<NavigationHistoryEntry>
        get() = if (entries.isEmpty()) listOf(NavigationHistoryEntry(NavigationTarget.DASHBOARD)) else entries

    private val safeIndex: Int
        get() = currentIndex.coerceIn(0, safeEntries.lastIndex)

    val current: NavigationHistoryEntry
        get() = safeEntries[safeIndex]

    val canGoBack: Boolean
        get() = safeIndex > 0

    val canGoForward: Boolean
        get() = safeIndex < safeEntries.lastIndex

    /** Recently visited distinct destinations (most recent first), excluding [current]. */
    val recentTargets: List<NavigationTarget>
        get() = buildList {
            val currentTarget = current.target
            for (idx in safeIndex - 1 downTo 0) {
                val candidate = safeEntries[idx].target
                if (candidate != currentTarget && candidate !in this) {
                    add(candidate)
                    if (size >= 6) break
                }
            }
        }

    fun navigateTo(
        target: NavigationTarget,
        subLabel: String? = null,
    ): NavigationHistoryStack {
        val currentList = safeEntries
        val idx = safeIndex
        val cleanSub = subLabel?.trim()?.takeIf(String::isNotBlank)
        val currentEntry = currentList[idx]
        if (currentEntry.target == target) {
            val updated = currentList.toMutableList()
            val resolvedSub = cleanSub ?: if (subLabel == null) null else currentEntry.subLabel
            updated[idx] = currentEntry.copy(subLabel = resolvedSub)
            return copy(entries = updated, currentIndex = idx)
        }
        val retained = currentList.subList(0, idx + 1) + NavigationHistoryEntry(target, cleanSub)
        val trimmed = if (retained.size > maxEntries) retained.takeLast(maxEntries) else retained
        return copy(entries = trimmed, currentIndex = trimmed.lastIndex)
    }

    fun updateCurrentSubLabel(subLabel: String?): NavigationHistoryStack {
        val currentList = safeEntries
        val idx = safeIndex
        val cleanSub = subLabel?.trim()?.takeIf(String::isNotBlank)
        if (currentList[idx].subLabel == cleanSub) return this
        val updated = currentList.toMutableList()
        updated[idx] = currentList[idx].copy(subLabel = cleanSub)
        return copy(entries = updated, currentIndex = idx)
    }

    fun goBack(): NavigationHistoryStack =
        if (!canGoBack) this else copy(entries = safeEntries, currentIndex = safeIndex - 1)

    fun goForward(): NavigationHistoryStack =
        if (!canGoForward) this else copy(entries = safeEntries, currentIndex = safeIndex + 1)

    fun reset(
        initialTarget: NavigationTarget = NavigationTarget.DASHBOARD,
        subLabel: String? = null,
    ): NavigationHistoryStack =
        NavigationHistoryStack(
            entries = listOf(NavigationHistoryEntry(initialTarget, subLabel?.trim()?.takeIf(String::isNotBlank))),
            currentIndex = 0,
            maxEntries = maxEntries,
        )
}
