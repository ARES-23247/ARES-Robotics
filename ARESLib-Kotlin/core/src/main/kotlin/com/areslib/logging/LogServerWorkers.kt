package com.areslib.logging

import fi.iki.elonen.NanoHTTPD
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Bounds connection workers and queued sockets, including clients that have not sent headers. */
internal class LogServerWorkers(workerCount: Int = 4, queueCapacity: Int = 16, daemon: Boolean = true) : NanoHTTPD.AsyncRunner {
    private val guard = Any()
    private val clients = Collections.newSetFromMap(IdentityHashMap<NanoHTTPD.ClientHandler, Boolean>())
    private var isClosed = false
    private val threadNumber = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        workerCount, workerCount, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(queueCapacity),
        { runnable -> Thread(runnable, "ARES-Log-HTTP-${threadNumber.incrementAndGet()}").apply { isDaemon = daemon } }
    ).apply { allowCoreThreadTimeOut(true) }

    override fun exec(client: NanoHTTPD.ClientHandler) {
        val accepted = synchronized(guard) {
            if (isClosed) false else {
                clients.add(client)
                try { executor.execute(client); true }
                catch (_: RejectedExecutionException) { clients.remove(client); false }
            }
        }
        if (!accepted) client.close()
    }

    override fun closed(client: NanoHTTPD.ClientHandler) {
        synchronized(guard) { clients.remove(client) }
    }

    override fun closeAll() {
        val closing = synchronized(guard) {
            if (isClosed) return
            isClosed = true
            executor.shutdownNow()
            clients.toList().also { clients.clear() }
        }
        closing.forEach { it.close() }
    }
}
