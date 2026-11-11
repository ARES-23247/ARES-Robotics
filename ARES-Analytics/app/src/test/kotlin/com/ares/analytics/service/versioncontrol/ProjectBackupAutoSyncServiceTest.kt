package com.ares.analytics.service.versioncontrol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectBackupAutoSyncServiceTest {
    @Test
    fun `scheduling two canonical projects within debounce pushes both in order without dropping the first`(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-auto-sync-test-").toFile()
        try {
            val repoA = createEnabledAutoSyncRepo(File(tempRoot, "project-a"))
            val repoB = createEnabledAutoSyncRepo(File(tempRoot, "project-b"))
            val pushedPaths = Collections.synchronizedList(mutableListOf<String>())
            val bothPushed = CompletableDeferred<Unit>()

            val service = ProjectBackupAutoSyncService(
                inspectProject = { path -> syncedPlan(path) },
                pushBackup = { path ->
                    pushedPaths += path
                    if (pushedPaths.size >= 2) {
                        bothPushed.complete(Unit)
                    }
                    syncedPlan(path)
                },
                epochSeconds = { 1_700_000_000L },
                workerDelay = {},
            )
            try {
                service.schedule(repoA.path)
                service.schedule(repoB.path)
                withTimeout(5_000L) {
                    bothPushed.await()
                }
                assertEquals(listOf(repoA.canonicalPath, repoB.canonicalPath), pushedPaths.toList())
            } finally {
                service.closeAndJoin()
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun createEnabledAutoSyncRepo(directory: File): File {
        val projectFile = File(directory, ".ares/project.json")
        projectFile.parentFile.mkdirs()
        projectFile.writeText("""{"schemaVersion":1,"name":"${directory.name}"}""")
        Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            git.add().addFilepattern(".ares/project.json").call()
            git.commit()
                .setSign(false)
                .setAuthor("ARES Test", "test@aresfirst.org")
                .setMessage("Initial project checkpoint")
                .call()
            val config = git.repository.config
            config.setBoolean("aresBackup", null, "autoSync", true)
            config.save()
        }
        return directory.canonicalFile
    }

    private fun syncedPlan(projectPath: String) = ProjectBackupPlan(
        projectPath = projectPath,
        initialized = true,
        branch = "main",
        changes = emptyList(),
        blockedSensitivePaths = emptyList(),
        lastCommit = "abc1234",
        remoteUrl = "https://github.com/team/private-backup.git",
        destination = GitHubBackupDestination(
            installationId = 1L,
            repositoryId = 10L,
            ownerLogin = "team",
            repositoryName = "private-backup",
            accountKind = GitHubAccountKind.ORGANIZATION,
            cloneUrl = "https://github.com/team/private-backup.git",
            webUrl = "https://github.com/team/private-backup",
        ),
        confirmationToken = null,
    )
}
