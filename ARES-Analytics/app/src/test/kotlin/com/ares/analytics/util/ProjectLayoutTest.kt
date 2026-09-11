// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.util

import com.ares.analytics.shared.models.League
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProjectLayoutTest {
    @get:Rule val folder = TemporaryFolder()
    private val temporary get() = folder.root.toPath()

    @Test
    fun `project source probes respect league language and source roots`() {
        for (league in League.entries) {
            val root = temporary.resolve(league.name).toFile().apply { mkdirs() }
            val source = root.resolve(if (league == League.XRP) "extensions" else "src/main/kotlin").apply { mkdirs() }
            val wrong = source.resolve(if (league == League.XRP) "Robot.kt" else "robot.py").apply { writeText("# placeholder") }
            assertNotNull(ProjectLayout.validationError(root.path, league))
            wrong.delete()
            source.resolve(if (league == League.XRP) "robot.py" else "Robot.kt").writeText("source placeholder")
            assertNull(ProjectLayout.validationError(root.path, league))
        }
        assertNotNull(ProjectLayout.validationError("", League.FTC))
        assertNotNull(ProjectLayout.validationError(temporary.resolve("missing").toString(), League.FRC))
        val ftc = temporary.resolve("android").toFile().apply { mkdirs() }
        ftc.resolve("TeamCode/src/main/java").apply { mkdirs() }.resolve("Robot.java").writeText("class Robot {}")
        assertNull(ProjectLayout.validationError(ftc.path, League.FTC))
    }

    @Test
    fun `asset roots and field documents are consistent for each supported layout`() {
        val root = temporary.toFile()
        assertEquals(root.resolve("src/main/assets"), ProjectLayout.assetsDirectory(root.path, League.FTC))
        root.resolve("TeamCode/src/main/assets").mkdirs()
        assertEquals(root.resolve("TeamCode/src/main/assets"), ProjectLayout.assetsDirectory(root.path, League.FTC))
        assertEquals(root.resolve("src/main/deploy"), ProjectLayout.assetsDirectory(root.path, League.FRC))
        assertEquals(root.resolve("deploy"), ProjectLayout.assetsDirectory(root.path, League.XRP))
        for (league in League.entries)
            assertEquals(ProjectLayout.assetsDirectory(root.path, league).resolve("paths/field.json"),
                ProjectLayout.fieldDefinitionFile(root.path, league))
    }

    @Test
    fun `image resolution keeps normalized relative paths inside the asset root`() {
        for (league in League.entries) {
            val root = temporary.resolve(league.name).toFile()
            val assets = ProjectLayout.assetsDirectory(root.path, league).canonicalFile
            assertEquals(assets.resolve("field_image.png"), ProjectLayout.fieldImageFile(root.path, league, "  "))
            assertEquals(assets.resolve("image.png"), ProjectLayout.fieldImageFile(root.path, league, " maps/../image.png "))
            assertFailsWith<IllegalArgumentException> { ProjectLayout.fieldImageFile(root.path, league, "../outside.png") }
            assertFailsWith<IllegalArgumentException> { ProjectLayout.fieldImageFile(root.path, league, temporary.resolve("absolute.png").toString()) }
        }
    }
}
