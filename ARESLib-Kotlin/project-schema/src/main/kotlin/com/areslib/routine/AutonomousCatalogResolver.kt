package com.areslib.routine

/** Deterministic result of resolving an operator request against enabled autonomous entries. */
data class AutonomousCatalogResolution(
    val entry: AutonomousCatalogEntry,
    val requestedId: String,
    val usedFallback: Boolean,
)

/** Shared enabled-entry ordering and fail-safe fallback policy for FTC and FRC runtimes. */
class AutonomousCatalogResolver(
    entries: List<AutonomousCatalogEntry>,
    defaultEntryId: String?,
    safeFallbackEntryId: String = "do-nothing",
) {
    val enabledEntries: List<AutonomousCatalogEntry> = entries
        .asSequence()
        .filter(AutonomousCatalogEntry::enabled)
        .sortedWith(compareBy<AutonomousCatalogEntry> { it.sortOrder }.thenBy { it.entryId })
        .toList()

    private val entriesById = enabledEntries.associateBy(AutonomousCatalogEntry::entryId)
    private val fallback = defaultEntryId?.let(entriesById::get)
        ?: entriesById[safeFallbackEntryId]
        ?: enabledEntries.firstOrNull()

    val availableEntryIds: List<String> = enabledEntries.map(AutonomousCatalogEntry::entryId)

    fun resolve(requestedId: String?): AutonomousCatalogResolution {
        val normalized = requestedId.orEmpty().trim()
        val requested = entriesById[normalized]
        val selected = requested ?: checkNotNull(fallback) {
            "Generated autonomous catalog has no enabled fail-safe entry"
        }
        return AutonomousCatalogResolution(selected, normalized, requested == null)
    }
}
