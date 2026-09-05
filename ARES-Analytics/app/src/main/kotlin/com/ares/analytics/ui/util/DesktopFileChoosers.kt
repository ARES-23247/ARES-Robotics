package com.ares.analytics.ui.util

import com.ares.analytics.ui.components.core.AresFileChooserLauncher
import com.ares.analytics.ui.components.core.AresFileChooserMode
import java.io.File

/**
 * Standardized native desktop file and directory choosers for ARES-Analytics.
 * Uses the modern ARES Compose File and Directory Chooser with seamless theme matching.
 */
internal object DesktopFileChoosers {

    fun chooseDirectory(
        dialogTitle: String = "Choose Directory",
        initialPath: String? = null,
        approveButtonText: String = "Select"
    ): File? {
        val initial = initialPath?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::exists)
        return AresFileChooserLauncher.show(
            mode = AresFileChooserMode.DIRECTORY,
            dialogTitle = dialogTitle,
            initialDirectory = initial,
            approveButtonText = approveButtonText,
        )?.firstOrNull()?.canonicalFile
    }

    fun chooseOpenFile(
        dialogTitle: String = "Open File",
        initialDirectory: File? = null,
        filterDescription: String? = null,
        extensions: List<String> = emptyList()
    ): File? = chooseOpenFileInternal(
        dialogTitle = dialogTitle,
        initialDirectory = initialDirectory,
        filterDescription = filterDescription,
        extensions = extensions
    )

    private fun chooseOpenFileInternal(
        dialogTitle: String,
        initialDirectory: File?,
        filterDescription: String?,
        extensions: List<String>
    ): File? {
        return AresFileChooserLauncher.show(
            mode = AresFileChooserMode.OPEN_FILE,
            dialogTitle = dialogTitle,
            initialDirectory = initialDirectory,
            filterDescription = filterDescription,
            extensions = extensions,
        )?.firstOrNull()?.canonicalFile
    }

    fun chooseSaveFile(
        dialogTitle: String = "Save File",
        defaultFileName: String? = null,
        initialDirectory: File? = null,
        filterDescription: String? = null,
        extensions: List<String> = emptyList()
    ): File? = chooseSaveFileInternal(
        dialogTitle = dialogTitle,
        defaultFileName = defaultFileName,
        initialDirectory = initialDirectory,
        filterDescription = filterDescription,
        extensions = extensions
    )

    private fun chooseSaveFileInternal(
        dialogTitle: String,
        defaultFileName: String?,
        initialDirectory: File?,
        filterDescription: String?,
        extensions: List<String>
    ): File? {
        val files = AresFileChooserLauncher.show(
            mode = AresFileChooserMode.SAVE_FILE,
            dialogTitle = dialogTitle,
            initialDirectory = initialDirectory,
            defaultFileName = defaultFileName,
            filterDescription = filterDescription,
            extensions = extensions,
        )
        return files?.firstOrNull()?.let { ensureExtension(it, extensions) }
    }

    fun chooseOpenFiles(
        dialogTitle: String = "Open Files",
        initialDirectory: File? = null,
        filterDescription: String? = null,
        extensions: List<String> = emptyList()
    ): List<File> = chooseOpenFilesInternal(
        dialogTitle = dialogTitle,
        initialDirectory = initialDirectory,
        filterDescription = filterDescription,
        extensions = extensions
    )

    private fun chooseOpenFilesInternal(
        dialogTitle: String,
        initialDirectory: File?,
        filterDescription: String?,
        extensions: List<String>
    ): List<File> {
        return AresFileChooserLauncher.show(
            mode = AresFileChooserMode.OPEN_FILES,
            dialogTitle = dialogTitle,
            initialDirectory = initialDirectory,
            filterDescription = filterDescription,
            extensions = extensions,
        )?.map { it.canonicalFile }.orEmpty()
    }

    internal fun ensureExtension(selectedFile: File, extensions: List<String>): File {
        val selected = selectedFile.canonicalFile
        val normalizedExtensions = extensions.map { it.trim().removePrefix(".") }.filter(String::isNotEmpty)
        return if (
            normalizedExtensions.isEmpty() ||
            normalizedExtensions.any { extension -> selected.name.endsWith(".$extension", ignoreCase = true) }
        ) {
            selected
        } else {
            File(selected.parentFile, "${selected.name}.${normalizedExtensions.first()}").canonicalFile
        }
    }
}

