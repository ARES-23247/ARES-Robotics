package com.areslib.ftc.telemetry

import com.areslib.util.RobotClock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Configuration schema for a single Limelight camera. */
data class LimelightConfig(
    val name: String,
    val targetIp: String,
    val localPortOffset: Int = 0,
    val targetPortOffset: Int = 0
)

/**
 * Bounded TCP proxy for Limelight web, stream, websocket, and status ports.
 *
 * Each accepted client consumes one per-camera permit and one process-wide permit. A connection
 * uses exactly two copy workers (one per direction); there is no third waiter thread. Listener,
 * worker, socket, and queued-task counts are therefore bounded by constructor limits. All reads use
 * a finite idle timeout, and [stop] closes every listener and active socket before joining pools.
 */
class LimelightProxy(
    private val cameras: List<LimelightConfig> = listOf(
        LimelightConfig("Front", "172.29.0.1", 0)
    ),
    private val maxConnectionsPerCamera: Int = DEFAULT_CONNECTIONS_PER_CAMERA,
    private val maxConnectionsGlobal: Int =
        (cameras.size * DEFAULT_CONNECTIONS_PER_CAMERA).coerceIn(1, DEFAULT_GLOBAL_CONNECTIONS),
    private val socketIdleTimeoutMs: Int = DEFAULT_IDLE_TIMEOUT_MS,
    private val stopTimeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS
) {
    private var acceptExecutor: ExecutorService? = null
    private var copyExecutor: ExecutorService? = null
    private val forwarders = mutableListOf<TCPForwarder>()
    private val activeConnections = ConcurrentHashMap.newKeySet<ProxiedConnection>()
    private val globalConnections = Semaphore(maxConnectionsGlobal, true)
    private val activeClientCounter = AtomicInteger()
    private val peakClientCounter = AtomicInteger()

    internal val activeClientCount: Int get() = activeClientCounter.get()
    internal val peakClientCount: Int get() = peakClientCounter.get()

    init {
        require(cameras.isNotEmpty()) { "At least one Limelight camera is required" }
        require(cameras.size <= MAX_CAMERAS) { "At most $MAX_CAMERAS Limelight cameras are supported" }
        require(maxConnectionsPerCamera in 1..MAX_CONNECTIONS_PER_CAMERA) {
            "maxConnectionsPerCamera must be between 1 and $MAX_CONNECTIONS_PER_CAMERA"
        }
        require(maxConnectionsGlobal in 1..MAX_GLOBAL_CONNECTIONS) {
            "maxConnectionsGlobal must be between 1 and $MAX_GLOBAL_CONNECTIONS"
        }
        require(socketIdleTimeoutMs in MIN_IDLE_TIMEOUT_MS..MAX_IDLE_TIMEOUT_MS) {
            "socketIdleTimeoutMs must be finite and between $MIN_IDLE_TIMEOUT_MS and $MAX_IDLE_TIMEOUT_MS"
        }
        require(stopTimeoutMs in 1L..MAX_STOP_TIMEOUT_MS) {
            "stopTimeoutMs must be between 1 and $MAX_STOP_TIMEOUT_MS"
        }
    }

    private fun discoverLimelightIp(): String {
        val executor = Executors.newFixedThreadPool(DISCOVERY_THREADS) { task ->
            Thread(task, "LimelightProxy-Discovery").apply { isDaemon = true }
        }
        val completion = ExecutorCompletionService<String?>(executor)
        repeat(254) { index ->
            val ip = "172.29.0.${index + 1}"
            completion.submit {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(ip, 5801), DISCOVERY_CONNECT_TIMEOUT_MS)
                    }
                    ip
                } catch (_: IOException) {
                    null
                }
            }
        }

        val deadline = RobotClock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DISCOVERY_DEADLINE_MS)
        var completed = 0
        try {
            while (completed < 254) {
                val remaining = deadline - RobotClock.nanoTime()
                if (remaining <= 0L) break
                val result = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                completed++
                result.get()?.let { discovered ->
                    System.out.println("LimelightProxy: Auto-discovered Limelight at $discovered")
                    return discovered
                }
            }
        } catch (_: Exception) {
            // Fall through to the fixed USB-C address.
        } finally {
            executor.shutdownNow()
        }
        return DEFAULT_LIMELIGHT_IP
    }

    /** Starts all configured camera tunnels. Repeated calls while running are no-ops. */
    @Synchronized
    fun start() {
        if (acceptExecutor?.isShutdown == false) return
        val listenerCount = cameras.size * FORWARDED_PORT_COUNT
        val listeners = Executors.newFixedThreadPool(listenerCount) { task ->
            Thread(task, "LimelightProxy-Acceptor").apply { isDaemon = true }
        }
        val workers = Executors.newFixedThreadPool(maxConnectionsGlobal * COPY_DIRECTIONS) { task ->
            Thread(task, "LimelightProxy-Copy").apply { isDaemon = true }
        }
        acceptExecutor = listeners
        copyExecutor = workers

        try {
            val discoveredIp = if (cameras.any { it.targetIp.startsWith("172.29.0.") }) {
                discoverLimelightIp()
            } else {
                null
            }
            for (camera in cameras) {
                val cameraConnections = Semaphore(maxConnectionsPerCamera, true)
                val localBase = 5800 + camera.localPortOffset
                val remoteBase = 5800 + camera.targetPortOffset
                val targetIp = if (camera.targetIp.startsWith("172.29.0.")) {
                    requireNotNull(discoveredIp)
                } else {
                    camera.targetIp
                }
                repeat(FORWARDED_PORT_COUNT) { offset ->
                    startForwarder(
                        localBase + offset,
                        targetIp,
                        remoteBase + offset,
                        cameraConnections,
                        listeners,
                        workers
                    )
                }
            }
        } catch (failure: Throwable) {
            stop()
            throw failure
        }
    }

    private fun startForwarder(
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
        cameraConnections: Semaphore,
        listeners: ExecutorService,
        workers: ExecutorService
    ) {
        val forwarder = TCPForwarder(
            localPort,
            remoteHost,
            remotePort,
            cameraConnections,
            workers
        )
        forwarders.add(forwarder)
        listeners.submit(forwarder)
    }

    /** Closes all sockets immediately and joins the bounded pools within [stopTimeoutMs]. */
    @Synchronized
    fun stop() {
        forwarders.forEach(TCPForwarder::stop)
        forwarders.clear()
        activeConnections.forEach(ProxiedConnection::abort)

        val listeners = acceptExecutor
        val workers = copyExecutor
        acceptExecutor = null
        copyExecutor = null
        listeners?.shutdownNow()
        workers?.shutdownNow()

        val deadline = RobotClock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stopTimeoutMs)
        awaitUntil(listeners, deadline)
        awaitUntil(workers, deadline)
    }

    private fun awaitUntil(executor: ExecutorService?, deadlineNanos: Long) {
        if (executor == null) return
        val remaining = deadlineNanos - RobotClock.nanoTime()
        if (remaining <= 0L) return
        try {
            executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private inner class TCPForwarder(
        private val localPort: Int,
        private val remoteHost: String,
        private val remotePort: Int,
        private val cameraConnections: Semaphore,
        private val workers: ExecutorService
    ) : Runnable {
        @Volatile private var serverSocket: ServerSocket? = null
        @Volatile private var running = true

        override fun run() {
            try {
                if (!running) return
                val listener = ServerSocket(localPort)
                serverSocket = listener
                while (running) {
                    val client = listener.accept()
                    if (!running) {
                        client.close()
                        break
                    }
                    acceptClient(client)
                }
            } catch (_: IOException) {
                // Normal path when stop closes the listener.
            } finally {
                try { serverSocket?.close() } catch (_: IOException) {}
                serverSocket = null
            }
        }

        private fun acceptClient(client: Socket) {
            if (!globalConnections.tryAcquire()) {
                closeQuietly(client)
                return
            }
            if (!cameraConnections.tryAcquire()) {
                globalConnections.release()
                closeQuietly(client)
                return
            }

            val remote = Socket()
            val connection = ProxiedConnection(client, remote, cameraConnections)
            try {
                client.soTimeout = socketIdleTimeoutMs
                remote.soTimeout = socketIdleTimeoutMs
                remote.connect(InetSocketAddress(remoteHost, remotePort), CONNECT_TIMEOUT_MS)
                connection.start(workers)
            } catch (_: Throwable) {
                connection.abortBeforeStart()
            }
        }

        fun stop() {
            running = false
            try { serverSocket?.close() } catch (_: IOException) {}
        }
    }

    private inner class ProxiedConnection(
        private val client: Socket,
        private val remote: Socket,
        private val cameraConnections: Semaphore
    ) {
        private val directionsRemaining = AtomicInteger(COPY_DIRECTIONS)
        private val released = AtomicBoolean(false)

        init {
            activeConnections.add(this)
            val active = activeClientCounter.incrementAndGet()
            peakClientCounter.getAndUpdate { previous -> maxOf(previous, active) }
        }

        fun start(workers: ExecutorService) {
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()
            val remoteIn = remote.getInputStream()
            val remoteOut = remote.getOutputStream()
            var submitted = 0
            try {
                workers.submit { pump(clientIn, remoteOut) }
                submitted++
                workers.submit { pump(remoteIn, clientOut) }
                submitted++
            } catch (_: RejectedExecutionException) {
                abort()
                repeat(COPY_DIRECTIONS - submitted) { directionComplete() }
            }
        }

        private fun pump(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    output.flush()
                }
            } catch (_: IOException) {
                // Disconnect, idle timeout, or stop closed the pair.
            } finally {
                abort()
                directionComplete()
            }
        }

        fun abortBeforeStart() {
            abort()
            repeat(COPY_DIRECTIONS) { directionComplete() }
        }

        fun abort() {
            closeQuietly(client)
            closeQuietly(remote)
        }

        private fun directionComplete() {
            if (directionsRemaining.decrementAndGet() == 0 && released.compareAndSet(false, true)) {
                activeConnections.remove(this)
                activeClientCounter.decrementAndGet()
                cameraConnections.release()
                globalConnections.release()
            }
        }
    }

    private fun closeQuietly(socket: Socket) {
        try { socket.close() } catch (_: IOException) {}
    }

    private companion object {
        const val FORWARDED_PORT_COUNT = 8
        const val COPY_DIRECTIONS = 2
        const val COPY_BUFFER_BYTES = 8_192
        const val MAX_CAMERAS = 4
        const val DEFAULT_CONNECTIONS_PER_CAMERA = 4
        const val DEFAULT_GLOBAL_CONNECTIONS = 16
        const val MAX_CONNECTIONS_PER_CAMERA = 16
        const val MAX_GLOBAL_CONNECTIONS = 32
        const val DEFAULT_IDLE_TIMEOUT_MS = 15_000
        const val MIN_IDLE_TIMEOUT_MS = 100
        const val MAX_IDLE_TIMEOUT_MS = 120_000
        const val CONNECT_TIMEOUT_MS = 2_000
        const val DEFAULT_STOP_TIMEOUT_MS = 2_000L
        const val MAX_STOP_TIMEOUT_MS = 5_000L
        const val DISCOVERY_THREADS = 16
        const val DISCOVERY_CONNECT_TIMEOUT_MS = 100
        const val DISCOVERY_DEADLINE_MS = 1_000L
        const val DEFAULT_LIMELIGHT_IP = "172.29.0.1"
    }
}
