package com.ares.analytics.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class CameraStreamState(
    val streamUrl: String = "http://10.0.0.2:1181/stream.mjpg",
    val isConfiguring: Boolean = false,
    val currentFrame: ImageBitmap? = null,
    val isConnected: Boolean = false,
    val errorMessage: String? = null
)

sealed class CameraStreamIntent {

    data class SetConfiguring(val isConfiguring: Boolean) : CameraStreamIntent()

    data class UpdateStreamUrl(val streamUrl: String) : CameraStreamIntent()

    object Connect : CameraStreamIntent()

    object Disconnect : CameraStreamIntent()
}

/** Owns one MJPEG connection and publishes only complete decoded JPEG frames to Compose. */
class CameraStreamViewModel(
    initialStreamUrl: String?,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(
        CameraStreamState(
            streamUrl = initialStreamUrl ?: "http://10.0.0.2:1181/stream.mjpg",
            isConfiguring = initialStreamUrl == null
        )
    )
    val state: StateFlow<CameraStreamState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private val closed = AtomicBoolean(false)
    private val streamGeneration = AtomicLong(0L)
    private val imageLock = Any()
    private var currentSkiaImage: org.jetbrains.skia.Image? = null
    private val retiredSkiaImages = ArrayDeque<org.jetbrains.skia.Image>()

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = Long.MAX_VALUE
        }
    }

    init {
        if (initialStreamUrl != null) {
            startStreaming()
        }
    }

    fun onIntent(intent: CameraStreamIntent) {
        when (intent) {
            is CameraStreamIntent.SetConfiguring -> {
                _state.update { it.copy(isConfiguring = intent.isConfiguring) }
            }
            is CameraStreamIntent.UpdateStreamUrl -> {
                _state.update { it.copy(streamUrl = intent.streamUrl) }
            }
            is CameraStreamIntent.Connect -> {
                _state.update { it.copy(isConfiguring = false) }
                startStreaming()
            }
            is CameraStreamIntent.Disconnect -> {
                stopStreaming()
            }
        }
    }

    private fun stopStreaming() {
        streamGeneration.incrementAndGet()
        streamJob?.cancel()
        streamJob = null
        _state.update { it.copy(isConnected = false, currentFrame = null) }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopStreaming()
        httpClient.close()
        val images = synchronized(imageLock) {
            buildList {
                currentSkiaImage?.let(::add)
                addAll(retiredSkiaImages)
            }.also {
                currentSkiaImage = null
                retiredSkiaImages.clear()
            }
        }
        images.forEach { image -> runCatching { image.close() } }
    }

    private fun startStreaming() {
        if (closed.get()) return
        streamJob?.cancel()
        val generation = streamGeneration.incrementAndGet()

        streamJob = scope.launch(Dispatchers.IO) {
            var retryDelayMs = 1000L
            while (isActive) {
                val currentUrl = _state.value.streamUrl
                if (_state.value.isConfiguring || currentUrl.isBlank()) {
                    delay(1000)
                    continue
                }

                _state.update { it.copy(isConnected = false, errorMessage = null) }

                try {
                    httpClient.prepareGet(currentUrl).execute { response ->
                        if (response.status.value in 200..299) {
                            _state.update { it.copy(isConnected = true) }
                            retryDelayMs = 1000L
                            val channel = response.bodyAsChannel()
                            val assembler = MjpegFrameAssembler()
                            val readBuffer = ByteArray(8192)

                            while (isActive && !channel.isClosedForRead && !_state.value.isConfiguring) {
                                val read = channel.readAvailable(readBuffer, 0, readBuffer.size)
                                if (read > 0) {
                                    assembler.offer(readBuffer, read) { frameBytes ->
                                        publishFrame(frameBytes, generation)
                                    }
                                }
                            }
                        } else {
                            _state.update { it.copy(errorMessage = "HTTP Error: ${response.status}") }
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    _state.update { it.copy(isConnected = false, errorMessage = e.message ?: "Connection failed") }
                } finally {
                    _state.update { it.copy(isConnected = false) }
                }

                if (isActive && !_state.value.isConfiguring) {
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 1.5).toLong().coerceAtMost(5000L)
                }
            }
        }
    }

    private fun publishFrame(frameBytes: ByteArray, generation: Long) {
        if (closed.get() || generation != streamGeneration.get()) return
        val skiaImage = try {
            org.jetbrains.skia.Image.makeFromEncoded(frameBytes)
        } catch (_: Exception) {
            return
        }
        val imageBitmap = try {
            skiaImage.toComposeImageBitmap()
        } catch (_: Exception) {
            skiaImage.close()
            return
        }

        var imageToClose: org.jetbrains.skia.Image? = null
        val accepted = synchronized(imageLock) {
            if (closed.get() || generation != streamGeneration.get()) {
                false
            } else {
                currentSkiaImage?.let(retiredSkiaImages::addLast)
                currentSkiaImage = skiaImage
                if (retiredSkiaImages.size > RETAINED_FRAME_COUNT) {
                    imageToClose = retiredSkiaImages.removeFirst()
                }
                true
            }
        }
        if (!accepted) {
            skiaImage.close()
            return
        }

        // Commit the new Compose frame before retiring an older native image. Keeping a short
        // queue avoids invalidating an image that the renderer still owns during recomposition.
        _state.update { it.copy(currentFrame = imageBitmap) }
        imageToClose?.close()
    }

    private companion object {
        const val RETAINED_FRAME_COUNT = 2
    }
}
