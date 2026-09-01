package com.ares.analytics.ui.components.controls

import com.ares.analytics.ui.components.core.AresSpecRow
import com.ares.analytics.ui.components.core.AresSpecSection
import com.ares.analytics.viewmodel.controls.ControlsEditorState
import com.areslib.controls.ControlBindingDocument

/** Builds the read-only keymap summary separately from the interactive controller editor. */
internal fun generateKeymapSpecSections(
    state: ControlsEditorState,
    onEditBinding: (String) -> Unit,
): List<AresSpecSection> {
    val bindings = state.selectedScheme?.bindings.orEmpty()
    val driverBindings = bindings.filter { it.source.controllerSlot == "driver" }
    val operatorBindings = bindings.filter { it.source.controllerSlot == "operator" }
    val otherBindings = bindings.filter { it.source.controllerSlot != "driver" && it.source.controllerSlot != "operator" }

    val sections = mutableListOf(
        AresSpecSection(
            "Driver (Gamepad 1)",
            null,
            driverBindings.map { it.toSpecRow(onEditBinding) },
            "No bindings configured on Gamepad 1 (Driver).",
        ),
        AresSpecSection(
            "Operator (Gamepad 2)",
            null,
            operatorBindings.map { it.toSpecRow(onEditBinding) },
            "No bindings configured on Gamepad 2 (Operator).",
        ),
    )
    if (otherBindings.isNotEmpty()) {
        sections += AresSpecSection(
            "Other Controllers",
            null,
            otherBindings.map { binding ->
                binding.toSpecRow(
                    onEditBinding = onEditBinding,
                    secondaryLabel = "${binding.source.controllerSlot}: ${binding.source.controlIds.joinToString(" + ")}",
                    includeEnabled = false,
                )
            },
        )
    }
    return sections
}

internal fun generateKeymapMarkdown(state: ControlsEditorState): String = buildString {
    appendLine("# ARES TeleOp & Controls Keymap Spec")
    appendLine("Project: ${state.projectPath}")
    appendLine("League: ${state.league.name}")
    appendLine("Target Platform: ${state.targetPlatform.name}")
    appendLine()
    state.selectedScheme?.bindings.orEmpty().groupBy { it.source.controllerSlot }.forEach { (slot, bindings) ->
        appendLine("## Controller: ${slot.replaceFirstChar(Char::uppercase)}")
        appendLine("| Control | Event | Target Action | Kind | Enabled |")
        appendLine("|---|---|---|---|---|")
        bindings.forEach { binding ->
            appendLine(
                "| ${binding.source.controlIds.joinToString(" + ")} | ${binding.event.reportLabel()} | " +
                    "${binding.target.key} | ${binding.target.kind.name} | ${if (binding.enabled) "Yes" else "No"} |",
            )
        }
        appendLine()
    }
}

private fun ControlBindingDocument.toSpecRow(
    onEditBinding: (String) -> Unit,
    secondaryLabel: String = source.controlIds.joinToString(" + "),
    includeEnabled: Boolean = true,
): AresSpecRow = AresSpecRow(
    id = bindingId,
    primaryLabel = displayName,
    secondaryLabel = secondaryLabel,
    badge = event.name,
    columns = buildList {
        add("Event" to event.reportLabel())
        add("Target" to target.key)
        add("Kind" to target.kind.name)
        if (includeEnabled) add("Enabled" to if (enabled) "YES" else "NO")
    },
    onEditClick = { onEditBinding(bindingId) },
)

private fun Enum<*>.reportLabel(): String =
    name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
