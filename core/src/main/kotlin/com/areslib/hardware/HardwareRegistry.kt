package com.areslib.hardware

import com.areslib.telemetry.ITelemetry
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import com.areslib.hardware.actuator.*

/**
 * Global registry for automatic hardware device logging, diagnostics, power scaling, and lifecycle batch reads.
 */
object HardwareRegistry {
    private val devices = ConcurrentHashMap<String, LoggableDevice>()
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
    private var reflectionInitialized = false
    private var getActiveInstanceMethod: java.lang.reflect.Method? = null
    private var getStoreMethod: java.lang.reflect.Method? = null
    private var getStateMethod: java.lang.reflect.Method? = null
    private var getTuningMethod: java.lang.reflect.Method? = null
    private var getIntervalMethod: java.lang.reflect.Method? = null

    /**
     * Registers a closeable hardware wrapper to ensure background threads are terminated on close.
     */
    fun registerCloseable(closeable: AutoCloseable) {
        closeables.add(closeable)
    }

    /**
     * Registers a device that requires periodic synchronous polling (like motor current).
     * Automatically starts the background polling daemon if not already running.
     */
    fun registerSyncPolledDevice(device: SyncPolledDevice) {
        if (!syncPolledDevices.contains(device)) {
            syncPolledDevices.add(device)
        }
        startPollingThreadIfNeeded()
    }

    /**
     * registerRoundRobinDevice declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun registerRoundRobinDevice(device: SyncPolledDevice) {
        if (!roundRobinDevices.contains(device)) {
            roundRobinDevices.add(device)
        }
        startPollingThreadIfNeeded()
    }

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
                        
                        // Default 50ms sleep, can be overridden by tuning if FtcBaseRobot is active
                        var sleepInterval = 50L
                        if (!reflectionInitialized) {
                            reflectionInitialized = true
                            try {
                                val clazz = Class.forName("com.areslib.ftc.FtcBaseRobot")
                                getActiveInstanceMethod = clazz.getMethod("getActiveInstance")
                            } catch (_: Exception) {}
                        }
                        try {
                            val activeInstance = getActiveInstanceMethod?.invoke(null)
                            if (activeInstance != null) {
                                if (getStoreMethod == null) getStoreMethod = activeInstance.javaClass.getMethod("getStore")
                                val store = getStoreMethod?.invoke(activeInstance)
                                if (store != null) {
                                    if (getStateMethod == null) getStateMethod = store.javaClass.getMethod("getState")
                                    val state = getStateMethod?.invoke(store)
                                    if (state != null) {
                                        if (getTuningMethod == null) getTuningMethod = state.javaClass.getMethod("getTuning")
                                        val tuning = getTuningMethod?.invoke(state)
                                        if (tuning != null) {
                                            if (getIntervalMethod == null) getIntervalMethod = tuning.javaClass.getMethod("getMotorCurrentPollingIntervalMs")
                                            val interval = getIntervalMethod?.invoke(tuning) as? Long
                                            if (interval != null) sleepInterval = interval
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                        
                        try { Thread.sleep(kotlin.math.max(10L, sleepInterval)) } catch (_: InterruptedException) { break }
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
     * Registers a generic loggable device with a unique system name.
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

    /**
     * registerServo declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
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

    /**
     * registerDevice declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
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

    /**
     * buildTopology declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun buildTopology(robotId: String): HardwareTopology {
        return HardwareTopology(robotId, topologyNodes.values.toList())
    }

    /**
     * getTopologyJson declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun getTopologyJson(robotId: String): String {
        return Gson().toJson(buildTopology(robotId))
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
     * Retrieves all registered motor wrappers for power budget calculations and voltage compensation.
     */
    fun getRegisteredMotors(): List<MotorIO> {
        return cachedMotorsList
    }

    /**
     * Retrieves all registered motor wrappers mapped by their registered names.
     */
    fun getRegisteredMotorsWithNames(): Map<String, MotorIO> {
        return cachedMotorsWithNames
    }

    /**
     * Batches status updates and read transactions across all registered SubsystemIO components.
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
     * Triggers safety failsafes across all registered subsystems.
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
     * Clears all registered devices and terminates background threads.
     */
    fun closeAll() {
        pollingRunning = false
        pollingThread?.interrupt()
        pollingThread = null
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
     * Publishes the current state of all registered hardware devices to the telemetry provider.
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
