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
    return files.map { file ->
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        ChooserEntry(file, attributes.isDirectory, attributes.lastModifiedTime().toMillis(), attributes.size(),
            if (attributes.isDirectory) detectRobotFlavor(file) else null)
    }
}
