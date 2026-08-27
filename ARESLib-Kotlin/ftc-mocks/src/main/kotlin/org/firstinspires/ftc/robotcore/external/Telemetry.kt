@file:Suppress("UNUSED_PARAMETER")
package org.firstinspires.ftc.robotcore.external

/** Minimal FTC SDK telemetry surface required by desktop OpModes. */
interface Telemetry {
    /** Marker retained for FTC source compatibility; this mock does not return mutable items. */
    interface Item
    /** Queues a caption/value line for the next [update]. Returns `null` in [MockTelemetry]. */
    fun addData(caption: String, value: Any?): Item?
    /** Queues a formatted line for the next [update]. Returns `null` in [MockTelemetry]. */
    fun addData(caption: String, format: String, vararg args: Any?): Item?
    /** Publishes the pending batch and clears it. */
    fun update(): Boolean
}

/**
 * Thread-safe batch telemetry sink consumed by [com.areslib.sim.DesktopSimLauncher].
 *
 * Producers append under a lock. [update] atomically replaces [displayLines] with an immutable
 * snapshot and clears the pending batch. Formatting failures produce a diagnostic line instead of
 * escaping into the OpMode.
 */
class MockTelemetry : Telemetry {
    private val buffer = mutableListOf<String>()
    
    /** Most recently committed batch; safe for the simulator publisher thread to read. */
    @Volatile
    var displayLines: List<String> = emptyList()
        private set

    /** Appends one unformatted line to the pending batch. */
    override fun addData(caption: String, value: Any?): Telemetry.Item? {
        synchronized(buffer) {
            buffer.add("$caption: $value")
        }
        return null
    }
    
    /** Appends one `String.format` line, substituting `[Format Error]` on invalid input. */
    override fun addData(caption: String, format: String, vararg args: Any?): Telemetry.Item? {
        synchronized(buffer) {
            try {
                buffer.add("$caption: ${String.format(format, *args)}")
            } catch (e: Exception) {
                buffer.add("$caption: [Format Error]")
            }
        }
        return null
    }
    
    /** Commits the pending lines to [displayLines] and returns `true`. */
    override fun update(): Boolean {
        synchronized(buffer) {
            displayLines = buffer.toList()
            buffer.clear()
        }
        return true
    }
}
