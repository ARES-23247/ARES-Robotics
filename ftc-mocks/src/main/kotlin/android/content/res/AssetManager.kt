package android.content.res

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Minimal desktop implementation of Android's asset manager for simulator and lifecycle tests. */
open class AssetManager {
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
