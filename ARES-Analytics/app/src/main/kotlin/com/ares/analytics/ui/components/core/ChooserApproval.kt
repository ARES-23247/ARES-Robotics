package com.ares.analytics.ui.components.core

import java.io.File

internal sealed interface ChooserAction {
    data class Selected(val files: List<File>) : ChooserAction
    data class Overwrite(val file: File) : ChooserAction
    data class Created(val folder: File) : ChooserAction
}

/** File validation is called only by the state owner's I/O operation. */
internal fun approveChooserSelection(
    mode: AresFileChooserMode,
    directory: File,
    selected: List<File>,
    name: String,
    extensions: List<String>,
): ChooserAction = when (mode) {
    AresFileChooserMode.DIRECTORY -> {
        val target = selected.firstOrNull() ?: directory
        require(target.isDirectory) { "The selected folder is no longer available." }
        ChooserAction.Selected(listOf(target.canonicalFile))
    }
    AresFileChooserMode.OPEN_FILE, AresFileChooserMode.OPEN_FILES -> {
        require(selected.isNotEmpty() && selected.all { it.isFile }) { "Select an available file." }
        val files = if (mode == AresFileChooserMode.OPEN_FILE) selected.take(1) else selected
        ChooserAction.Selected(files.map { it.canonicalFile })
    }
    AresFileChooserMode.SAVE_FILE -> {
        require(name.isNotBlank()) { "Enter a file name." }
        val effectiveName = if (extensions.isNotEmpty() && extensions.none { name.endsWith(".$it", true) }) {
            "$name.${extensions.first()}"
        } else name
        val target = File(directory, effectiveName).canonicalFile
        validateChooserSaveTarget(target)
        if (target.exists()) ChooserAction.Overwrite(target) else ChooserAction.Selected(listOf(target))
    }
}

/** Recheck the same destination when approval follows an overwrite prompt. */
internal fun validateChooserSaveTarget(target: File) {
    require(!target.isDirectory) { "Select a file, not a folder." }
    require(target.parentFile?.isDirectory == true) { "The destination folder does not exist." }
}
