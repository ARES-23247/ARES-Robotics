package com.areslib.auto

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Safe, platform-neutral resolver for native `.aresauto` documents.
 *
 * Platform adapters provide their deploy directories and, when available, a resource opener. The
 * resolver never accepts a path from the document selector, so dashboard or Driver Station input
 * cannot escape an auto directory.
 */
object AresAutoFileLoader {
    private val documentIdPattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    /** Returns the canonical deploy filename for [documentId]. */
    fun fileName(documentId: String): String {
        require(documentId.matches(documentIdPattern)) { "Invalid ARES auto document ID '$documentId'" }
        return "$documentId.aresauto"
    }

    /**
     * Loads from the first matching directory, then from [openResource].
     *
     * [openResource] receives `ares/autos/<id>.aresauto` and may return `null` when the platform
     * has no bundled resource. File-system assets intentionally take priority so Analytics can
     * deploy a newer routine without rebuilding the robot application.
     */
    @JvmOverloads
    fun load(
        documentId: String,
        directories: List<File>,
        openResource: ((String) -> InputStream?)? = null
    ): AutoRoutine {
        val fileName = fileName(documentId)
        val failures = mutableListOf<String>()
        for (directory in directories) {
            val candidate = File(directory, fileName)
            if (!candidate.isFile) continue
            try {
                return AresAutoCodec.decode(candidate.readText(Charsets.UTF_8))
            } catch (error: Exception) {
                failures += "${candidate.absolutePath}: ${error.message}"
            }
        }

        val resourcePath = "ares/autos/$fileName"
        if (openResource != null) {
            try {
                openResource(resourcePath)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    return AresAutoCodec.decode(reader.readText())
                }
            } catch (error: Exception) {
                failures += "$resourcePath: ${error.message}"
            }
        }

        val searched = directories.joinToString { File(it, fileName).absolutePath }
        val details = failures.takeIf(List<String>::isNotEmpty)
            ?.joinToString(prefix = " Invalid candidates: ", separator = "; ")
            .orEmpty()
        throw IOException("Could not load ARES auto '$documentId'. Searched: $searched and $resourcePath.$details")
    }
}
