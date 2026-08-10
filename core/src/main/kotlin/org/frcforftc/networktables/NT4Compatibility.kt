package org.frcforftc.networktables

import com.areslib.networktables.NT4Server as KotlinNT4Server
import com.areslib.networktables.NT4Entry as KotlinNT4Entry
import com.areslib.networktables.NT4Instance as KotlinNT4Instance

/**
 * Source-compatibility alias for releases that exposed NT4 from `org.frcforftc.networktables`.
 * It is the same process-wide server type; no adapter or second topic registry is created.
 */
@Deprecated(
    message = "Use com.areslib.networktables.NT4Server instead",
    replaceWith = ReplaceWith("NT4Server", "com.areslib.networktables.NT4Server")
)
typealias NT4Server = KotlinNT4Server

/** Legacy source alias for [com.areslib.networktables.NT4Entry]. */
@Deprecated(
    message = "Use com.areslib.networktables.NT4Entry instead",
    replaceWith = ReplaceWith("NT4Entry", "com.areslib.networktables.NT4Entry")
)
typealias NetworkTablesEntry = KotlinNT4Entry

/** Legacy source alias for [com.areslib.networktables.NT4Instance]. */
@Deprecated(
    message = "Use com.areslib.networktables.NT4Instance instead",
    replaceWith = ReplaceWith("NT4Instance", "com.areslib.networktables.NT4Instance")
)
typealias NetworkTablesInstance = KotlinNT4Instance
