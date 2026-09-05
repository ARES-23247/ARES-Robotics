package com.ares.analytics.ui.components.core

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ares.analytics.ui.theme.*
import java.io.File
import java.util.*

internal class AresFileChooserState(
    val mode: AresFileChooserMode,
    val dialogTitle: String,
    initialDirectory: File?,
    val defaultFileName: String?,
    val filterDescription: String?,
    extensions: List<String>,
    approveButtonText: String?,
    val onConfirm: (List<File>) -> Unit,
    val onCancel: () -> Unit,
) {
    val normalizedExtensions = run {
        extensions.map { it.trim().removePrefix(".").lowercase() }.filter(String::isNotEmpty)
    }

    val userHome = File(System.getProperty("user.home")).canonicalFile
    val initialDir = run {
        val target = initialDirectory?.takeIf(File::exists)?.canonicalFile
            ?: initialDirectory?.parentFile?.takeIf(File::exists)?.canonicalFile
            ?: File(userHome, "Documents").takeIf(File::exists)
            ?: userHome
        if (target.isDirectory) target else target.parentFile ?: userHome
    }

    var currentDirectory by mutableStateOf(initialDir)
    var history by mutableStateOf(listOf(initialDir))
    var historyIndex by mutableStateOf(0)

    var selectedFiles by mutableStateOf<Set<File>>(emptySet())
    var fileNameInput by mutableStateOf(defaultFileName ?: "")
    var searchQuery by mutableStateOf("")
    var isEditingPath by mutableStateOf(false)
    var pathEditText by mutableStateOf(currentDirectory.absolutePath)

    var sortColumn by mutableStateOf(FileSortColumn.NAME)
    var sortAscending by mutableStateOf(true)

    var showNewFolderDialog by mutableStateOf(false)
    var newFolderName by mutableStateOf("New Folder")

    var refreshVersion by mutableStateOf(0)

    fun refresh() { refreshVersion++ }

    fun navigateTo(dir: File) {
        val canonical = dir.canonicalFile
        if (!canonical.exists() || !canonical.isDirectory) return
        refresh()
        currentDirectory = canonical
        pathEditText = canonical.absolutePath
        selectedFiles = emptySet()
        val newHistory = history.take(historyIndex + 1) + canonical
        history = newHistory
        historyIndex = newHistory.lastIndex
    }

    fun navigateBack() {
        if (historyIndex > 0) {
            historyIndex--
            currentDirectory = history[historyIndex]
            pathEditText = currentDirectory.absolutePath
            selectedFiles = emptySet()
        }
    }

    fun navigateForward() {
        if (historyIndex < history.lastIndex) {
            historyIndex++
            currentDirectory = history[historyIndex]
            pathEditText = currentDirectory.absolutePath
            selectedFiles = emptySet()
        }
    }

    fun navigateUp() {
        currentDirectory.parentFile?.let(::navigateTo)
    }

    // Refresh file entries for currentDirectory
    val directoryEntries by derivedStateOf {
        refreshVersion
        val all = runCatching { currentDirectory.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
        all.filter { file ->
            if (file.isDirectory) {
                true
            } else when (mode) {
                AresFileChooserMode.DIRECTORY -> false
                else -> {
                    if (normalizedExtensions.isEmpty()) true
                    else normalizedExtensions.any { ext -> file.name.endsWith(".$ext", ignoreCase = true) }
                }
            }
        }
    }

    val filteredEntries by derivedStateOf {
        val query = searchQuery.trim().lowercase()
        val searched = if (query.isEmpty()) {
            directoryEntries
        } else {
            directoryEntries.filter { it.name.lowercase().contains(query) }
        }

        val comparator = Comparator<File> { a, b ->
            if (a.isDirectory != b.isDirectory) {
                if (a.isDirectory) -1 else 1
            } else {
                val comp = when (sortColumn) {
                    FileSortColumn.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                    FileSortColumn.TYPE -> {
                        val extA = a.extension.lowercase()
                        val extB = b.extension.lowercase()
                        extA.compareTo(extB)
                    }
                    FileSortColumn.DATE_MODIFIED -> a.lastModified().compareTo(b.lastModified())
                    FileSortColumn.SIZE -> a.length().compareTo(b.length())
                }
                if (sortAscending) comp else -comp
            }
        }
        searched.sortedWith(comparator)
    }

    val effectiveApproveText = approveButtonText ?: when (mode) {
        AresFileChooserMode.DIRECTORY -> "Select Folder"
        AresFileChooserMode.SAVE_FILE -> "Save"
        AresFileChooserMode.OPEN_FILE, AresFileChooserMode.OPEN_FILES -> "Open"
    }

    var pendingOverwrite by mutableStateOf<File?>(null)
    var errorText by mutableStateOf<String?>(null)

    fun createFolder() {
        runCatching {
            val name = newFolderName.trim()
            require(name.isNotEmpty() && name !in setOf(".", "..") && '/' !in name && '\\' !in name) {
                "Enter a single folder name."
            }
            val folder = File(currentDirectory, name)
            check(folder.mkdir()) { "Could not create folder; it may already exist or be read-only." }
            navigateTo(folder)
            showNewFolderDialog = false
            newFolderName = "New Folder"
        }.onFailure { errorText = it.message ?: "Could not create folder." }
    }

    fun confirmOverwrite() {
        val target = pendingOverwrite ?: return
        pendingOverwrite = null
        if (target.isDirectory) errorText = "Select a file, not a folder."
        else onConfirm(listOf(target))
    }

    fun handleApprove() {
        errorText = null
        runCatching { approveSelection() }.onFailure { errorText = it.message ?: "Could not select this path." }
    }

    private fun approveSelection() {
        when (mode) {
            AresFileChooserMode.DIRECTORY -> {
                val target = selectedFiles.firstOrNull()?.takeIf(File::isDirectory) ?: currentDirectory
                onConfirm(listOf(target.canonicalFile))
            }
            AresFileChooserMode.OPEN_FILE -> {
                val target = selectedFiles.firstOrNull { it.isFile }
                if (target != null) onConfirm(listOf(target.canonicalFile))
            }
            AresFileChooserMode.OPEN_FILES -> {
                val targets = selectedFiles.filter(File::isFile).map(File::getCanonicalFile)
                if (targets.isNotEmpty()) onConfirm(targets)
            }
            AresFileChooserMode.SAVE_FILE -> {
                val name = fileNameInput.trim()
                if (name.isNotEmpty()) {
                    var targetFile = File(currentDirectory, name).canonicalFile
                    if (normalizedExtensions.isNotEmpty() && normalizedExtensions.none { targetFile.name.endsWith(".$it", ignoreCase = true) }) {
                        targetFile = File(currentDirectory, "$name.${normalizedExtensions.first()}").canonicalFile
                    }
                    require(!targetFile.isDirectory) { "Select a file, not a folder." }
                    require(targetFile.parentFile?.isDirectory == true) { "The destination folder does not exist." }
                    if (targetFile.exists()) pendingOverwrite = targetFile
                    else onConfirm(listOf(targetFile))
                }
            }
        }
    }

}
