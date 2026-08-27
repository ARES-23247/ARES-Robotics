package com.areslib.hardware

/**
 * Allocation-free-after-growth sampler for cached current providers.
 *
 * Every provider is read at most once per [sample] call and provider failures are isolated. A
 * valid aggregate suppresses valid constituents identified by [CurrentSourceIO.includesCurrentFrom]
 * so total-current fallbacks cannot double-count the same electrical branch.
 */
class CurrentSourceSampler(initialCapacity: Int = 16) {
    private var readings = DoubleArray(initialCapacity.coerceAtLeast(1))
    private var valid = BooleanArray(readings.size)
    private var selected = BooleanArray(readings.size)
    private var sources: List<CurrentSourceIO> = emptyList()

    val size: Int get() = sources.size
    var validSelectedSourceCount: Int = 0
        private set
    val hasValidReading: Boolean get() = validSelectedSourceCount > 0
    /** True when every registered leaf source is valid or represented by a valid aggregate. */
    var hasCompleteCoverage: Boolean = false
        private set

    fun sample(currentSources: List<CurrentSourceIO>, includeMotorSources: Boolean = true): Double {
        ensureCapacity(currentSources.size)
        sources = currentSources
        for (index in currentSources.indices) {
            val source = currentSources[index]
            if (!includeMotorSources && source is com.areslib.hardware.actuator.MotorIO) {
                readings[index] = 0.0
                valid[index] = false
                selected[index] = false
                continue
            }
            try {
                val reading = source.currentAmps
                readings[index] = reading
                valid[index] = source.isCurrentReadingValid(reading)
            } catch (_: Exception) {
                readings[index] = 0.0
                valid[index] = false
            }
            selected[index] = false
        }

        hasCompleteCoverage = currentSources.isNotEmpty()
        if (hasCompleteCoverage) {
            for (index in currentSources.indices) {
                if (!valid[index] && !isCoveredByAnotherValidSource(index) && !isAggregateSource(index)) {
                    hasCompleteCoverage = false
                    break
                }
            }
        }

        var total = 0.0
        validSelectedSourceCount = 0
        for (index in currentSources.indices) {
            if (!valid[index] || isCoveredByAnotherValidSource(index)) continue
            selected[index] = true
            validSelectedSourceCount++
            total += readings[index]
        }
        return if (hasValidReading) total else Double.NaN
    }

    fun sourceAt(index: Int): CurrentSourceIO = sources[index]
    fun readingAt(index: Int): Double = readings[index]
    fun isSelected(index: Int): Boolean = index in 0 until size && selected[index]

    /** Exception-isolated coverage query for callers reconciling estimates with aggregates. */
    fun includes(aggregate: CurrentSourceIO, constituent: CurrentSourceIO): Boolean {
        return try {
            aggregate.includesCurrentFrom(constituent)
        } catch (_: Exception) {
            false
        }
    }

    private fun isCoveredByAnotherValidSource(index: Int): Boolean {
        val source = sources[index]
        for (candidateIndex in sources.indices) {
            if (candidateIndex == index || !valid[candidateIndex]) continue
            if (includes(sources[candidateIndex], source)) return true
        }
        return false
    }

    /** An invalid aggregate is optional when its registered constituents remain independently observable. */
    private fun isAggregateSource(index: Int): Boolean {
        val candidate = sources[index]
        for (otherIndex in sources.indices) {
            if (otherIndex != index && includes(candidate, sources[otherIndex])) return true
        }
        return false
    }

    private fun ensureCapacity(required: Int) {
        if (required <= readings.size) return
        var capacity = readings.size
        while (capacity < required) capacity *= 2
        readings = DoubleArray(capacity)
        valid = BooleanArray(capacity)
        selected = BooleanArray(capacity)
    }
}
