
package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryFrame
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Data container describing a discovered CTRE CAN bus hardware device (TalonFX, CANcoder, Pigeon2, Kraken X60).
 *
 * @property id Unique string identifier for the device.
 * @property name Human-readable device name.
 * @property type Device hardware model type.
 * @property canId Numeric CAN Bus node ID.
 */
@Serializable
data class PhoenixDevice(
    val id: String,
    val name: String,
    val type: String,
    val canId: Int? = null
)

@Serializable
data class PhoenixTelemetryResponse(
    val deviceId: String,
    val signals: Map<String, Double>
)

class PhoenixDiagnosticsService(
    private val nt4ClientService: Nt4ClientService,
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
) {
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private var pollJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(host: String = "localhost", port: Int = 1250, pollIntervalMs: Long = 100) {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            while (isActive) {
                try {
                    httpClient.prepareGet("http://$host:$port/devices").execute { devicesResponse ->
                        if (devicesResponse.status == HttpStatusCode.OK) {
                            _isConnected.value = true
                            val devices = devicesResponse.body<List<PhoenixDevice>>()

                            for (device in devices) {
                                httpClient.prepareGet("http://$host:$port/device/${device.id}/telemetry").execute { telResponse ->
                                    if (telResponse.status == HttpStatusCode.OK) {
                                        val telData = telResponse.body<PhoenixTelemetryResponse>()
                                        val now = System.currentTimeMillis()
                                        for ((signalName, signalValue) in telData.signals) {
                                            val frame = TelemetryFrame(
                                                timestampMs = now,
                                                sessionId = "live-telemetry",
                                                key = "/Phoenix/${device.name}_${device.canId ?: 0}/$signalName",
                                                value = signalValue
                                            )
                                            nt4ClientService.publishFrame(frame)
                                        }
                                    }
                                }
                            }
                        } else {
                            _isConnected.value = false
                        }
                    }
                } catch (e: Exception) {
                    _isConnected.value = false
                }
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        _isConnected.value = false
    }

    fun dispose() {
        stop()
        serviceScope.cancel()
        httpClient.close()
    }
}
