package com.areslib.hardware

import com.areslib.telemetry.ITelemetry
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import com.areslib.hardware.actuator.*

/**
 * Process-wide owner registry for hardware refresh, safety, telemetry, topology, and shutdown.
 *
 * Registration is normally performed once during robot construction. Device collections are
 * copy-on-write/concurrent so telemetry and background polling can inspect them safely, but repeated
 * registration of the same logical name still appends lifecycle entries and should be avoided.
 * Hardware exceptions are isolated during best-effort safety, telemetry, and close passes.
 *
 * The polling daemon services at most one regular and one round-robin device per interval. Polled
 * devices must cache results for robot-loop getters and must not throw from [SyncPolledDevice.pollSync].
 */
object HardwareRegistry {
    private val devices = ConcurrentHashMap<String, LoggableDevice>()
    private val gson = Gson()
    private val devicesList = CopyOnWriteArrayList<LoggableDevice>()
    private val devicesNamesList = CopyOnWriteArrayList<String>()
    private val devicesPrefixList = CopyOnWriteArrayList<String>()
    private val closeables = CopyOnWriteArrayList<AutoCloseable>()
    private val topologyNodes = ConcurrentHashMap<String, TopologyNode>()
    private val cachedMotorsWithNames = ConcurrentHashMap<String, MotorIO>()
    private val cachedMotorsList = CopyOnWriteArrayList<MotorIO>()
    private val syncPolledDevices = CopyOnWriteArrayList<SyncPolledDevice>()
    private val roundRobinDevices = CopyOnWriteArrayList<SyncPolledDevice>()
    
    @Volatile private var pollingRunning = false
    private var pollingThread: Thread? = null
    @Volatile private var pollingIntervalMs: Long = 50L

    /**
     * Sets the delay between polling passes. Runtime values below 10 ms are clamped by the worker.
     */
    fun setPollingIntervalMs(intervalMs: Long) {
        pollingIntervalMs = intervalMs
    }

    /**
     * Registers a lifecycle resource for best-effort closure by [closeAll].
     */
    fun registerCloseable(closeable: AutoCloseable) {
        closeables.add(closeable)
    }

    /**
     * Adds [device] to the primary polling list and starts the daemon on first registration.
     * Duplicate object registrations in this list are ignored.
     */
    fun registerSyncPolledDevice(device: SyncPolledDevice) {
        if (!syncPolledDevices.contains(device)) {
            syncPolledDevices.add(device)
        }
        startPollingThreadIfNeeded()
    }

    /**
     * Adds [device] to the secondary round-robin list and starts the daemon if needed.
     * One entry from this list is serviced per pass independently of the primary list.
     */
    fun registerRoundRobinDevice(device: SyncPolledDevice) {
        if (!roundRobinDevices.contains(device)) {
            roundRobinDevices.add(device)
        }
        startPollingThreadIfNeeded()
    }

    @Synchronized
    private fun startPollingThreadIfNeeded() {
        if (pollingThread == null || !(pollingThread!!.isAlive)) {
            pollingRunning = true
            pollingThread = Thread {
                var index = 0
                var roundRobinIndex = 0
                while (pollingRunning) {
                    var polledAny = false
                    if (syncPolledDevices.isNotEmpty()) {
                        val idx = index % syncPolledDevices.size
                        syncPolledDevices[idx].pollSync()
                        index++
                        polledAny = true
                    }
                    if (roundRobinDevices.isNotEmpty()) {
                        val idx = roundRobinIndex % roundRobinDevices.size
                        roundRobinDevices[idx].pollSync()
                        roundRobinIndex++
                        polledAny = true
                    }
                    if (polledAny) {
                        try { Thread.sleep(kotlin.math.max(10L, pollingIntervalMs)) } catch (_: InterruptedException) { break }
                    } else {
                        try { Thread.sleep(50L) } catch (_: InterruptedException) { break }
                    }
                }
            }.apply {
                isDaemon = true
                name = "ARES-HardwarePolling-Thread"
                start()
            }
        }
    }

    /**
     * Registers [device] under [name] for telemetry and lifecycle operations.
     * Names should be unique; reusing a name replaces map lookup data but does not remove the prior
     * device from ordered refresh/publish lists.
     */
    fun registerDevice(name: String, device: LoggableDevice) {
        devices[name] = device
        devicesList.add(device)
        devicesNamesList.add(name)
        devicesPrefixList.add("Hardware/$name")
        if (device is MotorIO) {
            val shortName = if (name.startsWith("Motors/")) name.substring("Motors/".length) else name
            cachedMotorsWithNames[shortName] = device
            if (!cachedMotorsList.contains(device)) {
                cachedMotorsList.add(device)
            }
        }
    }

    /**
     * Registers a motor with a unique diagnostic name.
     */
    fun registerMotor(name: String, motor: MotorIO) {
        registerDevice("Motors/$name", motor)
    }

    /**
     * Registers a servo with a unique diagnostic name.
     */
    fun registerServo(name: String, servo: ServoIO) {
        registerDevice("Servos/$name", servo)
    }

    // ────────────────────────────────────────────────────────────────────────────
    // FTC Topology Overloads
    // ────────────────────────────────────────────────────────────────────────────

    fun registerMotor(name: String, motor: MotorIO, parentHub: String, port: Int) {
        val cleanName = "Motors/$name"
        registerMotor(name, motor)
        topologyNodes[cleanName] = TopologyNode(
            id = cleanName,
            type = TopologyNodeType.MOTOR,
            displayName = name,
            parentId = parentHub,
            port = port
        )
    }

