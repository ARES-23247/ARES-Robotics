package com.ares.analytics.service

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Resolve existing links, retaining the suffix when the final file or directory does not exist. */
internal fun resolveExistingPath(path: Path): Path {
    var existing = path.toAbsolutePath().normalize()
    val missing = ArrayDeque<Path>()
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
        missing.addFirst(existing.fileName)
        existing = existing.parent ?: throw IOException("Path has no existing filesystem root: $path")
    }
    var resolved = existing.toRealPath()
    for (part in missing) resolved = resolved.resolve(part)
    return resolved
}
