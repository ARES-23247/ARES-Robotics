// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.util

import com.ares.analytics.shared.models.League
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectLayoutTest {
    @get:Rule val folder = TemporaryFolder()
    private val temporary get() = folder.root.toPath()

    @Test(timeout = 10000)
    fun `linked source directories remain usable and ancestor cycles terminate`() {
        val project = Files.createDirectories(temporary.resolve("project"))
        val externalSource = Files.createDirectories(temporary.resolve("source"))
        val sourceParent = Files.createDirectories(project.resolve("src/main"))
        val linkedSource = sourceParent.resolve("kotlin")
        val cycle = externalSource.resolve("cycle")
        linkDirectory(linkedSource, externalSource)
        try {
            linkDirectory(cycle, externalSource)
            try {
                assertFalse(ProjectLayout.containsRobotSource(project.toFile(), League.FRC))
                Files.writeString(externalSource.resolve("Robot.kt"), "class Robot")
                assertTrue(ProjectLayout.containsRobotSource(project.toFile(), League.FRC))
            } finally {
                Files.deleteIfExists(cycle)
            }
        } finally {
            Files.deleteIfExists(linkedSource)
        }
    }

    @Test(timeout = 10000)
    fun `XRP overlapping roots and links still find ordinary project Python`() {
        val root = Files.createDirectories(temporary.resolve("xrp"))
        val generated = Files.createDirectories(root.resolve("build/generated/ares/python"))
        val extensions = root.resolve("extensions")
        linkDirectory(extensions, generated)
        try {
            assertFalse(ProjectLayout.containsRobotSource(root.toFile(), League.XRP))
            Files.writeString(root.resolve("main.py"), "pass")
            assertTrue(ProjectLayout.containsRobotSource(root.toFile(), League.XRP))
        } finally {
            Files.deleteIfExists(extensions)
        }
    }

    private fun linkDirectory(link: Path, target: Path) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Junction creation timed out")
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
        } else {
            Files.createSymbolicLink(link, target)
        }
    }

    @Test(timeout = 10000)
    fun `image containment rejects a linked directory escaping assets`() {
        val root = temporary.resolve("project")
        val assets = Files.createDirectories(root.resolve("deploy"))
        val outside = Files.createDirectories(temporary.resolve("outside"))
        Files.writeString(outside.resolve("image.png"), "not decoded by path resolution")
        val link = assets.resolve("linked")
        linkDirectory(link, outside)
        try {
            for (path in listOf("linked/image.png", "linked/not-created/image.png", "new/../linked/image.png")) {
                assertFailsWith<IllegalArgumentException> {
                    ProjectLayout.fieldImageFile(root.toString(), League.XRP, path)
                }
            }
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test(timeout = 10000)
    fun `linked asset root establishes its real directory as the image boundary`() {
        val root = Files.createDirectories(temporary.resolve("project"))
        val realAssets = Files.createDirectories(temporary.resolve("assets"))
        val link = root.resolve("deploy")
        linkDirectory(link, realAssets)
        try {
            assertEquals(realAssets.toRealPath().resolve("new/image.png").toFile(),
                ProjectLayout.fieldImageFile(root.toString(), League.XRP, "new/image.png"))
        } finally {
            Files.deleteIfExists(link)
        }
    }

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
