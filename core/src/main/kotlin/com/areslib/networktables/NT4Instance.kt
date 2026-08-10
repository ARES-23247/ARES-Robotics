package com.areslib.networktables

/**
 * Kotlin facade over the process-wide custom NT4 server.
 *
 * ARESLib supports one active [NT4Server] per JVM. [startServer] replaces and stops an existing
 * server, resets the shared topic registry, binds the requested address, and starts its WebSocket
 * worker. Call it from application initialization rather than a robot loop.
 */
class NT4Instance private constructor() {
    /** Active process-wide server, or `null` before startup/after shutdown. */
    val defaultServer: NT4Server?
        get() = NT4Server.getInstance()

    /** Starts and returns the sole server, listening on [address]:[port]. */
    fun startServer(address: String = "0.0.0.0", port: Int = 5810): NT4Server {
        return NT4Server.createInstance(address, port)
    }

    companion object {
        @JvmStatic
        val defaultInstance: NT4Instance by lazy { NT4Instance() }
    }
}
