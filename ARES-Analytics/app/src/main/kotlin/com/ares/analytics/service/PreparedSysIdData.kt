package com.ares.analytics.service

import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS

/** One owned chronological sample snapshot shared by quality checks and numerical analysis. */
internal class PreparedSysIdData private constructor(
    val rows: List<AlignedDataRow>,
    val originalCount: Int,
    val finiteSampleCount: Int,
    val invalidTimestampCount: Int
) {
    companion object {
        fun from(samples: List<AlignedDataRow>): PreparedSysIdData {
            val rows = ArrayList<AlignedDataRow>(samples.size)
            var finiteCount = 0
            var invalidTimes = 0
            var ordered = true
            var previousTime = -1L
            for (row in samples) {
                val finite = row.voltage.isFinite() && row.velocity.isFinite() && row.accel.isFinite()
                val validTime = row.timestampMs in 0L..MAX_SUPPORTED_TIMESTAMP_MS
                if (finite) finiteCount++
                if (!validTime) invalidTimes++
                if (finite && validTime) {
                    if (row.timestampMs < previousTime) ordered = false
                    previousTime = row.timestampMs
                    rows.add(row)
                }
            }
            if (!ordered) rows.sortBy { it.timestampMs }
            return PreparedSysIdData(rows, samples.size, finiteCount, invalidTimes)
        }
    }
}
