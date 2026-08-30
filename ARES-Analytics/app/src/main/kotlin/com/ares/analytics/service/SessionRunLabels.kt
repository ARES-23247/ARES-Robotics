package com.ares.analytics.service

import com.ares.analytics.shared.models.Session

/**
 * Human-readable run identity shared by Guided Review and comparison reports.
 *
 * Stable session IDs remain the actual identity. Academy imports carry a source-filename tag so
 * students see which teaching scenario they selected instead of two identical synthetic-data
 * labels.
 */
internal fun Session.shortRunLabel(): String = matchNumber?.let { "Match $it" }
    ?: academyPracticeLabel()
    ?: "Run ${sessionId.take(8)}"

private fun Session.academyPracticeLabel(): String? = tags
    .firstOrNull { it.startsWith(ACADEMY_PRACTICE_SOURCE_PREFIX) }
    ?.removePrefix(ACADEMY_PRACTICE_SOURCE_PREFIX)
    ?.removeSuffix(".csv")
    ?.split('-')
    ?.filter(String::isNotBlank)
    ?.joinToString(" ")
    ?.replaceFirstChar { character -> character.uppercase() }
    ?.let { "Academy · $it" }

private const val ACADEMY_PRACTICE_SOURCE_PREFIX = "academy-practice-source:"
