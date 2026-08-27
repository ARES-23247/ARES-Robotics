package com.ares.analytics

import com.ares.analytics.service.project.AresProjectDocuments
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import java.io.File
import java.nio.file.Files

internal const val PACKAGED_PROJECT_VALIDATION_COMMAND = "--verify-packaged-project"

internal data class PackagedProjectValidationResult(
    val routineCount: Int,
    val subsystemCount: Int,
    val errors: List<String>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * Exercises every canonical ARES project-document codec without starting Compose or robot services.
 *
 * The native-distribution workflow invokes this through the generated application launcher. That
 * matters: a normal JVM test cannot detect Java modules accidentally omitted from the jlink image.
 */
internal fun validatePackagedProject(projectPath: String): PackagedProjectValidationResult {
    val snapshot = AresProjectDocuments().load(projectPath)
    val project = snapshot.query
    val errors = buildList {
        addAll(snapshot.diagnostics.map { diagnostic ->
            "${diagnostic.kind}: ${diagnostic.file.name}: ${diagnostic.message}"
        })
        if (project.metadata == null) add("Project metadata did not load")
        if (project.capabilityCatalog == null) add("Capability catalog did not load")
        if (project.autonomousCatalog == null) add("Autonomous catalog did not load")
        if (project.routines.isEmpty()) add("No routine document loaded")
        if (project.subsystems.isEmpty()) add("No subsystem document loaded")
    }
    return PackagedProjectValidationResult(
        routineCount = project.routines.size,
        subsystemCount = project.subsystems.size,
        errors = errors,
    )
}

/**
 * Exercises JGit's real pack-transfer path inside the jlink runtime.
 *
 * Opening an ordinary repository is insufficient: JGit first touches java.management while
 * publishing its pack-window-cache MBean during fetch. This probe caught the exact packaged-only
 * failure that a full-JDK unit test and document-loader smoke test could not see.
 */
internal fun validatePackagedGitRuntime() {
    com.ares.analytics.service.versioncontrol.configureJGitLogging()
    val root = Files.createTempDirectory("ares-packaged-jgit-").toFile()
    try {
        val source = File(root, "source").apply { mkdirs() }
        val remote = File(root, "remote.git")
        val target = File(root, "target").apply { mkdirs() }
        Git.init().setDirectory(source).setInitialBranch("main").call().use { git ->
            File(source, "probe.txt").writeText("ARES packaged JGit pack-transfer probe")
            git.add().addFilepattern(".").call()
            git.commit()
                .setMessage("Create packaged JGit probe")
                .setAuthor("ARES Validation", "validation@aresfirst.org")
                .setCommitter("ARES Validation", "validation@aresfirst.org")
                .setSign(false)
                .call()
            Git.init().setBare(true).setDirectory(remote).call().close()
            git.push().setRemote(remote.toURI().toString()).setPushAll().call()
        }
        Git.init().setDirectory(target).setInitialBranch("main").call().use { git ->
            git.fetch()
                .setRemote(remote.toURI().toString())
                .setRefSpecs(RefSpec("+refs/heads/main:refs/remotes/probe/main"))
                .call()
            checkNotNull(git.repository.resolve("refs/remotes/probe/main")) {
                "Packaged JGit fetch did not produce the reviewed main ref"
            }
        }
    } finally {
        root.deleteRecursively()
    }
}

/** Returns null when the desktop application should launch normally. */
internal fun runPackagedProjectValidationCommand(args: Array<String>): Int? {
    if (args.firstOrNull() != PACKAGED_PROJECT_VALIDATION_COMMAND) return null
    if (args.size != 2) {
        System.err.println("Usage: $PACKAGED_PROJECT_VALIDATION_COMMAND <project-directory>")
        return 64
    }

    val result = runCatching {
        validatePackagedGitRuntime()
        validatePackagedProject(args[1])
    }
        .onFailure { error ->
            System.err.println("PACKAGED_PROJECT_VALIDATION_FAILED: ${error.message}")
        }
        .getOrNull() ?: return 1

    if (!result.isValid) {
        result.errors.forEach { System.err.println("PACKAGED_PROJECT_VALIDATION_FAILED: $it") }
        return 1
    }

    println(
        "PACKAGED_PROJECT_VALIDATION_OK " +
            "routines=${result.routineCount} subsystems=${result.subsystemCount} jgitPackTransfer=true"
    )
    return 0
}
