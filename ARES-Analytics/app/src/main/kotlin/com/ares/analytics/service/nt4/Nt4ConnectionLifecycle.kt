package com.ares.analytics.service.nt4

import com.ares.analytics.service.Nt4ConnectionMetrics
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** Owns the WebSocket clients, retry loop, and connection lifecycle for one NT4 target at a time. */
internal class Nt4ConnectionLifecycle(
    private val scope: CoroutineScope,
    private val inboundRouter: Nt4InboundRouter,
    private val outboundPublisher: Nt4OutboundPublisher,
    private val subscriptionPrefixes: List<String>,
    private val deleteLiveTelemetry: suspend () -> Unit,
    private val flushPendingFrames: suspend () -> Boolean,
    private val clearLiveTargetState: () -> Unit
) {
    @Volatile
    private var localClient: HttpClient? = null

    @Volatile
    private var remoteClient: HttpClient? = null

    @Volatile
    var serverIp: String = "127.0.0.1"
        private set

    private val connectedState = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = connectedState.asStateFlow()

    private val connectionAttempts = AtomicLong()
    private val successfulConnections = AtomicLong()
    private val lifecycleGeneration = AtomicLong()
    private val lifecycleMonitor = Any()
    private val connectionMutex = Mutex()
    private var clientJob: Job? = null
    private var startJob: Job? = null

    fun metrics(): Nt4ConnectionMetrics = Nt4ConnectionMetrics(
        attempts = connectionAttempts.get(),
        successfulConnections = successfulConnections.get(),
        reconnects = (successfulConnections.get() - 1L).coerceAtLeast(0L),
        connected = connectedState.value
    )

    fun start(host: String, port: Int, target: Nt4TargetIdentity) {
        println(
            "[Nt4ClientService] start() called with host=$host, port=$port, teamId=${target.teamId}, " +
                "seasonId=${target.seasonId}, robotId=${target.robotId}"
        )
        val generation = lifecycleGeneration.incrementAndGet()
        val nextStart = scope.launch(start = CoroutineStart.LAZY) {
            connectionMutex.withLock {
                if (generation != lifecycleGeneration.get()) return@withLock
                clientJob?.cancelAndJoin()
                while (isActive && generation == lifecycleGeneration.get() && !flushPendingFrames()) {
                    delay(250L)
                }
                if (generation != lifecycleGeneration.get()) return@withLock
                clearLiveTargetState()
                clientJob = launchClient(host, port, target)
            }
        }
        val previousStart = synchronized(lifecycleMonitor) {
            val previous = startJob
            startJob = nextStart
            previous
        }
        previousStart?.cancel()
        nextStart.start()
    }

    suspend fun stop() {
        lifecycleGeneration.incrementAndGet()
        val pendingStart = synchronized(lifecycleMonitor) {
            val pending = startJob
            startJob = null
            pending
        }
        pendingStart?.cancelAndJoin()
        connectionMutex.withLock {
            clientJob?.cancelAndJoin()
            clientJob = null
        }
        connectedState.value = false
        synchronized(this) {
            localClient?.close()
            localClient = null
            remoteClient?.close()
            remoteClient = null
        }
    }

    private fun CoroutineScope.launchClient(
        host: String,
        port: Int,
        target: Nt4TargetIdentity
    ): Job = launch {
        try {
            deleteLiveTelemetry()
        } catch (exception: Exception) {
            exception.printStackTrace()
        }

        launch {
            while (isActive) {
                delay(1_000L)
                flushPendingFrames()
            }
        }

        var retryDelay = INITIAL_RETRY_DELAY_MS
        while (isActive) {
            val clientName = "ARES-Analytics-${System.currentTimeMillis()}"
            val path = "/nt/$clientName"
            val url = "ws://$host:$port$path"
            serverIp = host
            var connectedAtMs: Long? = null
            try {
                connectionAttempts.incrementAndGet()
                println("[Nt4ClientService] Attempting to connect to $url (engine=OkHttp)")
                clientFor(host).webSocket(
                    method = HttpMethod.Get,
                    host = host,
                    port = port,
                    path = path,
                    request = {
                        header("Sec-WebSocket-Protocol", "v4.1.networktables.first.wpi.edu")
                    }
                ) {
                    println("[Nt4ClientService] Connected to $url successfully!")
                    successfulConnections.incrementAndGet()
                    connectedState.value = true
                    connectedAtMs = System.currentTimeMillis()
                    outboundPublisher.attach(this)
                    inboundRouter.clear()
                    try {
                        send(Frame.Text(outboundPublisher.fixedPublishMessage()))
                        send(Frame.Text(subscriptionMessage()))
                        for (publishMessage in outboundPublisher.dynamicPublishMessages()) {
                            send(Frame.Text(publishMessage))
                        }

                        val clockSyncJob = launch {
                            while (isActive) {
                                try {
                                    outboundPublisher.sendTimeSyncRequest()
                                } catch (_: Exception) {
                                    break
                                }
                                delay(1_000L)
                            }
                        }
                        try {
                            while (isActive) {
                                when (val frame = withTimeout(RECEIVE_TIMEOUT_MS) { incoming.receive() }) {
                                    is Frame.Text -> inboundRouter.handleText(frame.readText(), target)
                                    is Frame.Binary -> inboundRouter.handleBinary(frame.readBytes(), target)
                                    else -> Unit
                                }
                            }
                        } finally {
                            clockSyncJob.cancel()
                        }
                    } finally {
                        val reason = withContext(NonCancellable) {
                            withTimeoutOrNull(CLOSE_HANDSHAKE_TIMEOUT_MS) { closeReason.await() }
                        }
                        if (reason != null) {
                            println(
                                "[Nt4ClientService] Connection to $url closed. Reason: ${reason.message} " +
                                    "(Code: ${reason.code})"
                            )
                        } else {
                            println("[Nt4ClientService] Connection to $url closed without a peer close handshake.")
                        }
                        outboundPublisher.detach(this)
                        connectedState.value = false
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                println("[Nt4ClientService] Error connecting to $url: ${exception.message}")
                connectedState.value = false
            }
            if (isActive) {
                val wasHealthy = connectedAtMs?.let {
                    System.currentTimeMillis() - it >= HEALTHY_CONNECTION_MS
                } == true
                delay(if (wasHealthy) INITIAL_RETRY_DELAY_MS else retryDelay)
                retryDelay = if (wasHealthy) {
                    INITIAL_RETRY_DELAY_MS
                } else {
                    (retryDelay * 2L).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun subscriptionMessage(): String {
        val topics = subscriptionPrefixes.joinToString(",") { "\"$it\"" }
        return """
            [
              {
                "method": "subscribe",
                "params": {
                  "topics": [$topics],
                  "subuid": 1,
                  "options": {
                    "prefix": true,
                    "logging": true
                  }
                }
              }
            ]
        """.trimIndent()
    }

    private fun clientFor(host: String): HttpClient = when (host) {
        "127.0.0.1", "localhost" -> getOrCreateLocalClient()
        else -> getOrCreateRemoteClient()
    }

    private fun getOrCreateLocalClient(): HttpClient {
        var client = localClient
        if (client == null || !client.coroutineContext.isActive) {
            synchronized(this) {
                client = localClient
                if (client == null || !client.coroutineContext.isActive) {
                    client = HttpClient(OkHttp) { install(WebSockets) }
                    localClient = client
                }
            }
        }
        return requireNotNull(client)
    }

    private fun getOrCreateRemoteClient(): HttpClient {
        var client = remoteClient
        if (client == null || !client.coroutineContext.isActive) {
            synchronized(this) {
                client = remoteClient
                if (client == null || !client.coroutineContext.isActive) {
                    client = HttpClient(OkHttp) { install(WebSockets) }
                    remoteClient = client
                }
            }
        }
        return requireNotNull(client)
    }

    companion object {
        private const val RECEIVE_TIMEOUT_MS = 5_000L
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 10_000L
        private const val HEALTHY_CONNECTION_MS = 10_000L
        private const val CLOSE_HANDSHAKE_TIMEOUT_MS = 1_000L
    }
}