    /** Registers an FTC servo and records its parent hub and zero-based port for topology export. */
    fun registerServo(name: String, servo: ServoIO, parentHub: String, port: Int) {
        val cleanName = "Servos/$name"
        registerServo(name, servo)
        topologyNodes[cleanName] = TopologyNode(
            id = cleanName,
            type = TopologyNodeType.SERVO,
            displayName = name,
            parentId = parentHub,
            port = port
        )
    }

    // ────────────────────────────────────────────────────────────────────────────
    // FRC CAN Topology Overloads
    // ────────────────────────────────────────────────────────────────────────────

    fun registerMotor(name: String, motor: MotorIO, canBus: String, canId: Int, busPosition: Int? = null) {
        val cleanName = "Motors/$name"
        registerMotor(name, motor)
        topologyNodes[cleanName] = TopologyNode(
            id = cleanName,
            type = TopologyNodeType.CAN_MOTOR_CONTROLLER,
            displayName = name,
            canId = canId,
            canBus = canBus,
            busPosition = busPosition
        )
    }

    /** Registers a CAN device and derives its topology type from its logical [name]. */
    fun registerDevice(name: String, device: LoggableDevice, canBus: String, canId: Int, busPosition: Int? = null) {
        registerDevice(name, device)
        topologyNodes[name] = TopologyNode(
            id = name,
            type = getDeviceNodeType(name),
            displayName = name.split("/").last(),
            canId = canId,
            canBus = canBus,
            busPosition = busPosition
        )
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Generic Topology Overload & Builder
    // ────────────────────────────────────────────────────────────────────────────

    fun registerDevice(name: String, device: LoggableDevice, topology: TopologyNode) {
        registerDevice(name, device)
        topologyNodes[name] = topology
    }

    /** Builds a point-in-time topology snapshot. Concurrent-map node order is unspecified. */
    fun buildTopology(robotId: String): HardwareTopology {
        return HardwareTopology(robotId, topologyNodes.values.toList())
    }

    /** Serializes the current topology snapshot as JSON for dashboard discovery. */
    fun getTopologyJson(robotId: String): String {
        return gson.toJson(buildTopology(robotId))
    }

    private fun getDeviceNodeType(name: String): TopologyNodeType {
        val lower = name.lowercase()
        return when {
            lower.contains("imu") || lower.contains("gyro") -> TopologyNodeType.IMU
            lower.contains("camera") || lower.contains("vision") -> TopologyNodeType.CAMERA
            lower.contains("pinpoint") || lower.contains("odometry") -> TopologyNodeType.ODOMETRY_COMPUTER
            lower.contains("color") -> TopologyNodeType.COLOR_SENSOR
            lower.contains("distance") -> TopologyNodeType.DISTANCE_SENSOR
            lower.contains("beam") -> TopologyNodeType.BEAM_BREAK
            else -> TopologyNodeType.ANALOG_SENSOR
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Lifecycle & Batch Reads
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Returns the live, read-only-by-contract motor list used by power managers.
     * Callers must not cast and mutate the returned collection.
     */
    fun getRegisteredMotors(): List<MotorIO> {
        return cachedMotorsList
    }

    /**
     * Returns the live motor lookup keyed by the short name after an optional `Motors/` prefix.
     * Callers must treat the returned map as read-only.
     */
    fun getRegisteredMotorsWithNames(): Map<String, MotorIO> {
        return cachedMotorsWithNames
    }

    /**
     * Calls [SubsystemIO.refresh] once for every registered subsystem in registration order.
     * Unlike safety and close passes, refresh exceptions propagate to the caller.
     */
    fun refreshAll() {
        for (i in 0 until devicesList.size) {
            val device = devicesList[i]
            if (device is SubsystemIO) {
                device.refresh()
            }
        }
    }

    /**
     * Invokes every registered subsystem's fail-safe output and suppresses individual failures so
     * one broken device cannot prevent the remaining devices from being stopped.
     */
    fun safeAll() {
        for (i in 0 until devicesList.size) {
            val device = devicesList[i]
            if (device is SubsystemIO) {
                try {
                    device.safe()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Stops polling, waits up to one second for its daemon, closes registered resources on a
     * best-effort basis, and clears all registry state. Safe to call during repeated test/OpMode
     * teardown; a resource registered in both ownership lists may receive more than one close call.
     */
    fun closeAll() {
        pollingRunning = false
        val thread = pollingThread
        if (thread != null) {
            thread.interrupt()
            try { thread.join(1000) } catch (_: InterruptedException) {}
            pollingThread = null
        }
        syncPolledDevices.clear()
        roundRobinDevices.clear()

        for (i in 0 until closeables.size) {
            try {
                closeables[i].close()
            } catch (_: Exception) {}
        }
        closeables.clear()

        for (i in 0 until devicesList.size) {
            val device = devicesList[i]
            if (device is AutoCloseable) {
                try {
                    device.close()
                } catch (_: Exception) {}
            }
        }
        devices.clear()
        devicesList.clear()
        devicesNamesList.clear()
        devicesPrefixList.clear()
        topologyNodes.clear()
        cachedMotorsWithNames.clear()
        cachedMotorsList.clear()
    }

    /**
     * Clears all registered devices (useful between OpModes / tests).
     */
    fun clear() {
        closeAll()
    }

    /**
     * Publishes registered devices in registration order. Concurrent registration skew and device
     * telemetry failures are suppressed because diagnostics must not stop the robot loop.
     */
    fun publishAll(telemetry: ITelemetry) {
        try {
            val count = kotlin.math.min(devicesList.size, devicesPrefixList.size)
            for (i in 0 until count) {
                try {
                    val device = devicesList[i] as? LoggableDevice ?: continue
                    val prefix = devicesPrefixList[i] ?: ""
                    device.logTelemetry(telemetry, prefix)
                } catch (_: IndexOutOfBoundsException) { break }
            }
        } catch (_: Throwable) {}
    }
}
