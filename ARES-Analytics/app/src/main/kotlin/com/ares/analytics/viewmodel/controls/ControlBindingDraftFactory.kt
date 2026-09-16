package com.ares.analytics.viewmodel.controls

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.catalog.initialCapabilityArguments
import com.areslib.controls.AnalogControlPolicyDocument
import com.areslib.controls.AxisTransformDocument
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlSourceDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetDocument
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControllerControlTypeDocument

internal fun createChordBindingDraft(current: ControlsEditorState): ControlsEditorState {
    if (current.draftHasUnappliedChanges) {
        return current.copy(status = "Apply or discard the current binding draft first.")
    }
    val control = current.selectedControl
        ?: return current.copy(status = "Select the first button in the chord.")
    if (control.type != ControllerControlTypeDocument.BUTTON) {
        return current.copy(status = "A chord starts with a button, not an analog axis.")
    }
    val scheme = current.selectedScheme ?: return current
    val slot = current.selectedControllerSlot ?: return current
    val target = current.actions.firstOrNull()?.let { descriptor ->
        ControlTargetDocument(ControlTargetKind.ACTION, descriptor.key, initialCapabilityArguments(descriptor.parameters))
    } ?: current.routineIds.firstOrNull()?.let { routine ->
        ControlTargetDocument(ControlTargetKind.ROUTINE, routine)
    } ?: ControlTargetDocument(ControlTargetKind.ACTION, "choose.action")
    return current.copy(
        selectedBindingId = null,
        draftBinding = ControlBindingDocument(
            bindingId = uniqueBindingId(scheme, "${control.controlId}-chord"),
            displayName = "${control.displayName} chord",
            source = ControlSourceDocument(
                kind = ControlSourceKind.CHORD,
                controllerSlot = slot,
                controlIds = listOf(control.controlId),
                chordWindowSeconds = .075,
            ),
            event = ControlEvent.PRESS,
            target = target,
            suppressConstituentBindings = true,
        ),
        draftHasUnappliedChanges = true,
        status = "Click at least one more button on the controller, then choose the action or routine.",
    )
}

internal fun createRoutineMacroBindingDraft(current: ControlsEditorState): ControlsEditorState {
    if (current.draftHasUnappliedChanges) {
        return current.copy(status = "Apply or discard the current binding draft first.")
    }
    val routineId = current.routineIds.firstOrNull()
        ?: return current.copy(status = "Create a reusable routine in Routines & Auto before binding a macro.")
    val control = current.selectedControl
        ?: return current.copy(status = "Select the button that should run the macro.")
    if (control.type != ControllerControlTypeDocument.BUTTON) {
        return current.copy(status = "A routine macro requires a button or button chord.")
    }
    val scheme = current.selectedScheme ?: return current
    val slot = current.selectedControllerSlot ?: return current
    return current.copy(
        selectedBindingId = null,
        draftBinding = ControlBindingDocument(
            bindingId = uniqueBindingId(scheme, "${control.controlId}-macro-$routineId"),
            displayName = "Run $routineId",
            source = ControlSourceDocument(ControlSourceKind.BUTTON, slot, listOf(control.controlId)),
            event = ControlEvent.PRESS,
            target = ControlTargetDocument(ControlTargetKind.ROUTINE, routineId),
        ),
        draftHasUnappliedChanges = true,
        status = "Review the macro routine and invocation policy, then add the binding.",
    )
}

internal fun createDefaultBindingDraft(current: ControlsEditorState): ControlsEditorState {
    if (current.draftHasUnappliedChanges) {
        return current.copy(status = "Apply or discard the current binding draft before creating another.")
    }
    val control = current.selectedControl ?: return current
    val scheme = current.selectedScheme ?: return current
    val slot = current.selectedControllerSlot ?: return current
    val id = uniqueBindingId(scheme, control.controlId)
    val axis = control.type == ControllerControlTypeDocument.AXIS
    val source = if (axis) {
        ControlSourceDocument(
            kind = ControlSourceKind.AXIS_THRESHOLD,
            controllerSlot = slot,
            controlIds = listOf(control.controlId),
            transform = AxisTransformDocument(),
            pressThreshold = 0.65,
            releaseThreshold = 0.50
        )
    } else {
        ControlSourceDocument(ControlSourceKind.BUTTON, slot, listOf(control.controlId))
    }
    val target = current.actions.firstOrNull()?.let { descriptor ->
        ControlTargetDocument(
            ControlTargetKind.ACTION,
            descriptor.key,
            initialCapabilityArguments(descriptor.parameters),
        )
    } ?: current.routineIds.firstOrNull()?.let { routine ->
        ControlTargetDocument(ControlTargetKind.ROUTINE, routine)
    } ?: ControlTargetDocument(ControlTargetKind.ACTION, "choose.action")
    return current.copy(
        selectedBindingId = null,
        draftBinding = ControlBindingDocument(
            bindingId = id,
            displayName = "${control.displayName} binding",
            source = source,
            event = ControlEvent.PRESS,
            target = target
        ),
        draftHasUnappliedChanges = true
    )
}

