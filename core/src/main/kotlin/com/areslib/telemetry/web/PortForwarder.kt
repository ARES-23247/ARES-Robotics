package com.areslib.telemetry.web

/**
 * Daemon TCP proxy from [localPort] to [targetIp]:[remotePort].
 *
 * Each accepted connection gets one daemon worker plus a daemon copy thread for the opposite
 * direction. Either direction completing closes both sockets. Bind, connect, and copy failures are
 * intentionally suppressed because forwarding is optional robot infrastructure. [stopForwarder]
 * stops new accepts by closing the server socket; existing connection workers close when their IO
 * completes or fails.
 */
class PortForwarder(private val localPort: Int, private val remotePort: Int, private val targetIp: String) : Thread("ARES-PortForwarder-$localPort") {
    private var serverSocket: java.net.ServerSocket? = null
    @Volatile private var running = true

    init {
        isDaemon = true
    }

    /** Binds the local listener and accepts until stopped or the listener fails. */
    override fun run() {
        try {
            serverSocket = java.net.ServerSocket(localPort)
            while (running) {
                val clientSocket = serverSocket?.accept() ?: break
                Thread {
                    try {
                        val serverSocketConnection = java.net.Socket(targetIp, remotePort)
                        Thread {
                            try {
                                clientSocket.inputStream.copyTo(serverSocketConnection.outputStream)
                            } catch (_: Exception) {} finally {
                                try { serverSocketConnection.close() } catch (_: Exception) {}
                                try { clientSocket.close() } catch (_: Exception) {}
                            }
                        }.apply { isDaemon = true }.start()
                        try {
                            serverSocketConnection.inputStream.copyTo(clientSocket.outputStream)
                        } catch (_: Exception) {} finally {
                            try { serverSocketConnection.close() } catch (_: Exception) {}
                            try { clientSocket.close() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {
                        try { clientSocket.close() } catch (_: Exception) {}
                    }
                }.apply { isDaemon = true }.start()
            }
        } catch (_: Exception) {}
    }

    /** Idempotently prevents new connections and unblocks a pending accept. */
    fun stopForwarder() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}
