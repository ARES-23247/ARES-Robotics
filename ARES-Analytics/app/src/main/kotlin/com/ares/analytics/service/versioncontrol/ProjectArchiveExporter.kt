package com.ares.analytics.service.versioncontrol

import com.ares.analytics.service.writeFileAtomically
import com.ares.analytics.service.normalizeUnixGradleWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ProjectArchiveResult(
    val destinationPath: String,
    val fileCount: Int,
    val uncompressedBytes: Long,
    val skippedSensitivePaths: List<String>,
)

/** Creates deterministic, credential-free archives of canonical ARES robot projects. */
class ProjectArchiveExporter internal constructor(private val openArchive: (File) -> InputStream) {
    constructor() : this(File::inputStream)
    suspend fun export(projectPath: String, destinationPath: String): ProjectArchiveResult =
        withContext(Dispatchers.IO) {
            val root = requireCanonicalProjectRoot(projectPath)
            require(destinationPath.isNotBlank()) { "Choose where to save the project archive." }
            val destination = File(destinationPath).canonicalFile
            require(!destination.toPath().startsWith(root.toPath())) {
                "Save the project archive outside the robot project folder."
            }
            require(!destination.exists()) {
                "An archive already exists at that location. Choose a new name so ARES does not replace it."
            }

            val included = mutableListOf<Pair<File, String>>()
            val skippedSensitive = mutableListOf<String>()
            var totalBytes = 0L
            root.walkTopDown().onEnter { directory ->
                require(directory == root || !Files.isSymbolicLink(directory.toPath())) {
                    "The project contains an unsupported directory link " +
                        "(${directory.relativeTo(root).invariantSeparatorsPath}). Remove it before exporting."
                }
                val relative = directory.relativeTo(root).invariantSeparatorsPath
                relative.isEmpty() || !isExcludedArchivePath(relative)
            }.forEach { file ->
                if (file == root || file.isDirectory) return@forEach
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (isExcludedArchivePath(relative)) return@forEach
                if (isSensitiveProjectPath(relative)) {
                    skippedSensitive += relative
                    return@forEach
                }
                require(!Files.isSymbolicLink(file.toPath())) {
                    "The project contains an unsupported link ($relative). Remove it before exporting."
                }
                require(file.isFile && file.length() <= MAX_ARCHIVE_FILE_BYTES) {
                    "$relative is too large for a portable project archive."
                }
                totalBytes = Math.addExact(totalBytes, file.length())
                require(totalBytes <= MAX_ARCHIVE_PROJECT_BYTES) {
                    "The project is too large for one portable archive. Remove build outputs or large recordings first."
                }
                included += file to relative
            }
            require(included.any { it.second == ".ares/project.json" }) {
                "The project archive is missing its canonical ARES project identity."
            }

            writeFileAtomically(destination) { temporary ->
                ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                    included.sortedBy { it.second }.forEach { (file, relative) ->
                        zip.putNextEntry(ZipEntry(relative).apply { time = 0L })
                        file.inputStream().buffered().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            ProjectArchiveResult(
                destinationPath = destination.path,
                fileCount = included.size,
                uncompressedBytes = totalBytes,
                skippedSensitivePaths = skippedSensitive.distinct().sorted(),
            )
        }

    /** Validates in private staging before publishing; failed or cancelled reads leave no partial project. */
    suspend fun extract(archivePath: String, destinationPath: String): File =
        withContext(Dispatchers.IO) {
            require(archivePath.isNotBlank()) { "Choose a project archive to extract." }
            require(destinationPath.isNotBlank()) { "Choose where to extract the robot project." }
            val archive = File(archivePath).canonicalFile
            require(archive.isFile) { "Project archive does not exist: ${archive.path}" }
            require(archive.length() <= MAX_ARCHIVE_PROJECT_BYTES) {
                "Project archive is unexpectedly large."
            }
            val destination = File(destinationPath).canonicalFile
            val existingEmptyDirectory = destination.exists()
            if (existingEmptyDirectory) {
                require(destination.isDirectory && destination.listFiles()?.isEmpty() == true) {
                    "Destination directory already exists and is not empty: ${destination.path}"
                }
            }
            val parent = requireNotNull(destination.parentFile) { "Choose a project folder, not a filesystem root." }
            check(parent.mkdirs() || parent.isDirectory) { "Could not create parent directory: ${parent.path}" }
            val staging = Files.createTempDirectory(parent.toPath(), ".${destination.name}.ares-extract-").toFile()
            var removedEmptyDirectory = false
            var failure: Throwable? = null
            try {
                extractInto(archive, staging)
                File(staging, "gradlew").takeIf(File::isFile)?.let { wrapper ->
                    normalizeUnixGradleWrapper(wrapper)
                    check(wrapper.setExecutable(true, false) || wrapper.canExecute()) {
                        "Could not make gradlew executable."
                    }
                }
                currentCoroutineContext().ensureActive()
                if (existingEmptyDirectory) {
                    // Non-recursive: if another writer added any files, fail without deleting them.
                    Files.delete(destination.toPath())
                    removedEmptyDirectory = true
                }
                // Sibling rename, deliberately without REPLACE_EXISTING (including the race at publication).
                Files.move(staging.toPath(), destination.toPath())
                destination
            } catch (error: Throwable) {
                failure = error
                if (removedEmptyDirectory && !Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    runCatching { Files.createDirectory(destination.toPath()) }.exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            } finally {
                // Only our unique staging directory is recursively removed, never a published/user directory.
                if (staging.exists() && !staging.deleteRecursively()) {
                    val cleanupError = java.io.IOException("Could not remove extraction staging: ${staging.path}")
                    if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
                }
            }
        }

    private suspend fun extractInto(archive: File, destination: File) {
        // ZipInputStream alone accepts EOF between local entries, including a truncated archive.
        val remainingEntries = ZipFile(archive).use { zip ->
            check(zip.size() in 1..MAX_ARCHIVE_ENTRIES) { "The project archive is empty or contains too many files." }
            zip.entries().asSequence().map { it.name }.toMutableSet().also {
                check(it.size == zip.size()) { "The project archive contains duplicate paths." }
            }
        }
        var entryCount = 0
        var extractedBytes = 0L
        var hasProjectJson = false
        val entries = mutableSetOf<String>()
        val portablePaths = mutableMapOf<String, String>()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val context = currentCoroutineContext()

        java.util.zip.ZipInputStream(openArchive(archive).buffered()).use { zip ->
            while (true) {
                context.ensureActive()
                val entry = zip.nextEntry ?: break
                entryCount++
                check(entryCount <= MAX_ARCHIVE_ENTRIES) {
                    "The project archive contains too many files."
                }
                val rawName = entry.name
                check(remainingEntries.remove(rawName)) { "The project archive directory does not match its file entries." }
                check(rawName.isNotBlank() && !rawName.startsWith('/') && !rawName.contains('\\')) {
                    "The project archive contains an invalid path: $rawName"
                }
                val parts = rawName.removeSuffix("/").split('/')
                check(parts.none { it == ".." || it == "." }) {
                    "The project archive contains an unsupported relative path segment: $rawName"
                }
                check(parts.none { part -> part.substringBefore('.').uppercase(Locale.ROOT) in WINDOWS_RESERVED_NAMES }) {
                    "The project archive contains a reserved device name: $rawName"
                }
                check(parts.all { part ->
                    part.isNotBlank() && !part.endsWith('.') && !part.endsWith(' ') &&
                        part.none { it.code < 32 || it in "<>:\"|?*" }
                }) { "The project archive contains a non-portable path: $rawName" }
                check(entries.add(parts.joinToString("/").lowercase(Locale.ROOT))) {
                    "The project archive contains a duplicate path: $rawName"
                }
                parts.indices.forEach { index ->
                    val prefix = parts.take(index + 1).joinToString("/")
                    val previous = portablePaths.putIfAbsent(prefix.lowercase(Locale.ROOT), prefix)
                    check(previous == null || previous == prefix) {
                        "The project archive contains conflicting path capitalization: $rawName"
                    }
                }
                val relative = parts.joinToString(File.separator)
                val target = File(destination, relative).canonicalFile
                check(target.toPath().startsWith(destination.toPath())) {
                    "The project archive attempted to write outside the destination directory: $rawName"
                }
                if (entry.isDirectory) {
                    check(zip.read() == -1) { "Archive directory entries must not contain file data: $rawName" }
                    check(target.mkdirs() || target.isDirectory) {
                        "Could not create directory ${target.path}."
                    }
                } else {
                    check(target.parentFile.mkdirs() || target.parentFile.isDirectory) {
                        "Could not create parent directory ${target.parent}."
                    }
                    Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).buffered().use { output ->
                        var entryBytes = 0L
                        while (true) {
                            context.ensureActive()
                            val read = zip.read(buffer)
                            context.ensureActive()
                            if (read < 0) break
                            entryBytes += read
                            check(entryBytes <= MAX_ARCHIVE_FILE_BYTES) {
                                "$rawName expanded beyond the maximum archive file limit."
                            }
                            extractedBytes += read
                            check(extractedBytes <= MAX_ARCHIVE_PROJECT_BYTES) {
                                "The project expanded beyond its safe size limit."
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                    if (rawName == ".ares/project.json") {
                        hasProjectJson = true
                    }
                }
                zip.closeEntry()
            }
        }

        check(entryCount > 0) { "The project archive was empty." }
        check(remainingEntries.isEmpty()) { "The project archive ended before all files were extracted." }
        check(hasProjectJson) {
            "The project archive is missing its canonical ARES project identity (.ares/project.json)."
        }
    }

    private companion object {
        const val MAX_ARCHIVE_FILE_BYTES = 100L * 1024L * 1024L
        const val MAX_ARCHIVE_PROJECT_BYTES = 1024L * 1024L * 1024L
        const val MAX_ARCHIVE_ENTRIES = 50_000
        val WINDOWS_RESERVED_NAMES = buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL"))
            (1..9).forEach { index ->
                add("COM$index")
                add("LPT$index")
            }
        }
    }
}

private fun isExcludedArchivePath(path: String): Boolean {
    val segments = path.replace('\\', '/').lowercase(Locale.ROOT).split('/').filter(String::isNotEmpty)
    if (segments.isEmpty()) return false
    if (segments.any { it in setOf(".git", ".gradle", "build", ".idea", ".vscode", "out") }) return true
    if (segments[0] == ".ares" && segments.size >= 2) {
        if (segments[1] in setOf("local", "recovery") || segments[1].startsWith(".")) return true
    }
    val fileName = segments.last()
    return fileName in setOf("local.properties", ".ds_store", "thumbs.db")
}