internal fun createBindingForActionDraft(current: ControlsEditorState, actionKey: String): ControlsEditorState {
    if (current.draftHasUnappliedChanges) {
        return current.copy(status = "Apply or discard the current binding draft first.")
    }
    val action = current.actions.firstOrNull { it.key == actionKey }
        ?: return current.copy(status = "That action is no longer in the project catalog. Reload controls.")
    val control = current.selectedControl
        ?: return current.copy(status = "Select the button or axis that should run '${action.displayName}', then choose Bind.")
    if (control.type != ControllerControlTypeDocument.BUTTON) {
        return current.copy(
            status = "Select a button for '${action.displayName}'. Continuous axis actions require an explicit value contract.",
        )
    }
    val scheme = current.selectedScheme ?: return current
    val slot = current.selectedControllerSlot ?: return current
    val draft = ControlBindingDocument(
        bindingId = uniqueBindingId(scheme, "${control.controlId}-${action.key}"),
        displayName = action.displayName,
        source = ControlSourceDocument(ControlSourceKind.BUTTON, slot, listOf(control.controlId)),
        event = ControlEvent.PRESS,
        target = ControlTargetDocument(
            ControlTargetKind.ACTION,
            action.key,
            initialCapabilityArguments(action.parameters),
        ),
    )
    return current.copy(
        selectedBindingId = null,
        draftBinding = draft,
        draftHasUnappliedChanges = true,
        status = "Review the input event and arguments, then add the binding. Nothing has been saved yet.",
    )
}

internal fun addSafeMomentaryPairDraft(current: ControlsEditorState): ControlsEditorState {
    val draft = current.draftBinding
        ?: return current.copy(status = "Create a binding draft first.")
    if (current.selectedBindingId != null) {
        return current.copy(status = "Safe pairs can be added from a new binding. Edit existing bindings individually.")
    }
    if (draft.source.kind != ControlSourceKind.BUTTON) {
        return current.copy(status = "A safe hold-and-release pair requires one button input.")
    }
    val action = current.actions.firstOrNull { it.key == draft.target.key }
        ?: return current.copy(status = "Choose a project action first.")
    val outputParameter = momentaryOutputParameter(action)
        ?: return current.copy(status = "That action is not a momentary voltage or duty-cycle output.")
    if (current.problems.any { it.severity == ControlsProblemSeverity.ERROR && (it.bindingId == null || it.bindingId == draft.bindingId) }) {
        return current.copy(status = "Fix the highlighted binding errors before adding the safe pair.")
    }
    val scheme = current.selectedScheme ?: return current
    val runBinding = draft.copy(
        displayName = "${action.displayName} while held",
        event = ControlEvent.HELD,
    )
    val stopBinding = draft.copy(
        bindingId = uniqueBindingId(scheme.copy(bindings = scheme.bindings + runBinding), "${draft.bindingId}-release"),
        displayName = "Stop ${action.category.ifBlank { "output" }.lowercase()} on release",
        event = ControlEvent.RELEASE,
        target = draft.target.copy(arguments = draft.target.arguments + (outputParameter.key to "0")),
    )
    val updated = scheme.copy(bindings = scheme.bindings + runBinding + stopBinding)
    return current.replaceScheme(updated).copy(
        selectedBindingId = runBinding.bindingId,
        draftBinding = null,
        draftHasUnappliedChanges = false,
        dirty = true,
        dirtySchemeIds = current.dirtySchemeIds + updated.documentId,
        status = "Added a safe pair: hold to command output and release to command zero.",
    )
}

