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

class LimelightProxyTest {
    @Test
    fun `idle clients are capped and stop meets its deadline`() {
        val localBasePort = findFreeEightPortBlock()
        val remoteServer = ServerSocket(0)
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
        val localBasePort = findFreeEightPortBlock()
        val remoteServer = ServerSocket(0)
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
        while (System.nanoTime() < deadline) {
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
        while (System.nanoTime() < deadline) {
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
