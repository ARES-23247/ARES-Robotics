package android.content.res

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Minimal desktop implementation of Android's [AssetManager] for simulator and lifecycle tests.
 *
 * Emulates the Android asset packaging system by searching standard local repository asset paths
 * (`TeamCode/src/main/assets`, `src/main/assets`, `../TeamCode/src/main/assets`) on desktop.
 *
 * This allows vision models, AprilTag layout files, and machine learning models bundled as
 * assets to be opened seamlessly during desktop tests without modifying production robot code.
 */
open class AssetManager {
    /** Constructs a desktop mock [AssetManager]. */
    constructor()

    /**
     * Opens an input stream to access the specified [fileName] from local project asset directories.
     *
     * Path traversal components (e.g. `..`) are strictly forbidden to prevent accessing files
     * outside the intended asset repository roots.
     *
     * @param fileName Relative path to the asset file within the asset hierarchy.
     * @return An open [InputStream] reading the asset content.
     * @throws IllegalArgumentException If [fileName] contains directory traversal sequences.
     * @throws java.io.FileNotFoundException If the asset file does not exist in candidate directories.
     */
    open fun open(fileName: String): InputStream {
        val normalized = fileName.replace('\\', '/').removePrefix("/")
        require(".." !in normalized.split('/')) { "Asset path traversal is not allowed" }
        val candidates = listOf(
            File("TeamCode/src/main/assets", normalized),
            File("src/main/assets", normalized),
            File("../TeamCode/src/main/assets", normalized)
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: throw java.io.FileNotFoundException("Desktop asset '$normalized' was not found")
        return FileInputStream(file)
    }
}