internal fun applyDraftBinding(current: ControlsEditorState): ControlsEditorState {
    val draft = current.draftBinding ?: return current
    if (current.problems.any { it.severity == ControlsProblemSeverity.ERROR && (it.bindingId == null || it.bindingId == draft.bindingId) }) {
        return current.copy(status = "Fix the highlighted binding errors before applying.")
    }
    val scheme = current.selectedScheme ?: return current
    val updated = if (current.selectedBindingId == null) {
        scheme.copy(bindings = scheme.bindings + draft)
    } else {
        scheme.copy(bindings = scheme.bindings.map { if (it.bindingId == current.selectedBindingId) draft else it })
    }
    return current.replaceScheme(updated).copy(
        selectedBindingId = draft.bindingId,
        draftBinding = null,
        dirty = true,
        dirtySchemeIds = current.dirtySchemeIds + updated.documentId,
        draftHasUnappliedChanges = false,
        status = "Binding applied locally. Save to create a project revision.",
    )
}

internal fun setDraftSourceKind(
    draft: ControlBindingDocument,
    kind: ControlSourceKind,
    selectedControlId: String?,
): ControlBindingDocument {
    val selected = selectedControlId ?: draft.source.controlIds.firstOrNull().orEmpty()
    val source = when (kind) {
        ControlSourceKind.BUTTON -> ControlSourceDocument(kind, draft.source.controllerSlot, listOf(selected))
        ControlSourceKind.CHORD -> ControlSourceDocument(kind, draft.source.controllerSlot, listOf(selected), chordWindowSeconds = .075)
        ControlSourceKind.AXIS_THRESHOLD -> ControlSourceDocument(
            kind, draft.source.controllerSlot, listOf(selected), AxisTransformDocument(), .65, .50
        )
        ControlSourceKind.AXIS_VALUE -> ControlSourceDocument(
            kind, draft.source.controllerSlot, listOf(selected), AxisTransformDocument()
        )
        ControlSourceKind.AXIS_ZONE -> ControlSourceDocument(
            kind, draft.source.controllerSlot, listOf(selected), AxisTransformDocument(),
            zoneMinimum = -.25, zoneMaximum = .25, zoneHysteresis = .05
        )
    }
    return draft.copy(
        source = source,
        event = defaultControlEvent(kind),
        suppressConstituentBindings = kind == ControlSourceKind.CHORD,
        analogPolicy = if (kind == ControlSourceKind.AXIS_VALUE || kind == ControlSourceKind.AXIS_ZONE) {
            AnalogControlPolicyDocument()
        } else null
    )
}

internal fun setDraftTarget(
    draft: ControlBindingDocument,
    actions: List<ActionDescriptor>,
    selectedControlDisplayName: String?,
    kind: ControlTargetKind,
    key: String,
): ControlBindingDocument {
    val action = actions.firstOrNull { it.key == key }
    val previousAction = actions.firstOrNull { it.key == draft.target.key }
    val defaultControlName = selectedControlDisplayName?.let { "$it binding" }
    return draft.copy(
        displayName = if (
            action != null &&
            (draft.displayName == defaultControlName || draft.displayName == previousAction?.displayName)
        ) {
            action.displayName
        } else {
            draft.displayName
        },
        target = ControlTargetDocument(
            kind = kind,
            key = key,
            arguments = if (kind == ControlTargetKind.ACTION && action != null) {
                initialCapabilityArguments(action.parameters)
            } else {
                emptyMap()
            },
            routinePolicy = draft.target.routinePolicy
        )
    )
}

internal fun momentaryOutputParameter(action: ActionDescriptor): CapabilityParameterDescriptor? =
    action.parameters.firstOrNull { parameter ->
        if (parameter.type != CapabilityParameterType.NUMBER) return@firstOrNull false
        val normalizedUnit = parameter.unit.orEmpty().trim().lowercase()
        normalizedUnit in setOf(
            "v",
            "volt",
            "volts",
            "%",
            "percent",
            "power",
            "duty cycle",
            "duty_cycle",
        )
    }

internal fun uniqueBindingId(scheme: ControlSchemeDocument, raw: String): String {
    val base = raw.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "binding" }
    var candidate = base
    var suffix = 2
    val used = scheme.bindings.mapTo(hashSetOf()) { it.bindingId }
    while (candidate in used) candidate = "$base-${suffix++}"
    return candidate
}
