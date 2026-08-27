package com.ares.analytics.viewmodel.controls

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityContext
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlTargetKind

/** Honest reachability evidence for one control scheme; this does not claim an action was executed. */
data class ControlsCoverage(
    val teleOpActions: List<ActionDescriptor>,
    val directlyBoundActionKeys: Set<String>,
    val missingActions: List<ActionDescriptor>,
    val safetyActions: List<ActionDescriptor>,
    val missingSafetyActions: List<ActionDescriptor>,
) {
    val boundCount: Int get() = teleOpActions.count { it.key in directlyBoundActionKeys }
    val totalCount: Int get() = teleOpActions.size
    val fraction: Double get() = if (totalCount == 0) 1.0 else boundCount.toDouble() / totalCount
}

/**
 * Computes direct driver/operator reachability from canonical catalog and binding documents.
 *
 * A routine binding is intentionally not counted as a direct action binding: a routine can call an
 * action conditionally, and presenting that as a guaranteed emergency/recovery control would be
 * misleading. Disabled bindings are also excluded.
 */
internal fun controlsCoverage(
    actions: List<ActionDescriptor>,
    scheme: ControlSchemeDocument?,
): ControlsCoverage {
    val teleOp = actions
        .filter { CapabilityContext.TELEOP in it.allowedContexts }
        .sortedWith(compareBy<ActionDescriptor> { it.category.lowercase() }.thenBy { it.displayName.lowercase() })
    val bound = scheme?.bindings.orEmpty()
        .asSequence()
        .filter { it.enabled && it.target.kind == ControlTargetKind.ACTION }
        .map { it.target.key }
        .toSet()
    val safety = teleOp.filter(::isSafetyControlAction)
    return ControlsCoverage(
        teleOpActions = teleOp,
        directlyBoundActionKeys = bound,
        missingActions = teleOp.filterNot { it.key in bound },
        safetyActions = safety,
        missingSafetyActions = safety.filterNot { it.key in bound },
    )
}

/** Safety recovery must be reachable without relying on an arbitrary routine branch. */
internal fun isSafetyControlAction(action: ActionDescriptor): Boolean {
    val key = action.key.lowercase()
    return action.category.contains("safety", ignoreCase = true) ||
        key.endsWith(".recoverneutral") ||
        key.endsWith(".confirmcalibration") ||
        key.contains("emergencystop") ||
        key.contains("emergency-stop")
}
