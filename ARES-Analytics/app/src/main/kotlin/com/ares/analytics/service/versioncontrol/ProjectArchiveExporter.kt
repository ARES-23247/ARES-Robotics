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

    private companion object {
        const val MAX_ARCHIVE_FILE_BYTES = 100L * 1024L * 1024L
        const val MAX_ARCHIVE_PROJECT_BYTES = 1024L * 1024L * 1024L
    }
}

private fun isExcludedArchivePath(path: String): Boolean {
    val segments = path.replace('\\', '/').lowercase(Locale.ROOT).split('/').filter(String::isNotEmpty)
    return segments.any { it in setOf(".git", ".gradle", "build", ".idea", ".vscode", "out") } ||
        segments.lastOrNull() in setOf("local.properties", ".ds_store", "thumbs.db")
}
