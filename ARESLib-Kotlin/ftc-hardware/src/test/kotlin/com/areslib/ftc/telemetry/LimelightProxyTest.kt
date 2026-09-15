package com.areslib.ftc.telemetry

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals

class LimelightProxyTest {
    @Test
    fun `camera membership is snapshotted before caller mutation`() {
        val base = findFreeEightPortBlock()
        val cameras = mutableListOf(LimelightConfig("Snapshot", "127.0.0.1", base - 5800))
        val proxy = LimelightProxy(cameras)
        cameras.clear()
        try {
            proxy.start()
            assertThrows(IOException::class.java) { ServerSocket(base).close() }
        } finally { proxy.stop() }
    }

    @Test
    fun `occupied listener fails startup synchronously and rolls back previous bindings`() {
        val base = findFreeEightPortBlock()
        val proxy = LimelightProxy(listOf(LimelightConfig("Collision", "127.0.0.1", base - 5800)))
        try {
            ServerSocket(base + 3).use {
                assertThrows(IOException::class.java) { proxy.start() }
                repeat(3) { offset -> ServerSocket(base + offset).close() }
            }
            proxy.start()
            assertThrows(IOException::class.java) { ServerSocket(base).close() }
        } finally { proxy.stop() }
    }

    @Test
    fun `invalid port intervals and overlapping cameras are rejected before startup`() {
        for (offset in listOf(Int.MIN_VALUE, -5800, 65529 - 5800, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                LimelightProxy(listOf(LimelightConfig("Invalid local", "127.0.0.1", offset)))
            }
            assertThrows(IllegalArgumentException::class.java) {
                LimelightProxy(listOf(LimelightConfig("Invalid remote", "127.0.0.1", targetPortOffset = offset)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            LimelightProxy(listOf(LimelightConfig("A", "127.0.0.1"), LimelightConfig("B", "127.0.0.1", 7)))
        }
    }

    /** Queued execution is held deterministically; no production executor hooks are added. */
    private class HeldExecutor(private val rejectAfter: Int = Int.MAX_VALUE) : AbstractExecutorService() {
        val commands = mutableListOf<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) {
            if (stopped || commands.size >= rejectAfter) throw RejectedExecutionException()
            commands.add(command)
        }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return commands.toMutableList().also { commands.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
    }

    private class MemorySocket(private val input: InputStream = ByteArrayInputStream(byteArrayOf())) : Socket() {
        private val output = ByteArrayOutputStream()
        override fun getInputStream() = input
        override fun getOutputStream() = output
    }

    private fun installConnection(proxy: LimelightProxy, executor: HeldExecutor, client: Socket = MemorySocket()): Pair<Any, Semaphore> {
        proxy.javaClass.getDeclaredField("copyExecutor").apply { isAccessible = true }.set(proxy, executor)
        val global = proxy.javaClass.getDeclaredField("globalConnections").apply { isAccessible = true }.get(proxy) as Semaphore
        assertTrue(global.tryAcquire())
        val camera = Semaphore(0)
        val type = proxy.javaClass.declaredClasses.single { it.simpleName == "ProxiedConnection" }
        val connection = type.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(proxy, client, MemorySocket(), camera)
        type.getDeclaredMethod("start", ExecutorService::class.java).apply { isAccessible = true }.invoke(connection, executor)
        return connection to camera
    }

    @Test
    fun `stop releases permits for both copy workers discarded before execution`() {
        val proxy = LimelightProxy(listOf(LimelightConfig("Queued", "127.0.0.1")))
        val executor = HeldExecutor()
        val (_, camera) = installConnection(proxy, executor)
        assertEquals(1, proxy.activeClientCount)
        proxy.stop()
        assertEquals(0, proxy.activeClientCount)
        assertEquals(1, camera.availablePermits())
        proxy.stop()
        assertEquals(1, camera.availablePermits(), "Cleanup must release permits exactly once")
    }

    @Test
    fun `second worker rejection releases ownership of the queued first worker`() {
        val proxy = LimelightProxy(listOf(LimelightConfig("Rejected", "127.0.0.1")))
        val executor = HeldExecutor(rejectAfter = 1)
        val (_, camera) = installConnection(proxy, executor)
        proxy.stop()
        assertEquals(0, proxy.activeClientCount)
        assertEquals(1, camera.availablePermits())
    }

    @Test
    fun `completed first direction cancels pending peer without double release`() {
        val proxy = LimelightProxy(listOf(LimelightConfig("EOF", "127.0.0.1")))
        val executor = HeldExecutor()
        val (_, camera) = installConnection(proxy, executor)
        try {
            executor.commands.first().run()
            assertEquals(0, proxy.activeClientCount)
            assertEquals(1, camera.availablePermits())
            executor.commands.last().run()
            assertEquals(1, camera.availablePermits())
        } finally { proxy.stop() }
    }

    @Test
    fun `running direction retains its permit until its finally block exits`() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int = error("bulk read expected")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                check(finish.await(2, TimeUnit.SECONDS))
                return -1
            }
        }
        val proxy = LimelightProxy(listOf(LimelightConfig("Running", "127.0.0.1")))
        val executor = HeldExecutor()
        val (_, camera) = installConnection(proxy, executor, MemorySocket(input))
        val worker = Thread(executor.commands.first())
        try {
            worker.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            proxy.stop()
            assertEquals(1, proxy.activeClientCount)
            assertEquals(0, camera.availablePermits())
        } finally {
            finish.countDown(); worker.join(2000); proxy.stop()
        }
        assertTrue(!worker.isAlive)
        assertEquals(0, proxy.activeClientCount)
        assertEquals(1, camera.availablePermits())
    }

