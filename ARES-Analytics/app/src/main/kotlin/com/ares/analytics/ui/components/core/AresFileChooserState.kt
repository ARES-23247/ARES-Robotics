package com.ares.analytics.ui.components.core

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ares.analytics.ui.theme.*
import java.io.File
import java.util.*
import kotlinx.coroutines.*

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val directoryReader: (File) -> List<ChooserEntry> = ::readChooserDirectory,
) {
    val normalizedExtensions = run {
        extensions.map { it.trim().removePrefix(".").lowercase() }.filter(String::isNotEmpty)
    }

    val userHome = File(System.getProperty("user.home")).absoluteFile
    val initialDir = initialDirectory?.absoluteFile ?: userHome
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null
    private var requestId = 0L
    var loading by mutableStateOf(false)
        private set
    var listingError by mutableStateOf<String?>(null)
        private set
    var entries by mutableStateOf<List<ChooserEntry>>(emptyList())
        private set
    var quickAccess by mutableStateOf<Set<File>>(emptySet())
        private set
    var roots by mutableStateOf<List<File>>(emptyList())
        private set

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

    fun refresh() = requestDirectory(currentDirectory, recordHistory = false)

    fun navigateTo(dir: File) = requestDirectory(dir)

    private fun requestDirectory(dir: File, recordHistory: Boolean = true, historyTarget: Int? = null) {
        if (busy) return
        val request = ++requestId
        operation?.cancel()
        loading = true
        listingError = null
        selectedFiles = emptySet()
        operation = scope.launch {
            try {
                val result = withContext(ioDispatcher) {
                    val target = dir.canonicalFile
                    val directory = if (target.isFile) target.parentFile else target
                    require(directory.isDirectory) { "The folder does not exist or is unavailable: $directory" }
                    val read = directoryReader(directory)
                    val shortcuts = listOf("Documents", "Downloads", "Desktop", "Documents/ARES", "Robots")
                        .map { File(userHome, it) }.filter { it.isDirectory }.toSet()
                    Triple(directory, read, shortcuts to File.listRoots().orEmpty().toList())
                }
                if (request != requestId) return@launch
                currentDirectory = result.first
                pathEditText = result.first.absolutePath
                entries = result.second
                quickAccess = result.third.first
                roots = result.third.second
                if (historyTarget != null) historyIndex = historyTarget
                else if (recordHistory) {
                    history = history.take(historyIndex + 1) + currentDirectory
                    historyIndex = history.lastIndex
                } else if (history.size == 1) history = listOf(currentDirectory)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == requestId) {
                    entries = emptyList()
                    listingError = error.message ?: "Could not read this folder."
                }
            } finally {
                if (request == requestId) loading = false
            }
        }
    }

    fun navigateBack() {
        if (historyIndex > 0) requestDirectory(history[historyIndex - 1], false, historyIndex - 1)
    }

    fun navigateForward() {
        if (historyIndex < history.lastIndex) requestDirectory(history[historyIndex + 1], false, historyIndex + 1)
    }

    fun navigateUp() { currentDirectory.parentFile?.let(::navigateTo) }
    fun goToEditedPath() { navigateTo(File(pathEditText.trim())); isEditingPath = false }
    fun metadata(file: File): ChooserEntry? = entries.firstOrNull { it.file == file }
    fun isDirectory(file: File): Boolean = metadata(file)?.directory == true

    val directoryEntries: List<File> get() = entries.filter { entry ->
        entry.directory || (mode != AresFileChooserMode.DIRECTORY &&
            (normalizedExtensions.isEmpty() || normalizedExtensions.any { entry.file.name.endsWith(".$it", true) }))
    }.map { it.file }

    val filteredEntries: List<File> get() {
        val query = searchQuery.trim()
        val visible = directoryEntries.toSet()
        return entries.filter { it.file in visible && it.file.name.contains(query, true) }
            .sortedWith { a, b ->
                if (a.directory != b.directory) if (a.directory) -1 else 1
                else {
                    val comparison = when (sortColumn) {
                        FileSortColumn.NAME -> a.file.name.compareTo(b.file.name, true)
                        FileSortColumn.TYPE -> a.file.extension.compareTo(b.file.extension, true)
                        FileSortColumn.DATE_MODIFIED -> a.modified.compareTo(b.modified)
                        FileSortColumn.SIZE -> a.size.compareTo(b.size)
                    }
                    if (sortAscending) comparison else -comparison
                }
            }.map { it.file }
    }

    fun moveSelection(offset: Int) {
        val files = filteredEntries
        if (files.isEmpty()) return
        val index = files.indexOf(selectedFiles.firstOrNull())
        val selected = files[(if (index < 0) 0 else index + offset).coerceIn(0, files.lastIndex)]
        selectedFiles = setOf(selected)
        if (mode == AresFileChooserMode.SAVE_FILE && !isDirectory(selected)) fileNameInput = selected.name
    }

    fun activateSelection() {
        val selected = selectedFiles.firstOrNull()
        if (selected != null && isDirectory(selected)) navigateTo(selected)
        else handleApprove()
    }

    suspend fun awaitIdle() {
        do { val pending = operation; pending?.join() } while (pending !== operation)
    }
    fun close() { ++requestId; scope.cancel() }

    val effectiveApproveText = approveButtonText ?: when (mode) {
        AresFileChooserMode.DIRECTORY -> "Select Folder"
        AresFileChooserMode.SAVE_FILE -> "Save"
        AresFileChooserMode.OPEN_FILE, AresFileChooserMode.OPEN_FILES -> "Open"
    }

    var pendingOverwrite by mutableStateOf<File?>(null)
    var errorText by mutableStateOf<String?>(null)

    var busy by mutableStateOf(false)
        private set

    private fun fileAction(work: () -> ChooserAction) {
        if (loading || busy) return
        busy = true
        errorText = null
        operation = scope.launch {
            try {
                val result = withContext(ioDispatcher) { work() }
                busy = false
                when (result) {
                    is ChooserAction.Selected -> onConfirm(result.files)
                    is ChooserAction.Overwrite -> pendingOverwrite = result.file
                    is ChooserAction.Created -> {
                        showNewFolderDialog = false
                        newFolderName = "New Folder"
                        navigateTo(result.folder)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorText = error.message ?: "Could not use this path."
            } finally { busy = false }
        }
    }

    init { refresh() }

    fun createFolder() {
        val directory = currentDirectory
        val name = newFolderName.trim()
        fileAction {
            require(name.isNotEmpty() && name !in setOf(".", "..") && '/' !in name && '\\' !in name) {
                "Enter a single folder name."
            }
            val folder = File(directory, name)
            check(folder.mkdir()) { "Could not create folder; it may already exist or be read-only." }
            ChooserAction.Created(folder.canonicalFile)
        }
    }

    fun confirmOverwrite() {
        val target = pendingOverwrite ?: return
        pendingOverwrite = null
        fileAction {
            require(!target.isDirectory) { "Select a file, not a folder." }
            ChooserAction.Selected(listOf(target.canonicalFile))
        }
    }

    fun handleApprove() {
        val directory = currentDirectory
        val selected = selectedFiles.toList()
        val name = fileNameInput.trim()
        fileAction { approveChooserSelection(mode, directory, selected, name, normalizedExtensions) }
    }
}
