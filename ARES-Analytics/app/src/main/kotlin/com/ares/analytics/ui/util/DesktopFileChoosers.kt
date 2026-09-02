package com.ares.analytics.ui.util

import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Standardized native desktop file and directory choosers for ARES-Analytics.
 * Unifies Swing JFileChooser setup, filters, and canonical file normalization.
 */
object DesktopFileChoosers {

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
        vararg extensions: String
    ): File? {
        val chooser = JFileChooser(initialDirectory).apply {
            this.dialogTitle = dialogTitle
            fileSelectionMode = JFileChooser.FILES_ONLY
            if (extensions.isNotEmpty() && filterDescription != null) {
                fileFilter = FileNameExtensionFilter(filterDescription, *extensions)
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
        vararg extensions: String
    ): File? {
        val chooser = JFileChooser(initialDirectory).apply {
            this.dialogTitle = dialogTitle
            fileSelectionMode = JFileChooser.FILES_ONLY
            if (defaultFileName != null) {
                selectedFile = File(defaultFileName)
            }
            if (extensions.isNotEmpty() && filterDescription != null) {
                fileFilter = FileNameExtensionFilter(filterDescription, *extensions)
            }
        }
        return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val selected = chooser.selectedFile.canonicalFile
            if (extensions.isNotEmpty() && extensions.none { ext -> selected.name.endsWith(".$ext", ignoreCase = true) }) {
                File(selected.parentFile, "${selected.name}.${extensions.first()}").canonicalFile
            } else {
                selected
            }
        } else {
            null
        }
    }

    fun chooseOpenFiles(
        dialogTitle: String = "Open Files",
        initialDirectory: File? = null,
        filterDescription: String? = null,
        vararg extensions: String
    ): List<File> {
        val chooser = JFileChooser(initialDirectory).apply {
            this.dialogTitle = dialogTitle
            isMultiSelectionEnabled = true
            fileSelectionMode = JFileChooser.FILES_ONLY
            if (extensions.isNotEmpty() && filterDescription != null) {
                fileFilter = FileNameExtensionFilter(filterDescription, *extensions)
            }
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return emptyList()
        return chooser.selectedFiles?.map { it.canonicalFile }.orEmpty().ifEmpty {
            listOfNotNull(chooser.selectedFile?.canonicalFile)
        }
    }
}

