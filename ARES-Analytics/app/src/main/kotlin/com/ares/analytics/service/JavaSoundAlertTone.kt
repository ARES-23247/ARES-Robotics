package com.ares.analytics.service

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.sin

/** One finite preloaded clip; cancellation resumes cleanup on the IO dispatcher, not the caller. */
internal class JavaSoundAlertTone(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clipFactory: () -> Clip = AudioSystem::getClip,
) {
    suspend fun play(): Unit = withContext(dispatcher) {
        withTimeout(2_000L) {
            clipFactory().use { clip ->
                var listener: LineListener? = null
                // Nested use preserves a primary failure if listener removal or line closure also fails.
                java.lang.AutoCloseable { listener?.let { clip.removeLineListener(it) } }.use {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val finished = AtomicBoolean(false)
                        val callback = LineListener { event ->
                            if (event.line === clip &&
                                (event.type == LineEvent.Type.STOP || event.type == LineEvent.Type.CLOSE) &&
                                finished.compareAndSet(false, true)) continuation.resume(Unit)
                        }
                        listener = callback
                        clip.addLineListener(callback)
                        clip.open(format, pcm, 0, pcm.size)
                        if (continuation.isActive) clip.start()
                    }
                }
            }
        }
    }

    private companion object {
        val format = AudioFormat(8_000f, 8, 1, true, true)
        val pcm = createAlertTonePcm()
    }
}

/** Signed 8-bit mono at 8 kHz: 100 ms at 1 kHz, 50 ms silence, 150 ms at 1.2 kHz. */
internal fun createAlertTonePcm(): ByteArray = ByteArray(2_400).also { pcm ->
    repeat(800) { index -> pcm[index] = (sin(2.0 * PI * 1_000.0 * index / 8_000.0) * 127.0).toInt().toByte() }
    repeat(1_200) { index -> pcm[1_200 + index] = (sin(2.0 * PI * 1_200.0 * index / 8_000.0) * 127.0).toInt().toByte() }
}
