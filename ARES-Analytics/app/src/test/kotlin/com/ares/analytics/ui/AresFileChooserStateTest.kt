package com.ares.analytics.ui

import com.ares.analytics.ui.components.core.AresFileChooserMode
import com.ares.analytics.ui.components.core.AresFileChooserState
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class AresFileChooserStateTest {
    private fun withState(mode: AresFileChooserMode, block: (File, AresFileChooserState, MutableList<List<File>>) -> Unit) {
        val root = createTempDirectory("ares-chooser-state").toFile().canonicalFile
        val results = mutableListOf<List<File>>()
        try {
            val state = AresFileChooserState(mode, "Test", root, null, null, listOf("json"), null,
                { results.add(it) }, {})
            block(root, state, results)
        } finally { root.deleteRecursively() }
    }

    @Test fun refreshReloadsTheSameDirectory() = withState(AresFileChooserMode.OPEN_FILE) { root, state, _ ->
        assertTrue(state.directoryEntries.isEmpty())
        val added = File(root, "new.json").apply { writeText("{}") }
        state.refresh()
        assertEquals(listOf(added), state.directoryEntries)
        added.delete()
        state.refresh()
        assertTrue(state.directoryEntries.isEmpty())
    }

    @Test fun saveConfirmsOverwriteAfterAddingExtension() = withState(AresFileChooserMode.SAVE_FILE) { root, state, results ->
        val existing = File(root, "config.json").apply { writeText("original") }
        state.fileNameInput = "config"
        state.handleApprove()
        assertTrue(results.isEmpty())
        assertEquals(existing, state.pendingOverwrite)
        assertEquals("original", existing.readText())
        state.confirmOverwrite()
        assertEquals(listOf(listOf(existing)), results)
    }

    @Test fun saveRejectsDirectoriesAndMissingParents() = withState(AresFileChooserMode.SAVE_FILE) { root, state, results ->
        File(root, "folder.json").mkdir()
        state.fileNameInput = "folder"
        state.handleApprove()
        assertNotNull(state.errorText)
        state.fileNameInput = "missing/config"
        state.handleApprove()
        assertNotNull(state.errorText)
        assertTrue(results.isEmpty())
    }

    @Test fun newFolderReportsFailureAndNavigatesOnlyAfterCreation() = withState(AresFileChooserMode.DIRECTORY) { root, state, _ ->
        state.newFolderName = ".."
        state.createFolder()
        assertNotNull(state.errorText)
        assertEquals(root, state.currentDirectory)
        state.newFolderName = "new-folder"
        state.createFolder()
        assertEquals(File(root, "new-folder"), state.currentDirectory)
        assertTrue(state.currentDirectory.isDirectory)
    }
}
