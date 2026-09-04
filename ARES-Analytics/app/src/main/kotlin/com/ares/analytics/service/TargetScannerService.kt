package com.ares.analytics.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Background network polling service discovering online telemetry hosts (Local Simulation & Live Robot).
 *
 * Periodically attempts socket connections to the selected platform link port, updating active online status flows
 * for local desktop simulators (`127.0.0.1:5810`) and live wireless robot control hubs.
 *
 * ### Thread Safety & Performance Guarantees:
 * Executes non-blocking socket connection attempts in a background coroutine loop on [Dispatchers.IO].
 *
 * @see Nt4ClientService
 */
class TargetScannerService {
    private val _isLocalSimOnline = MutableStateFlow(false)
    val isLocalSimOnline: StateFlow<Boolean> = _isLocalSimOnline.asStateFlow()

    private val _isLiveRobotOnline = MutableStateFlow(false)
    val isLiveRobotOnline: StateFlow<Boolean> = _isLiveRobotOnline.asStateFlow()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scannerJob: Job? = null

    /**
     * Starts continuous socket polling for local and remote robot telemetry endpoints.
     *
     * @param liveRobotHost IP address or hostname of the live robot controller (e.g., `"192.168.43.1"`).
     * @param port platform link port: NT4 uses 5810 and XRP uses its dedicated JSON link port.
     */
    fun startScanning(liveRobotHost: String, port: Int = 5810) {
        scannerJob?.cancel()
        scannerJob = serviceScope.launch {
            while (isActive) {
                // Check Local Sim
                _isLocalSimOnline.value = checkPort("127.0.0.1", port)

                // Check Live Robot
                _isLiveRobotOnline.value = checkPort(liveRobotHost, port)

                delay(2000) // Poll every 2 seconds
            }
        }
    }

    private fun checkPort(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 200)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun stopScanning() {
        scannerJob?.cancel()
        scannerJob = null
    }
}
