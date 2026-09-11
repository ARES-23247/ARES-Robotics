package org.firstinspires.ftc.teamcode

/** Owns partially acquired calibration-client resources and attempts every shutdown step. */
internal class CalibrationVerificationResources : AutoCloseable {
    private val resources = mutableListOf<AutoCloseable>()
    private val beforeClose = mutableListOf<() -> Unit>()
    private var closed = false

    fun <T : AutoCloseable> own(resource: T): T {
        check(!closed)
        resources.add(resource)
        return resource
    }

    fun beforeClose(action: () -> Unit) {
        check(!closed)
        beforeClose.add(action)
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (next: Throwable) {
                val first = failure
                if (first == null) failure = next
                else if (first !== next) first.addSuppressed(next)
            }
        }
        beforeClose.forEach { attempt(it) }
        resources.asReversed().forEach { resource -> attempt { resource.close() } }
        beforeClose.clear()
        resources.clear()
        failure?.let { throw it }
    }
}
