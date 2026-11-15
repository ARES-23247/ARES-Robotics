package com.ares.analytics.mcp.engine

import kotlinx.serialization.Serializable
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Summary of a non-intrusive JGit snapshot stored under `refs/ares-mcp/snapshots/`. */
@Serializable
data class McpGitSnapshotInfo(
    val refName: String,
    val commitId: String,
    val message: String,
    val createdAtEpochMillis: Long,
)

/** Outcome of creating a safety snapshot prior to mutating project files. */
@Serializable
data class SnapshotCreateResult(
    val created: Boolean,
    val refName: String? = null,
    val commitId: String? = null,
    val message: String,
)

/** Outcome of restoring files from a snapshot ref. */
@Serializable
data class SnapshotRestoreResult(
    val restored: Boolean,
    val refName: String,
    val backupRefName: String? = null,
    val restoredFileCount: Int = 0,
    val removedFileCount: Int = 0,
    val message: String,
)

/**
 * Creates, lists, and restores JGit safety snapshots stored exclusively under
 * `refs/ares-mcp/snapshots/<epochMillis>-<slug>` without touching `HEAD`, checked-out branches, or
 * `.git/index`.
 */
object JGitSnapshotSafety {
    const val SNAPSHOT_REF_PREFIX = "refs/ares-mcp/snapshots/"

    private val excludedDirs = setOf(".git", "build", ".gradle", ".idea", "out")

    fun createSnapshot(
        projectRoot: File,
        slug: String,
        initGitIfMissing: Boolean = false,
    ): SnapshotCreateResult {
        val gitDir = File(projectRoot, ".git")
        if (!gitDir.exists()) {
            if (!initGitIfMissing) {
                return SnapshotCreateResult(
                    created = false,
                    message = "Project has no .git directory; skipped non-intrusive Git snapshot.",
                )
            }
            openOrCreateRepository(projectRoot, create = true).close()
        }
        val safeSlug = slug.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifEmpty { "snapshot" }
        val nowMillis = Instant.now().toEpochMilli()
        val refName = "$SNAPSHOT_REF_PREFIX$nowMillis-$safeSlug"
        return openOrCreateRepository(projectRoot, create = false).use { repo ->
            val commitId = writeTreeAndCommit(repo, projectRoot, nowMillis, "ares-mcp snapshot: $safeSlug")
            val update = repo.updateRef(refName)
            update.setNewObjectId(commitId)
            update.setRefLogMessage("ares-mcp snapshot $safeSlug", false)
            val result = update.update()
            require(
                result == RefUpdate.Result.NEW ||
                    result == RefUpdate.Result.FAST_FORWARD ||
                    result == RefUpdate.Result.FORCED,
            ) {
                "Failed to record snapshot ref '$refName': $result"
            }
            SnapshotCreateResult(
                created = true,
                refName = refName,
                commitId = commitId.name,
                message = "Recorded non-intrusive snapshot at $refName without modifying HEAD or .git/index.",
            )
        }
    }

    fun listSnapshots(projectRoot: File): List<McpGitSnapshotInfo> {
        if (!File(projectRoot, ".git").exists()) return emptyList()
        return openOrCreateRepository(projectRoot, create = false).use { repo ->
            RevWalk(repo).use { revWalk ->
                repo.refDatabase.getRefsByPrefix(SNAPSHOT_REF_PREFIX).mapNotNull { ref ->
                    val commit = runCatching { revWalk.parseCommit(ref.objectId) }.getOrNull() ?: return@mapNotNull null
                    val prefixTime = ref.name.removePrefix(SNAPSHOT_REF_PREFIX)
                        .substringBefore('-')
                        .toLongOrNull()
                        ?: (commit.commitTime.toLong() * 1000L)
                    McpGitSnapshotInfo(
                        refName = ref.name,
                        commitId = commit.id.name,
                        message = commit.shortMessage,
                        createdAtEpochMillis = prefixTime,
                    )
                }.sortedByDescending { it.createdAtEpochMillis }
            }
        }
    }

