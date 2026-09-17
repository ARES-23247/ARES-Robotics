package com.ares.analytics.service.versioncontrol

import com.ares.analytics.service.writeFileAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ProjectArchiveResult(
    val destinationPath: String,
    val fileCount: Int,
    val uncompressedBytes: Long,
    val skippedSensitivePaths: List<String>,
)

/** Creates deterministic, credential-free archives of canonical ARES robot projects. */
class ProjectArchiveExporter {
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
            if (destination.exists()) {
                val existing = destination.listFiles()
                require(destination.isDirectory && (existing == null || existing.isEmpty())) {
                    "Destination directory already exists and is not empty: ${destination.path}"
                }
            } else {
                check(destination.mkdirs()) { "Could not create destination directory: ${destination.path}" }
            }

            var entryCount = 0
            var extractedBytes = 0L
            var hasProjectJson = false

            java.util.zip.ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    check(entryCount <= MAX_ARCHIVE_ENTRIES) {
                        "The project archive contains too many files."
                    }
                    val rawName = entry.name
                    check(rawName.isNotBlank() && !rawName.contains('\\')) {
                        "The project archive contains an invalid path: $rawName"
                    }
                    val parts = rawName.split('/').filter(String::isNotEmpty)
                    if (parts.isEmpty()) continue
                    check(parts.none { it == ".." || it == "." }) {
                        "The project archive contains an unsupported relative path segment: $rawName"
                    }
                    check(parts.none { part -> part.substringBefore('.').uppercase(Locale.ROOT) in WINDOWS_RESERVED_NAMES }) {
                        "The project archive contains a reserved device name: $rawName"
                    }
                    val relative = parts.joinToString(File.separator)
                    val target = File(destination, relative).canonicalFile
                    check(target.toPath().startsWith(destination.toPath())) {
                        "The project archive attempted to write outside the destination directory: $rawName"
                    }
                    if (entry.isDirectory) {
                        check(target.mkdirs() || target.isDirectory) {
                            "Could not create directory ${target.path}."
                        }
                    } else {
                        check(target.parentFile.mkdirs() || target.parentFile.isDirectory) {
                            "Could not create parent directory ${target.parent}."
                        }
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var entryBytes = 0L
                            while (true) {
                                val read = zip.read(buffer)
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
            check(hasProjectJson) {
                "The project archive is missing its canonical ARES project identity (.ares/project.json)."
            }

            File(destination, "gradlew").takeIf(File::isFile)?.let { wrapper ->
                val bytes = wrapper.readBytes()
                if (bytes.contains('\r'.code.toByte())) {
                    wrapper.writeBytes(bytes.filterNot { it == '\r'.code.toByte() }.toByteArray())
                }
                wrapper.setExecutable(true, false)
            }

            destination
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
