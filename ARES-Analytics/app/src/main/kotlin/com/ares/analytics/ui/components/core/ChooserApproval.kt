package com.ares.analytics.ui.components.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

internal data class ChooserEntry(
    val file: File,
    val directory: Boolean,
    val modified: Long,
    val size: Long,
    val flavor: RobotProjectFlavor?,
)

internal fun readChooserDirectory(directory: File): List<ChooserEntry> {
    val files = directory.listFiles() ?: error("This folder could not be read: $directory")
    return files.mapNotNull { file ->
        runCatching {
            val path = file.toPath()
            val attributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } catch (e: Exception) {
                Files.readAttributes(path, BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            }
            ChooserEntry(
                file = file,
                directory = attributes.isDirectory,
                modified = attributes.lastModifiedTime().toMillis(),
                size = attributes.size(),
                flavor = if (attributes.isDirectory) detectRobotFlavor(file) else null,
            )
        }.getOrElse {
            if (file.exists()) {
                ChooserEntry(file, file.isDirectory, file.lastModified(), file.length(), null)
            } else null
        }
    }
}

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
        val requested = File(directory, effectiveName).absoluteFile.toPath().normalize()
        require(requested.startsWith(directory.absoluteFile.toPath().normalize())) {
            "Cannot save outside the current folder."
        }
        // Preserve an explicitly selected symlink; the canonical target is shown for overwrite approval.
        val target = requested.toFile().canonicalFile
        validateChooserSaveTarget(target)
        if (target.exists()) ChooserAction.Overwrite(target) else ChooserAction.Selected(listOf(target))
    }
}

/** Recheck the same destination when approval follows an overwrite prompt. */
internal fun validateChooserSaveTarget(target: File) {
    require(!target.isDirectory) { "Select a file, not a folder." }
    require(target.parentFile?.isDirectory == true) { "The destination folder does not exist." }
}
