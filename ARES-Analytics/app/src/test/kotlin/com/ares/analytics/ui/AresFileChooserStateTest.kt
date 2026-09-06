package com.ares.analytics.ui

import com.ares.analytics.ui.components.core.AresFileChooserMode
import com.ares.analytics.ui.components.core.AresFileChooserState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class AresFileChooserStateTest {
    private fun withState(mode: AresFileChooserMode, block: suspend (File, AresFileChooserState, MutableList<List<File>>) -> Unit) = runBlocking { withContext(Dispatchers.Main) {
        val root = createTempDirectory("ares-chooser-state").toFile().canonicalFile
        val results = mutableListOf<List<File>>()
        try {
            val state = AresFileChooserState(mode, "Test", root, null, null, listOf("json"), null,
                { results.add(it) }, {})
            try {
                state.awaitIdle()
                block(root, state, results)
            } finally { state.close() }
        } finally { root.deleteRecursively() }
    } }

    @Test fun refreshReloadsTheSameDirectory() = withState(AresFileChooserMode.OPEN_FILE) { root, state, _ ->
        assertTrue(state.directoryEntries.isEmpty())
        val added = File(root, "new.json").apply { writeText("{}") }
        state.refresh()
        state.awaitIdle()
        assertEquals(listOf(added), state.directoryEntries)
        added.delete()
        state.refresh()
        state.awaitIdle()
        assertTrue(state.directoryEntries.isEmpty())
    }

    @Test fun keyboardSelectionUsesTheChosenFileAndRequestsOverwrite() = withState(AresFileChooserMode.SAVE_FILE) { root, state, results ->
        val existing = File(root, "chosen.json").apply { writeText("original") }
        state.refresh()
        state.awaitIdle()
        state.moveSelection(1)
        assertEquals("chosen.json", state.fileNameInput)
        state.activateSelection()
        state.awaitIdle()
        assertEquals(existing, state.pendingOverwrite)
        assertTrue(results.isEmpty())
        assertEquals("original", existing.readText())
    }

    @Test fun saveConfirmsOverwriteAfterAddingExtension() = withState(AresFileChooserMode.SAVE_FILE) { root, state, results ->
        val existing = File(root, "config.json").apply { writeText("original") }
        state.fileNameInput = "config"
        state.handleApprove()
        state.awaitIdle()
        assertTrue(results.isEmpty())
        assertEquals(existing, state.pendingOverwrite)
        assertEquals("original", existing.readText())
        state.confirmOverwrite()
        state.awaitIdle()
        assertEquals(listOf(listOf(existing)), results)
    }

    @Test fun saveRejectsDirectoriesAndMissingParents() = withState(AresFileChooserMode.SAVE_FILE) { root, state, results ->
        File(root, "folder.json").mkdir()
        state.fileNameInput = "folder"
        state.handleApprove()
        state.awaitIdle()
        assertNotNull(state.errorText)
        state.fileNameInput = "missing/config"
        state.handleApprove()
        state.awaitIdle()
        assertNotNull(state.errorText)
        assertTrue(results.isEmpty())
    }

    @Test fun newFolderReportsFailureAndNavigatesOnlyAfterCreation() = withState(AresFileChooserMode.DIRECTORY) { root, state, _ ->
        state.newFolderName = ".."
        state.createFolder()
        state.awaitIdle()
        assertNotNull(state.errorText)
        assertEquals(root, state.currentDirectory)
        state.newFolderName = "new-folder"
        state.createFolder()
        state.awaitIdle()
        assertEquals(File(root, "new-folder"), state.currentDirectory)
        assertTrue(state.currentDirectory.isDirectory)
    }
}
