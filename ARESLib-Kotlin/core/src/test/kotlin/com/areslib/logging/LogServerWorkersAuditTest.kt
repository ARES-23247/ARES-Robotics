package com.areslib.logging

import fi.iki.elonen.NanoHTTPD
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogServerWorkersAuditTest {
    private fun executor(workers: LogServerWorkers) = LogServerWorkers::class.java.getDeclaredField("executor")
        .apply { isAccessible = true }.get(workers) as ThreadPoolExecutor

    private fun connect(server: NanoHTTPD) = Socket("127.0.0.1", server.listeningPort).apply {
        soTimeout = 2000
        getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
        getOutputStream().flush()
    }

    private fun isClosed(socket: Socket): Boolean = try { socket.getInputStream().read() == -1 }
    catch (_: SocketTimeoutException) { false }
    catch (_: SocketException) { true }

    @Test fun `excess connections are closed and shutdown releases workers and queued sockets`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val workers = LogServerWorkers(workerCount = 2, queueCapacity = 1)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return newFixedLengthResponse("finished")
            }
        }.apply { setAsyncRunner(workers) }
        val sockets = mutableListOf<Socket>()
        try {
            server.start(1000, true)
            repeat(2) { sockets.add(connect(server)) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            sockets.add(connect(server))
            val pool = executor(workers)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (pool.queue.size != 1 && System.nanoTime() - deadline < 0) Thread.sleep(5)
            assertEquals(1, pool.queue.size)
            val excess = connect(server).also(sockets::add)
            assertTrue(isClosed(excess), "Saturated connections must be closed instead of retaining another socket")
            assertEquals(2, pool.largestPoolSize)
            server.stop()
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
            for (socket in sockets) {
                assertTrue(isClosed(socket))
            }
            workers.closeAll() // Idempotent after the server-owned shutdown.
        } finally {
            release.countDown(); server.stop(); sockets.forEach { it.close() }
        }
    }

    @Test fun `completed requests release their slot for later connections`() {
        val workers = LogServerWorkers(workerCount = 1, queueCapacity = 1)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = newFixedLengthResponse("finished")
        }.apply { setAsyncRunner(workers) }
        try {
            server.start(1000, true)
            repeat(8) {
                connect(server).use { socket ->
                    assertTrue(socket.getInputStream().bufferedReader().readText().contains("finished"))
                }
            }
            assertEquals(1, executor(workers).largestPoolSize)
        } finally {
            server.stop()
            assertTrue(executor(workers).awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
