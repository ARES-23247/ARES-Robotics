package com.ares.analytics.service.log

import com.ares.analytics.service.FrameBatcher
import java.io.File

/**
 * Abstract base contract for binary and text telemetry log file decoders.
 *
 * Defines the standard asynchronous decoding interface implemented by log format processors
 * (e.g. WPILib `.wpilog`, AdvantageKit `.rlog`, CTRE `.hoot`, REV `.revlog`, RoadRunner CSV/JSON).
 * Decoded telemetry samples use a frame-count-bounded [FrameBatcher]. Implementations must also
 * bound individual record sizes; batching alone does not guarantee a byte-level heap limit.
 *
 * ### Thread Safety & Performance Guarantees:
 * Implementations run within background coroutine contexts on `Dispatchers.IO`. Decoders must process
 * files sequentially or chunked in streams to adhere to GC memory allocation constraints.
 *
 * @see CsvLogDecoder
 * @see JsonlLogDecoder
 * @see WpiLogDecoder
 * @see RlogDecoderService
 * @see HootDecoderService
 * @see RevlogDecoderService
 */
abstract class BaseLogDecoder {
    /**
     * Decodes a target telemetry log file asynchronously and pushes normalized frames into the batcher.
     *
     * @param file Target log file on the local filesystem.
     * @param sessionId Unique session identifier assigned to the imported log.
     * @param batcher Sequential frame buffer receiving extracted [com.ares.analytics.shared.models.TelemetryFrame] records.
     * @throws java.io.IOException If the log file cannot be read or contains unrecoverable corruption.
     */
    abstract suspend fun decode(
        file: File,
        sessionId: String,
        batcher: FrameBatcher
    )
}