    @Test
    fun `worker queue has a fixed capacity tied to the global connection limit`() {
        val base = findFreeEightPortBlock()
        val proxy = LimelightProxy(listOf(LimelightConfig("Queue", "127.0.0.1", base - 5800)), maxConnectionsGlobal = 1)
        try {
            proxy.start()
            proxy.start()
            val workers = proxy.javaClass.getDeclaredField("copyExecutor").apply { isAccessible = true }.get(proxy) as ThreadPoolExecutor
            assertEquals(2, workers.maximumPoolSize)
            assertEquals(2, workers.queue.remainingCapacity())
        } finally { proxy.stop() }
    }

    @Test
    fun `bidirectional forwarding preserves data larger than a copy buffer`() {
        val remoteServer = ServerSocket(findFreeEightPortBlock()).apply { soTimeout = 2000 }
        val base = findFreeEightPortBlock()
        val proxy = LimelightProxy(listOf(LimelightConfig("Bytes", "127.0.0.1", base - 5800,
            remoteServer.localPort - 5800)), maxConnectionsGlobal = 1)
        val payload = ByteArray(100_003).also { java.util.Random(6601).nextBytes(it) }
        val failure = AtomicReference<Throwable?>()
        val clientFinished = CountDownLatch(1)
        val remote = Thread {
            try {
                remoteServer.accept().use { socket ->
                    socket.soTimeout = 2000
                    val received = socket.getInputStream().readNBytes(payload.size)
                    assertContentEquals(payload, received)
                    socket.getOutputStream().write(received)
                    socket.getOutputStream().flush()
                    check(clientFinished.await(2, TimeUnit.SECONDS))
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            remote.start(); proxy.start()
            Socket("127.0.0.1", base).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write(payload)
                socket.getOutputStream().flush()
                assertContentEquals(payload, socket.getInputStream().readNBytes(payload.size))
            }
        } finally {
            clientFinished.countDown(); proxy.stop(); remoteServer.close(); remote.join(3000)
        }
        assertTrue(!remote.isAlive)
        assertEquals(null, failure.get())
        assertEquals(0, proxy.activeClientCount)
    }

    @Test
    fun `idle clients are capped and stop meets its deadline`() {
        val remoteServer = ServerSocket(findFreeEightPortBlock())
        val localBasePort = findFreeEightPortBlock()
        val remoteBasePort = remoteServer.localPort
        val accepting = AtomicBoolean(true)
        val acceptedSockets = CopyOnWriteArrayList<Socket>()
        val acceptThread = Thread {
            while (accepting.get()) {
                try {
                    acceptedSockets.add(remoteServer.accept())
                } catch (_: IOException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val proxy = LimelightProxy(
            cameras = listOf(
                LimelightConfig(
                    name = "Bounded",
                    targetIp = "127.0.0.1",
                    localPortOffset = localBasePort - 5800,
                    targetPortOffset = remoteBasePort - 5800
                )
            ),
            maxConnectionsPerCamera = 2,
            maxConnectionsGlobal = 2,
            socketIdleTimeoutMs = 5_000,
            stopTimeoutMs = 1_000L
        )
        val clients = mutableListOf<Socket>()

        try {
            proxy.start()
            repeat(8) { clients.add(connectWithRetry(localBasePort)) }
            assertTrue(waitUntil { proxy.activeClientCount == 2 })
            Thread.sleep(100L)
            assertTrue(proxy.peakClientCount <= 2, "per-camera/global caps must bound idle clients")
            assertTrue(acceptedSockets.size <= 2, "rejected clients must not open upstream sockets")

            val elapsed = measureTimeMillis { proxy.stop() }
            assertTrue(elapsed < 1_500L, "stop took ${elapsed}ms despite a 1000ms join deadline")
            assertTrue(waitUntil { proxy.activeClientCount == 0 })
        } finally {
            proxy.stop()
            clients.forEach { socket -> try { socket.close() } catch (_: IOException) {} }
            acceptedSockets.forEach { socket -> try { socket.close() } catch (_: IOException) {} }
            accepting.set(false)
            try { remoteServer.close() } catch (_: IOException) {}
            acceptThread.join(1_000L)
        }
    }

    @Test
    fun `stop closes active stream sockets and same proxy can restart on identical ports`() {
        val remoteServer = ServerSocket(findFreeEightPortBlock())
        val localBasePort = findFreeEightPortBlock()
        val remoteBasePort = remoteServer.localPort
        val accepting = AtomicBoolean(true)
        val acceptedSockets = CopyOnWriteArrayList<Socket>()
        val twoConnections = CountDownLatch(2)
        val acceptThread = Thread {
            while (accepting.get()) {
                try {
                    acceptedSockets.add(remoteServer.accept())
                    twoConnections.countDown()
                } catch (_: IOException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "LimelightProxyTest-Remote"
            start()
        }
        val proxy = LimelightProxy(
            listOf(
                LimelightConfig(
                    name = "Test",
                    targetIp = "127.0.0.1",
                    localPortOffset = localBasePort - 5800,
                    targetPortOffset = remoteBasePort - 5800
                )
            )
        )
        var firstClient: Socket? = null
        var secondClient: Socket? = null

        try {
            proxy.start()
            firstClient = connectWithRetry(localBasePort)
            assertTrue(waitUntil { acceptedSockets.size >= 1 })

            proxy.stop()
            assertTrue(
                peerWasClosed(requireNotNull(firstClient)),
                "stop must unblock the active persistent stream"
            )

            proxy.start()
            secondClient = connectWithRetry(localBasePort)
            assertTrue(twoConnections.await(2, TimeUnit.SECONDS), "restart must accept a second proxied stream")

            proxy.stop()
            assertTrue(
                peerWasClosed(requireNotNull(secondClient)),
                "second stop must close restarted stream sockets"
            )
        } finally {
            proxy.stop()
            try { firstClient?.close() } catch (_: IOException) {}
            try { secondClient?.close() } catch (_: IOException) {}
            acceptedSockets.forEach { socket -> try { socket.close() } catch (_: IOException) {} }
            accepting.set(false)
            try { remoteServer.close() } catch (_: IOException) {}
            acceptThread.join(1_000L)
        }
    }

    private fun peerWasClosed(socket: Socket): Boolean {
        socket.soTimeout = 1_000
        return try {
            socket.getInputStream().read() == -1
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        }
    }

    private fun connectWithRetry(port: Int): Socket {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var lastFailure: IOException? = null
        while (deadline - System.nanoTime() > 0L) {
            try {
                return Socket("127.0.0.1", port)
            } catch (failure: IOException) {
                lastFailure = failure
                Thread.sleep(20L)
            }
        }
        throw AssertionError("Proxy never bound local port $port", lastFailure)
    }

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (deadline - System.nanoTime() > 0L) {
            if (predicate()) return true
            Thread.sleep(10L)
        }
        return predicate()
    }

    private fun findFreeEightPortBlock(): Int {
        repeat(100) {
            val candidate = ServerSocket(0).use { it.localPort }
            if (candidate > 65_527) return@repeat
            val reservations = mutableListOf<ServerSocket>()
            try {
                repeat(8) { offset -> reservations.add(ServerSocket(candidate + offset)) }
                return candidate
            } catch (_: IOException) {
                // Try another ephemeral base.
            } finally {
                reservations.forEach { socket -> try { socket.close() } catch (_: IOException) {} }
            }
        }
        throw AssertionError("Could not reserve eight contiguous loopback ports")
    }
}
