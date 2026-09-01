package com.ares.analytics.viewmodel.controls

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.initialCapabilityArguments
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

internal fun uniqueBindingId(scheme: ControlSchemeDocument, raw: String): String {
    val base = raw.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "binding" }
    var candidate = base
    var suffix = 2
    val used = scheme.bindings.mapTo(hashSetOf()) { it.bindingId }
    while (candidate in used) candidate = "$base-${suffix++}"
    return candidate
}
