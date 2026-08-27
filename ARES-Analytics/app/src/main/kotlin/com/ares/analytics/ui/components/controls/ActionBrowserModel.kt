package com.ares.analytics.ui.components.controls

import com.areslib.catalog.ActionDescriptor

internal data class ActionBrowserGroup(
    val category: String,
    val actions: List<ActionDescriptor>
)

/**
 * Builds the presentation model for the controller action browser. Keeping this logic outside
 * Compose makes catalog discovery deterministic and lets the UI show every action before a
 * student starts searching.
 */
internal fun actionBrowserGroups(
    actions: List<ActionDescriptor>,
    query: String
): List<ActionBrowserGroup> = actions
    .asSequence()
    .filter { actionMatchesBrowserQuery(it, query) }
    .groupBy { it.category.ifBlank { "General" } }
    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    .map { (category, categoryActions) ->
        ActionBrowserGroup(
            category = category,
            actions = categoryActions.sortedWith(
                compareBy<ActionDescriptor, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key }
            )
        )
    }

internal fun actionMatchesBrowserQuery(action: ActionDescriptor, query: String): Boolean {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
    if (tokens.isEmpty()) return true

    val searchableTerms = buildSet {
        add(action.displayName.lowercase())
        add(action.key.lowercase())
        add(action.category.lowercase())
        add(action.description.lowercase())
        action.resources.forEach { add(it.resourceKey.lowercase()) }

        val actionText = joinToString(" ")
        val isIndicator = "indicator" in actionText
        val isPrism = "prism" in actionText
        if (isIndicator || isPrism) {
            add("led")
            add("light")
            add("color")
        }
        if (isPrism) add("prism")
    }

    return tokens.all { token -> searchableTerms.any { term -> token in term } }
}

internal fun actionCatalogSummary(actions: List<ActionDescriptor>): String {
    val categoryCount = actions.map { it.category.ifBlank { "General" } }.distinct().size
    val actionLabel = if (actions.size == 1) "action" else "actions"
    val categoryLabel = if (categoryCount == 1) "category" else "categories"
    return "${actions.size} $actionLabel in $categoryCount $categoryLabel"
}

internal fun actionAccessibleLabel(action: ActionDescriptor): String = buildString {
    append(action.displayName)
    append(". Category: ")
    append(action.category.ifBlank { "General" })
    if (!action.description.isNullOrBlank()) {
        append(". ")
        append(action.description)
    }
}
