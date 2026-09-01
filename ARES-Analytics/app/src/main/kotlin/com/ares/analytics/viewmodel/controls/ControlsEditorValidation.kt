package com.ares.analytics.viewmodel.controls

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.validateCapabilityArguments
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControlValidationContext
import com.areslib.controls.ControlValidationSeverity
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.validateControlScheme
import com.areslib.controls.validateControllerProfile

internal fun ControlsEditorState.withProblems(external: List<ControlsProblem>): ControlsEditorState {
    if (schemes.isEmpty()) return copy(problems = external)
    val profileControls = profiles.associate { profile ->
        profile.documentId to profile.controls.mapTo(linkedSetOf()) { it.controlId }
    }
    val context = ControlValidationContext(
        actionKeys = actions.mapTo(linkedSetOf()) { it.key },
        routineIds = routineIds.toSet(),
        profileControls = profileControls,
    )
    val shared = schemes.flatMap { scheme ->
        validateControlScheme(scheme, context).map { issue ->
            ControlsProblem(
                severity = issue.severity.toProblemSeverity(),
                message = "${scheme.name}: ${issue.message}",
                bindingId = controlBindingIdFromPath(issue.path, scheme),
            )
        }
    }
    val profileProblems = profiles.flatMap { profile ->
        validateControllerProfile(profile).map { issue ->
            ControlsProblem(
                severity = issue.severity.toProblemSeverity(),
                message = "${profile.displayName}: ${issue.message}",
            )
        }
    }
    val mappingProblems = schemes.flatMap { scheme ->
        val profileBySlot = scheme.controllers.associate { assignment ->
            assignment.slot to profiles.firstOrNull { profile -> profile.documentId == assignment.profileId }
        }
        scheme.bindings.filter { it.enabled }.flatMap { binding ->
            val profile = profileBySlot[binding.source.controllerSlot]
            binding.source.controlIds.mapNotNull { controlId ->
                val mapped = profile?.controls?.firstOrNull { it.controlId == controlId }
                    ?.mappings?.any { it.platform == targetPlatform } == true
                if (mapped) {
                    null
                } else {
                    ControlsProblem(
                        severity = ControlsProblemSeverity.ERROR,
                        message = "${scheme.name} / ${binding.displayName}: '$controlId' has no " +
                            "${targetPlatform.name} mapping. Desktop indexes are intentionally not reused.",
                        bindingId = binding.bindingId,
                    )
                }
            }
        }
    }
    val draftProblems = draftBinding?.let(::validateDraftBinding).orEmpty()
    return copy(problems = (external + shared + profileProblems + mappingProblems + draftProblems).distinct())
}

internal fun ControlsEditorState.revalidated(): ControlsEditorState = withProblems(projectProblems)

internal fun ControlsEditorState.validateDraftBinding(binding: ControlBindingDocument): List<ControlsProblem> {
    val scheme = selectedScheme ?: return emptyList()
    val temporary = scheme.copy(
        bindings = scheme.bindings.filterNot { it.bindingId == selectedBindingId } + binding,
    )
    val profileControls = profiles.associate { profile ->
        profile.documentId to profile.controls.mapTo(linkedSetOf()) { it.controlId }
    }
    val issues = validateControlScheme(
        temporary,
        ControlValidationContext(
            actionKeys = actions.mapTo(linkedSetOf()) { it.key },
            routineIds = routineIds.toSet(),
            profileControls = profileControls,
        ),
    ).filter { it.path.contains("bindings") }
        .mapTo(mutableListOf()) { issue ->
            ControlsProblem(
                severity = issue.severity.toProblemSeverity(),
                message = issue.message,
                bindingId = binding.bindingId,
            )
        }

    if (binding.target.kind == ControlTargetKind.ACTION) {
        val descriptor = actions.firstOrNull { it.key == binding.target.key }
        if (descriptor == null) {
            issues += ControlsProblem(
                ControlsProblemSeverity.ERROR,
                "Choose an action from the project catalog.",
                binding.bindingId,
            )
        } else {
            issues += validateArguments(descriptor, binding.target.arguments).map { message ->
                ControlsProblem(ControlsProblemSeverity.ERROR, message, binding.bindingId)
            }
        }
    }

    val profileId = scheme.controllers.firstOrNull { it.slot == binding.source.controllerSlot }?.profileId
    val profile = profiles.firstOrNull { it.documentId == profileId }
    val referencedControls = binding.source.controlIds.mapNotNull { id ->
        profile?.controls?.firstOrNull { it.controlId == id }
    }
    binding.source.controlIds.filter { controlId ->
        profile?.controls?.firstOrNull { it.controlId == controlId }
            ?.mappings?.none { it.platform == targetPlatform } != false
    }.forEach { controlId ->
        issues += ControlsProblem(
            ControlsProblemSeverity.ERROR,
            "'$controlId' needs a ${targetPlatform.name} mapping before this binding can be applied.",
            binding.bindingId,
        )
    }

    val needsAxis = binding.source.kind == ControlSourceKind.AXIS_THRESHOLD ||
        binding.source.kind == ControlSourceKind.AXIS_VALUE ||
        binding.source.kind == ControlSourceKind.AXIS_ZONE
    if (needsAxis && referencedControls.any { it.type != ControllerControlTypeDocument.AXIS }) {
        issues += ControlsProblem(ControlsProblemSeverity.ERROR, "Analog bindings require axis controls.", binding.bindingId)
    }
    if (!needsAxis && referencedControls.any { it.type != ControllerControlTypeDocument.BUTTON }) {
        issues += ControlsProblem(
            ControlsProblemSeverity.ERROR,
            "Button and chord bindings require button controls.",
            binding.bindingId,
        )
    }
    return issues
}

internal fun validateArguments(
    action: ActionDescriptor,
    values: Map<String, String>,
): List<String> = validateCapabilityArguments(action.parameters, values).map { "${it.message}." }

private fun ControlValidationSeverity.toProblemSeverity(): ControlsProblemSeverity =
    if (this == ControlValidationSeverity.ERROR) ControlsProblemSeverity.ERROR else ControlsProblemSeverity.WARNING
