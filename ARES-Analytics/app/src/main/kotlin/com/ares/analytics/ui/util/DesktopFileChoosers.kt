package com.ares.analytics.ui.util

import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Standardized native desktop file and directory choosers for ARES-Analytics.
 * Unifies Swing JFileChooser setup, filters, and canonical file normalization.
 */
internal object DesktopFileChoosers {

    fun chooseDirectory(
        dialogTitle: String = "Choose Directory",
        initialPath: String? = null,
        approveButtonText: String = "Select"
    ): File? {
        val initial = initialPath?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::exists)
        val chooser = JFileChooser(initial).apply {
            this.dialogTitle = dialogTitle
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            this.approveButtonText = approveButtonText
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.canonicalFile
        } else {
            null
        }
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
        val extArray = extensions.toTypedArray()
        val chooser = JFileChooser(initialDirectory).apply {
            this.dialogTitle = dialogTitle
            fileSelectionMode = JFileChooser.FILES_ONLY
            if (extArray.isNotEmpty() && filterDescription != null) {
                fileFilter = FileNameExtensionFilter(filterDescription, *extArray)
            }
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.canonicalFile
        } else {
            null
        }
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
        val extArray = extensions.toTypedArray()
        val chooser = JFileChooser(initialDirectory).apply {
            this.dialogTitle = dialogTitle
            fileSelectionMode = JFileChooser.FILES_ONLY
            if (defaultFileName != null) {
                selectedFile = File(defaultFileName)
            }
            if (extArray.isNotEmpty() && filterDescription != null) {
                fileFilter = FileNameExtensionFilter(filterDescription, *extArray)
            }
        }
        return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            ensureExtension(chooser.selectedFile, extensions)
        } else {
            null
        }
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
        val extArray = extensions.toTypedArray()
        val chooser = JFileChooser(initialDirectory).apply {
            this.dialogTitle = dialogTitle
            isMultiSelectionEnabled = true
            fileSelectionMode = JFileChooser.FILES_ONLY
            if (extArray.isNotEmpty() && filterDescription != null) {
                fileFilter = FileNameExtensionFilter(filterDescription, *extArray)
            }
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return emptyList()
        return chooser.selectedFiles?.map { it.canonicalFile }.orEmpty().ifEmpty {
            listOfNotNull(chooser.selectedFile?.canonicalFile)
        }
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
