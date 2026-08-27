package com.ares.analytics.ui.help

import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.viewmodel.controls.ControlsEditorState
import com.ares.analytics.viewmodel.controls.ControlsProblemSeverity
import com.areslib.catalog.CapabilityContext
import com.areslib.controls.ControlTargetKind

private fun String.isGeneratedSubsystemTargetAction(): Boolean =
    startsWith("subsystem.") && contains(".set.")

/** Maps real catalog, mapping, binding, save, and generation state to Academy facts. */
fun ControlsEditorState.toAcademyControlsSnapshot(): AcademyControlsSnapshot {
    val scheme = selectedScheme
    if (loadError != null || scheme == null || selectedProfile == null) {
        return AcademyControlsSnapshot.Unavailable
    }
    val generatedSubsystemActions = actions.filter { action ->
        action.key.isGeneratedSubsystemTargetAction() && CapabilityContext.TELEOP in action.allowedContexts
    }
    val generatedKeys = generatedSubsystemActions.mapTo(linkedSetOf()) { it.key }
    val selectedMapping = selectedControl?.mappings?.firstOrNull { it.platform == targetPlatform }
    val validBindings = scheme.bindings.filter { binding ->
        binding.enabled && binding.target.kind == ControlTargetKind.ACTION && binding.target.key in generatedKeys &&
            problems.none { it.severity == ControlsProblemSeverity.ERROR && it.bindingId == binding.bindingId }
    }
    val hasValidAppliedBinding = validBindings.isNotEmpty()
    val hasSavedBinding = hasValidAppliedBinding && !dirty && !draftHasUnappliedChanges

    return AcademyControlsSnapshot(
        isAvailable = true,
        hasActionCatalog = actions.isNotEmpty(),
        hasGeneratedSubsystemCapability = generatedSubsystemActions.isNotEmpty(),
        hasMappedControlSelection = selectedMapping != null,
        hasValidAppliedBinding = hasValidAppliedBinding,
        hasSavedControlScheme = hasSavedBinding,
        hasGeneratedBindings = hasSavedBinding && generationPhase == AresGenerationPhase.SUCCEEDED &&
            !generatedContentHash.isNullOrBlank(),
    )
}
