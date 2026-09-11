package org.aresfirst.marvin

import com.areslib.hardware.HardwareRegistry

/** Retain completed IO before fallible composition; normal device registration shares ownership. */
internal fun HardwareRegistry.retainFrcHardware(vararg devices: Any?) {
    for (device in devices) {
        if (device is AutoCloseable) registerCloseable(device)
    }
}

/** Run partial-initialization teardown immediately while retaining the original startup failure. */
internal inline fun initializeFrcRobot(initialize: () -> Unit, cleanup: () -> Unit) {
    try { initialize() } catch (failure: Throwable) {
        try { cleanup() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
        throw failure
    }
}
