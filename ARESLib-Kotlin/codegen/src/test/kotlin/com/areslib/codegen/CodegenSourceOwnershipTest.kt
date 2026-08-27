package com.areslib.codegen

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class CodegenSourceOwnershipTest {
    private val repositoryRoot: Path = generateSequence(
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
        Path::getParent,
    ).first { candidate ->
        Files.isRegularFile(candidate.resolve("settings.gradle.kts")) &&
            Files.isDirectory(candidate.resolve("codegen")) && Files.isDirectory(candidate.resolve("core"))
    }

    @Test
    fun `codegen owns its sources without core source-set aliases`() {
        assertTrue(Files.isDirectory(repositoryRoot.resolve("codegen/src/main/kotlin/com/areslib/codegen")))
        val formerCorePackage = repositoryRoot.resolve("core/src/main/kotlin/com/areslib/codegen")
        val remainingCoreSources = if (Files.isDirectory(formerCorePackage)) {
            Files.list(formerCorePackage).use { files -> files.anyMatch { it.fileName.toString().endsWith(".kt") } }
        } else false
        assertFalse(remainingCoreSources)

        val codegenBuild = Files.readString(repositoryRoot.resolve("codegen/build.gradle.kts"))
        val coreBuild = Files.readString(repositoryRoot.resolve("core/build.gradle.kts"))
        assertFalse(codegenBuild.contains("../core"))
        assertFalse(coreBuild.contains("com/areslib/codegen"))
    }

    @Test
    fun `every BOM project is part of the immutable publication graph`() {
        val bomBuild = Files.readString(repositoryRoot.resolve("ares-bom/build.gradle.kts"))
        val rootBuild = Files.readString(repositoryRoot.resolve("build.gradle.kts"))
        val bomProjects = Regex("""api\(project\(\"([^\"]+)\"\)\)""")
            .findAll(bomBuild)
            .map { it.groupValues[1] }
            .toSortedSet()
        val publishedBlock = rootBuild.substringAfter("val publishedProjectPaths = listOf(")
            .substringBefore("\n)")
        val publishedProjects = Regex("""\"([^\"]+)\"""")
            .findAll(publishedBlock)
            .map { it.groupValues[1] }
            .toSortedSet()

        assertEquals(bomProjects + ":ares-bom", publishedProjects)
        bomProjects.forEach { projectPath ->
            assertTrue(
                Files.isDirectory(repositoryRoot.resolve(projectPath.removePrefix(":"))),
                "Published BOM project $projectPath must exist",
            )
        }
    }
}
