// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.project.InitialFieldPresetInstaller
import com.ares.analytics.service.project.persistence.FieldDocumentStore
import com.ares.analytics.shared.models.League
import com.ares.analytics.util.ProjectLayout
import com.areslib.state.RobotFieldAprilTag
import com.areslib.state.RobotFieldDocument
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FieldDocumentLeagueTest {
    @Test
    fun `cross league load and save reject mismatches without changing document or history`() {
        val root = Files.createTempDirectory("field-league-audit").toFile()
        try {
            for (league in League.entries) for (other in League.entries.filter { it != league }) {
                val project = root.resolve("${league.name}-${other.name}").apply { mkdirs() }
                val valid = FieldDocumentMapper.newDocument(league)
                FieldDocumentStore.save(project.path, league, valid)
                val file = ProjectLayout.fieldDefinitionFile(project.path, league)
                val before = file.readText()
                val history = project.resolve(".ares/history/fields")
                val historyBefore = history.listFiles().orEmpty().associate { it.name to it.readText() }
                val wrong = FieldDocumentMapper.newDocument(other)
                assertFailsWith<IllegalArgumentException> { FieldDocumentStore.save(project.path, league, wrong) }
                assertEquals(before, file.readText())
                assertEquals(historyBefore, history.listFiles().orEmpty().associate { it.name to it.readText() })
                file.writeText(RobotFieldDocument.encode(wrong))
                assertFailsWith<IllegalArgumentException> { FieldDocumentStore.load(project.path, league) }
                assertFailsWith<IllegalArgumentException> { FieldDocumentStore.save(project.path, league, valid) }
                assertEquals(RobotFieldDocument.encode(wrong), file.readText())
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `absent fields default to the requested league without writing files`() {
        val root = Files.createTempDirectory("field-league-default").toFile()
        try {
            for (league in League.entries) {
                assertEquals(league.name, FieldDocumentStore.load(root.path, league).document.fieldType.name)
                assertFalse(ProjectLayout.fieldDefinitionFile(root.path, league).exists())
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `preset installation rejects a starter and preset that agree on the wrong league`() {
        val root = Files.createTempDirectory("field-preset-league").toFile()
        try {
            val file = ProjectLayout.fieldDefinitionFile(root.path, League.FTC)
            file.parentFile.mkdirs()
            val wrong = FieldDocumentMapper.newDocument(League.FRC)
            val original = RobotFieldDocument.encode(wrong)
            file.writeText(original)
            val preset = RobotFieldDocument.encode(wrong.copy(apriltags = listOf(RobotFieldAprilTag(id = 1))))
            assertFailsWith<IllegalStateException> {
                InitialFieldPresetInstaller.install(root, League.FTC, "Robot", "preset") { preset.byteInputStream() }
            }
            assertEquals(original, file.readText())
        } finally { root.deleteRecursively() }
    }
}
