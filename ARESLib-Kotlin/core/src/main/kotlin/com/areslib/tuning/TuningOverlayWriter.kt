package com.areslib.tuning

/** One in-flight snapshot and one replaceable pending snapshot; no disk work on the caller. */
internal class TuningOverlayWriter(private val write: (TuningProfileDocument) -> Unit) : AutoCloseable {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // wait/notify keep the idle worker parked without a queue.
    private val monitor = Object()
    private var pending: TuningProfileDocument? = null
    private var permitted = false
    private var closing = false
    private var worker: Thread? = null

    @Volatile var failure: Throwable? = null
        private set

    fun submit(snapshot: TuningProfileDocument) = synchronized(monitor) {
        check(!closing) { "Tuning overlay writer is closed" }
        pending = snapshot
        permitted = true
        if (worker == null) {
            Thread(::run, "ARES-local-tuning-writer").apply {
                isDaemon = true
                start()
                worker = this
            }
        }
        monitor.notifyAll()
    }

    /** A failed snapshot is retried only on a later owner poll, a newer proposal, or close. */
    fun retry() = synchronized(monitor) {
        if (!closing && pending != null) {
            permitted = true
            monitor.notifyAll()
        }
    }

    private fun run() {
        while (true) {
            val snapshot = synchronized(monitor) {
                while (pending == null || !permitted) {
                    if (closing) return
                    monitor.wait()
                }
                requireNotNull(pending).also { pending = null; permitted = false }
            }
            var writeFailure: Throwable? = null
            try { write(snapshot) } catch (error: Throwable) { writeFailure = error }
            synchronized(monitor) {
                failure = writeFailure
                if (writeFailure != null && pending == null) {
                    pending = snapshot
                    // close makes one final attempt and surfaces failure instead of retrying forever.
                    if (closing) return
                }
                if (closing) permitted = pending != null
            }
        }
    }

    /** Drain after hardware is safe and owner updates have stopped; filesystem teardown may block. */
    override fun close() {
        val ownedWorker = synchronized(monitor) {
            closing = true
            permitted = pending != null
            monitor.notifyAll()
            worker
        }
        var interrupted = false
        if (ownedWorker != null) {
            check(ownedWorker !== Thread.currentThread()) { "A tuning writer cannot join itself" }
            while (ownedWorker.isAlive) {
                try { ownedWorker.join() } catch (_: InterruptedException) { interrupted = true }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        failure?.let { throw it }
    }
}
