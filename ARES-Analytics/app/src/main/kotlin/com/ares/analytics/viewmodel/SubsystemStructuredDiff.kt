package com.ares.analytics.viewmodel

/** Deterministic line diff used when reviewing replacement of generated starter files. */
internal fun structuredLineDiff(existing: String, proposed: String, contextLines: Int = 3): List<SubsystemDiffLine> {
    val before = existing.lines()
    val after = proposed.lines()
    var prefix = 0
    while (prefix < before.size && prefix < after.size && before[prefix] == after[prefix]) prefix++
    var suffix = 0
    while (
        suffix < before.size - prefix && suffix < after.size - prefix &&
        before[before.lastIndex - suffix] == after[after.lastIndex - suffix]
    ) suffix++

    val leadingStart = (prefix - contextLines.coerceAtLeast(0)).coerceAtLeast(0)
    val trailingCount = suffix.coerceAtMost(contextLines.coerceAtLeast(0))
    return buildList {
        before.subList(leadingStart, prefix).forEach { add(SubsystemDiffLine(SubsystemDiffLineKind.CONTEXT, it)) }
        before.subList(prefix, before.size - suffix).forEach { add(SubsystemDiffLine(SubsystemDiffLineKind.REMOVED, it)) }
        after.subList(prefix, after.size - suffix).forEach { add(SubsystemDiffLine(SubsystemDiffLineKind.ADDED, it)) }
        if (trailingCount > 0) {
            after.subList(after.size - suffix, after.size - suffix + trailingCount)
                .forEach { add(SubsystemDiffLine(SubsystemDiffLineKind.CONTEXT, it)) }
        }
    }
}

internal fun parseUnifiedDiff(diff: String): List<SubsystemDiffLine> = diff.lineSequence()
    .filterNot { it.startsWith("@@") }
    .map { line ->
        when {
            line.startsWith("+") -> SubsystemDiffLine(SubsystemDiffLineKind.ADDED, line.drop(1))
            line.startsWith("-") -> SubsystemDiffLine(SubsystemDiffLineKind.REMOVED, line.drop(1))
            else -> SubsystemDiffLine(SubsystemDiffLineKind.CONTEXT, line.removePrefix(" "))
        }
    }
    .toList()