    fun restoreSnapshot(projectRoot: File, requestedRefName: String): SnapshotRestoreResult {
        val gitDir = File(projectRoot, ".git")
        require(gitDir.exists()) { "Cannot restore snapshot: '$projectRoot' is not a Git repository." }
        val normalizedRef = if (requestedRefName.startsWith(SNAPSHOT_REF_PREFIX)) {
            requestedRefName
        } else {
            SNAPSHOT_REF_PREFIX + requestedRefName.removePrefix("/")
        }
        val backup = createSnapshot(projectRoot, slug = "pre-restore", initGitIfMissing = false)
        return openOrCreateRepository(projectRoot, create = false).use { repo ->
            val targetId = repo.resolve(normalizedRef)
                ?: throw IllegalArgumentException("Snapshot ref '$normalizedRef' was not found.")
            val preRestoreFiles = backup.refName?.let { readSnapshotPaths(repo, it) }.orEmpty()
            val targetFiles = RevWalk(repo).use { revWalk ->
                val commit = revWalk.parseCommit(targetId)
                val entries = linkedMapOf<String, ByteArray>()
                TreeWalk(repo).use { treeWalk ->
                    treeWalk.addTree(commit.tree)
                    treeWalk.isRecursive = true
                    while (treeWalk.next()) {
                        val path = treeWalk.pathString
                        val bytes = ByteArrayOutputStream().use { baos ->
                            repo.open(treeWalk.getObjectId(0)).copyTo(baos)
                            baos.toByteArray()
                        }
                        entries[path] = bytes
                    }
                }
                entries
            }

            var removed = 0
            for (path in preRestoreFiles) {
                if (path !in targetFiles) {
                    val target = safeResolveChild(projectRoot, path)
                    if (target.isFile && target.delete()) removed++
                }
            }
            var restored = 0
            for ((relPath, bytes) in targetFiles) {
                val dest = safeResolveChild(projectRoot, relPath)
                writeBytesAtomically(dest, bytes)
                restored++
            }
            SnapshotRestoreResult(
                restored = true,
                refName = normalizedRef,
                backupRefName = backup.refName,
                restoredFileCount = restored,
                removedFileCount = removed,
                message = "Restored $restored file(s) from $normalizedRef (backup=${backup.refName ?: "none"}).",
            )
        }
    }

    private fun readSnapshotPaths(repo: Repository, refName: String): Set<String> {
        val id = repo.resolve(refName) ?: return emptySet()
        return RevWalk(repo).use { revWalk ->
            val commit = revWalk.parseCommit(id)
            val result = linkedSetOf<String>()
            TreeWalk(repo).use { treeWalk ->
                treeWalk.addTree(commit.tree)
                treeWalk.isRecursive = true
                while (treeWalk.next()) {
                    result += treeWalk.pathString
                }
            }
            result
        }
    }

    private fun writeTreeAndCommit(
        repo: Repository,
        projectRoot: File,
        timestampMillis: Long,
        message: String,
    ): ObjectId = repo.newObjectInserter().use { inserter ->
        val dirCache = DirCache.newInCore()
        val builder = dirCache.builder()
        val files = collectSnapshotFiles(projectRoot)
        for ((relPath, file) in files) {
            val blobId = inserter.insert(Constants.OBJ_BLOB, file.readBytes())
            val entry = DirCacheEntry(relPath).apply {
                fileMode = if (file.canExecute() && !System.getProperty("os.name").lowercase().contains("win")) {
                    FileMode.EXECUTABLE_FILE
                } else {
                    FileMode.REGULAR_FILE
                }
                setObjectId(blobId)
                length = file.length().toInt()
            }
            builder.add(entry)
        }
        builder.finish()
        val treeId = dirCache.writeTree(inserter)
        val ident = PersonIdent("ARES MCP", "mcp@ares-robotics.local", Instant.ofEpochMilli(timestampMillis), java.time.ZoneOffset.UTC)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            author = ident
            committer = ident
            this.message = message
            repo.resolve(Constants.HEAD)?.let { setParentId(it) }
        }
        val commitId = inserter.insert(commit)
        inserter.flush()
        commitId
    }

    private fun collectSnapshotFiles(root: File): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles()?.sortedBy { it.name }.orEmpty()
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name in excludedDirs) continue
                    val nextPrefix = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    walk(child, nextPrefix)
                } else if (child.isFile) {
                    if (child.extension in setOf("hprof", "lock") || child.length() > 10 * 1024 * 1024) continue
                    val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    out += rel to child
                }
            }
        }
        walk(root, "")
        return out
    }

    private fun openOrCreateRepository(projectRoot: File, create: Boolean): Repository {
        val gitDir = File(projectRoot, ".git")
        val repo = FileRepositoryBuilder()
            .setGitDir(gitDir)
            .setWorkTree(projectRoot)
            .build()
        if (create && !gitDir.exists()) {
            repo.create(false)
        }
        return repo
    }

    internal fun safeResolveChild(root: File, relativePath: String): File {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val resolved = rootPath.resolve(relativePath).normalize()
        require(resolved.startsWith(rootPath)) { "Path escapes project root: '$relativePath'" }
        return resolved.toFile()
    }

    internal fun writeBytesAtomically(destination: File, bytes: ByteArray) {
        val parent = destination.parentFile ?: error("Destination has no parent directory: $destination")
        parent.mkdirs()
        val temp = File.createTempFile(".ares-mcp-", ".tmp", parent)
        try {
            temp.writeBytes(bytes)
            runCatching {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
