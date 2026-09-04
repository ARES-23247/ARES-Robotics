package com.ares.analytics.service

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Serializes and rate-limits non-blocking desktop alert tones. */
internal class AlertAudioNotifier {
    private val mutex = Mutex()
    private var lastBeepTime = 0L

    fun trigger(scope: CoroutineScope) {
        val now = System.currentTimeMillis()
        if (now - lastBeepTime <= MINIMUM_INTERVAL_MS) return
        lastBeepTime = now
        scope.launch(Dispatchers.IO) {
            if (!mutex.tryLock()) return@launch
            try {
                runCatching {
                    playBeepTone(1000f, 100)
                    delay(50)
                    playBeepTone(1200f, 150)
                }
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun playBeepTone(frequency: Float, durationMs: Int) {
        val sampleRate = 8000f
        val buffer = ByteArray((durationMs * sampleRate / 1000).toInt()) { index ->
            val angle = index / (sampleRate / frequency) * 2.0 * Math.PI
            (Math.sin(angle) * 127.0).toInt().toByte()
        }
        val format = AudioFormat(sampleRate, 8, 1, true, true)
        AudioSystem.getSourceDataLine(format).use { line ->
            line.open(format)
            line.start()
            line.write(buffer, 0, buffer.size)
            line.drain()
        }
    }

    private companion object {
        const val MINIMUM_INTERVAL_MS = 1_500L
    }
}
