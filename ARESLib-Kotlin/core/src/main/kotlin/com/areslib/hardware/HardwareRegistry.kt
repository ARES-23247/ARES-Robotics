package com.areslib.hardware

import com.areslib.telemetry.ITelemetry
import com.areslib.telemetry.schema.HardwareTopology
import com.areslib.telemetry.schema.HardwareTopologyCodec
import com.areslib.telemetry.schema.TopologyNode
import com.areslib.telemetry.schema.TopologyNodeType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import com.areslib.hardware.actuator.*

/**
 * Robot-instance owner registry for hardware refresh, safety, telemetry, topology, and shutdown.
 *
 * Registration is normally performed once during robot construction. Device collections are
 * copy-on-write/concurrent so telemetry and background polling can inspect them safely, but repeated
 * registration of the same logical name replaces its ordered lifecycle entry and cached lookups.
 * Hardware exceptions are isolated during best-effort safety, telemetry, and close passes.
 *
 * The polling daemon services at most one regular and one round-robin device per interval. Polled
 * devices must cache results for robot-loop getters. A device exception is isolated and
 * exponentially rate-limited in stderr so one failed sensor cannot stop polling every other sensor.
 */
class HardwareRegistry {
    private val devices = ConcurrentHashMap<String, LoggableDevice>()
    private val devicesList = CopyOnWriteArrayList<LoggableDevice>()
    // Rebuilt only during registration. Loop readers retain one stable, identity-deduplicated snapshot.
    @Volatile private var lifecycleDevices = emptyArray<SubsystemIO>()
    private class TelemetryEntry(val device: LoggableDevice, val prefix: String, val heartbeatTopic: String)
    // Written under the registration monitor; each publish pass retains one coherent array.
    private val telemetryEntries = ArrayList<TelemetryEntry>()
    @Volatile private var telemetrySnapshot = emptyArray<TelemetryEntry>()
    private val deviceIndices = ConcurrentHashMap<String, Int>()
    private val closeables = CopyOnWriteArrayList<AutoCloseable>()
    private val topologyNodes = ConcurrentHashMap<String, TopologyNode>()
    private val cachedMotorsWithNames = ConcurrentHashMap<String, MotorIO>()
    private val cachedMotorsList = CopyOnWriteArrayList<MotorIO>()
    private val registeredMotorsView: List<MotorIO> = Collections.unmodifiableList(cachedMotorsList)
    private val registeredMotorsByNameView: Map<String, MotorIO> = Collections.unmodifiableMap(cachedMotorsWithNames)
    private val cachedCurrentSourcesList = CopyOnWriteArrayList<CurrentSourceIO>()
    private val registeredCurrentSourcesView: List<CurrentSourceIO> = Collections.unmodifiableList(cachedCurrentSourcesList)
    private class PollingEntry(val device: SyncPolledDevice) {
        // Owned by the polling worker. Close discards entries before a new generation can register.
        var consecutiveFailures = 0L
    }
    private val pollingEntriesByIdentity = IdentityHashMap<SyncPolledDevice, PollingEntry>()
    @Volatile private var syncPolledDevices = emptyArray<PollingEntry>()
    @Volatile private var roundRobinDevices = emptyArray<PollingEntry>()
    private val telemetryPublishSequence = AtomicLong(0L)
    
    @Volatile private var pollingGeneration = 0L
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
    @Synchronized
    fun registerCloseable(closeable: AutoCloseable) {
        if (closeables.none { it === closeable }) closeables.add(closeable)
    }

    /**
     * Adds [device] to the primary polling list and starts the daemon on first registration.
     * Duplicate object registrations in this list are ignored.
     */
    @Synchronized
    fun registerSyncPolledDevice(device: SyncPolledDevice) {
        if (syncPolledDevices.none { it.device === device }) {
            syncPolledDevices += pollingEntry(device)
        }
        startPollingThreadIfNeeded()
    }

    /**
     * Adds [device] to the secondary round-robin list and starts the daemon if needed.
     * One entry from this list is serviced per pass independently of the primary list.
     */
    @Synchronized
    fun registerRoundRobinDevice(device: SyncPolledDevice) {
        if (roundRobinDevices.none { it.device === device }) {
            roundRobinDevices += pollingEntry(device)
        }
        startPollingThreadIfNeeded()
    }

    private fun pollingEntry(device: SyncPolledDevice): PollingEntry =
        pollingEntriesByIdentity[device] ?: PollingEntry(device).also { pollingEntriesByIdentity[device] = it }

    @Synchronized
    private fun startPollingThreadIfNeeded() {
        if (pollingThread?.isAlive == true) return
        val generation = ++pollingGeneration
        val worker = Thread {
            try {
                var index = 0
                var roundRobinIndex = 0
                while (pollingGeneration == generation) {
                    val primary = syncPolledDevices
                    val secondary = roundRobinDevices
                    if (pollingGeneration != generation) break
                    var polledAny = false
                    if (primary.isNotEmpty()) {
                        if (index >= primary.size) index = 0
                        pollSafely(primary[index])
                        // Keep the cursor bounded instead of overflowing a lifetime Int counter.
                        index = if (index == primary.lastIndex) 0 else index + 1
                        polledAny = true
                    }
                    if (pollingGeneration != generation) break
                    if (secondary.isNotEmpty()) {
                        if (roundRobinIndex >= secondary.size) roundRobinIndex = 0
                        pollSafely(secondary[roundRobinIndex])
                        roundRobinIndex = if (roundRobinIndex == secondary.lastIndex) 0 else roundRobinIndex + 1
                        polledAny = true
                    }
                    if (pollingGeneration != generation) break
                    if (polledAny) {
                        try { Thread.sleep(kotlin.math.max(10L, pollingIntervalMs)) } catch (_: InterruptedException) { break }
                    } else {
                        try { Thread.sleep(50L) } catch (_: InterruptedException) { break }
                    }
                }
            } finally {
                synchronized(this@HardwareRegistry) {
                    if (pollingThread === Thread.currentThread()) pollingThread = null
                }
            }
        }.apply {
            isDaemon = true
            name = "ARES-HardwarePolling-Thread-$generation"
        }
        pollingThread = worker
        worker.start()
    }

    private fun pollSafely(entry: PollingEntry) {
        try {
            entry.device.pollSync()
            entry.consecutiveFailures = 0L
        } catch (exception: Exception) {
            val failures = if (entry.consecutiveFailures < Long.MAX_VALUE) entry.consecutiveFailures + 1L else Long.MAX_VALUE
            entry.consecutiveFailures = failures
            if (failures == 1L || failures and (failures - 1L) == 0L) {
                System.err.println(
                    "HardwareRegistry: ${entry.device.javaClass.simpleName} polling failed " +
                        "($failures consecutive): ${exception.message}"
                )
            }
        }
    }

    /**
     * Registers [device] under [name] for telemetry and lifecycle operations.
     * Names should be unique; reusing a name replaces both map lookup data and the ordered
     * refresh/publish entry. Separately registered polling and closeable resources retain their ownership.
     */
    @Synchronized
    fun registerDevice(name: String, device: LoggableDevice) {
        registerDevice(name, "Hardware/$name", device)
    }

    /**
     * Registers a diagnostic producer whose canonical topic prefix is outside `Hardware/`.
     *
     * Generated subsystem health is a domain-level contract (`Subsystems/<id>/...`), not a
     * physical-device address. Keeping this explicit prevents the registry's normal `Hardware/`
     * namespace from silently changing that public telemetry contract.
     */
    @Synchronized
    fun registerTelemetryDevice(prefix: String, device: LoggableDevice) {
        registerDevice(prefix, prefix, device)
    }

    private fun registerDevice(name: String, telemetryPrefix: String, device: LoggableDevice) {
        require(name.isNotBlank()) { "Hardware device name must not be blank" }
        require(telemetryPrefix.isNotBlank() && !telemetryPrefix.startsWith('/')) {
            "Telemetry prefix must be non-blank and omit the leading slash"
        }
        val prior = devices.put(name, device)
        val heartbeatTopic = if (telemetryPrefix.startsWith("Subsystems/")) {
            "$telemetryPrefix/TelemetryHeartbeat"
        } else {
            ""
        }
        val existingIndex = deviceIndices[name]
        if (existingIndex == null) {
            deviceIndices[name] = devicesList.size
            devicesList.add(device)
            telemetryEntries.add(TelemetryEntry(device, telemetryPrefix, heartbeatTopic))
        } else {
            devicesList[existingIndex] = device
            telemetryEntries[existingIndex] = TelemetryEntry(device, telemetryPrefix, heartbeatTopic)
        }

        val shortName = if (name.startsWith("Motors/")) name.substring("Motors/".length) else name
        if (prior is MotorIO && prior !== device) {
            if (cachedMotorsWithNames[shortName] === prior) {
                cachedMotorsWithNames.remove(shortName)
                // A raw name and its Motors/ alias can share the same short lookup key.
                for ((otherName, otherDevice) in devices) {
                    if (otherDevice is MotorIO && otherName.removePrefix("Motors/") == shortName) {
                        cachedMotorsWithNames[shortName] = otherDevice
                        break
                    }
                }
            }
            if (devices.values.none { it === prior }) {
                val index = cachedMotorsList.indexOfFirst { it === prior }
                if (index >= 0) cachedMotorsList.removeAt(index)
            }
        }
        if (prior is CurrentSourceIO && prior !== device && devices.values.none { it === prior }) {
            val index = cachedCurrentSourcesList.indexOfFirst { it === prior }
            if (index >= 0) cachedCurrentSourcesList.removeAt(index)
        }
        if (device is MotorIO) {
            cachedMotorsWithNames[shortName] = device
            if (cachedMotorsList.none { it === device }) {
                cachedMotorsList.add(device)
            }
        }
        if (device is CurrentSourceIO && cachedCurrentSourcesList.none { it === device }) {
            cachedCurrentSourcesList.add(device)
        }
        val seen = IdentityHashMap<SubsystemIO, Boolean>()
        val lifecycle = ArrayList<SubsystemIO>()
        for (registered in devicesList) {
            if (registered is SubsystemIO && seen.put(registered, true) == null) {
                lifecycle.add(registered)
            }
        }
        lifecycleDevices = lifecycle.toTypedArray()
        telemetrySnapshot = telemetryEntries.toTypedArray()
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
        return HardwareTopology(robotId, topologyNodes.values.sortedBy { it.id })
    }

    /** Serializes the current topology snapshot as JSON for dashboard discovery. */
    fun getTopologyJson(robotId: String): String {
        return HardwareTopologyCodec.encode(buildTopology(robotId))
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
        return registeredMotorsView
    }

    /**
     * Returns the live motor lookup keyed by the short name after an optional `Motors/` prefix.
     * Callers must treat the returned map as read-only.
     */
    fun getRegisteredMotorsWithNames(): Map<String, MotorIO> {
        return registeredMotorsByNameView
    }

    /** Returns cached-current providers in registration order without performing hardware IO. */
    fun getRegisteredCurrentSources(): List<CurrentSourceIO> = registeredCurrentSourcesView

    /**
     * Calls [SubsystemIO.refresh] once per physical object in first-name registration order.
     * Aliases retain separate telemetry entries but share a single hardware read.
     * Unlike safety and close passes, refresh exceptions propagate to the caller.
     */
    fun refreshAll() {
        val snapshot = lifecycleDevices
        for (i in snapshot.indices) {
            snapshot[i].refresh()
        }
    }

    /**
     * Invokes each physical object's fail-safe output once. Ordinary exceptions remain suppressed;
     * other throwables are rethrown after every device has been attempted, with distinct additional
     * failures suppressed onto the first. A broken device cannot skip the remaining safety outputs.
     */
    fun safeAll() {
        var firstFailure: Throwable? = null
        val snapshot = lifecycleDevices
        for (i in snapshot.indices) {
            try {
                snapshot[i].safe()
            } catch (failure: Throwable) {
                if (failure !is Exception) firstFailure = retainFailure(firstFailure, failure)
            }
        }
        firstFailure?.let { throw it }
    }

    /**
     * Stops polling, waits up to one second for its daemon, closes registered resources on a
     * best-effort basis, and clears all registry state. Registered devices close before auxiliary
     * services so actuator shutdown cannot wait behind a logger drain. Resources shared between
     * ownership lists close once by identity. Safe during repeated test/OpMode teardown.
     * Ordinary close exceptions are suppressed; other throwables are rethrown only after all
     * resources have been attempted and registry state cleared. Owners must quiesce registration
     * and foreground callbacks before closing; the bounded join cannot cancel blocked device IO.
     */
    fun closeAll() {
        val thread = synchronized(this) {
            pollingGeneration++
            pollingThread.also { pollingThread = null }
        }
        if (thread != null) {
            thread.interrupt()
            try {
                thread.join(1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        syncPolledDevices = emptyArray()
        roundRobinDevices = emptyArray()
        pollingEntriesByIdentity.clear()

        val closedByIdentity = Collections.newSetFromMap(IdentityHashMap<AutoCloseable, Boolean>())
        var firstFailure: Throwable? = null
        for (i in 0 until devicesList.size) {
            val device = devicesList[i]
            if (device is AutoCloseable && closedByIdentity.add(device)) {
                try {
                    device.close()
                } catch (failure: Throwable) {
                    if (failure !is Exception) firstFailure = retainFailure(firstFailure, failure)
                }
            }
        }
        for (i in 0 until closeables.size) {
            val closeable = closeables[i]
            if (!closedByIdentity.add(closeable)) continue
            try {
                closeable.close()
            } catch (failure: Throwable) {
                if (failure !is Exception) firstFailure = retainFailure(firstFailure, failure)
            }
        }
        closeables.clear()
        devices.clear()
        devicesList.clear()
        lifecycleDevices = emptyArray()
        telemetryEntries.clear()
        telemetrySnapshot = emptyArray()
        deviceIndices.clear()
        topologyNodes.clear()
        cachedMotorsWithNames.clear()
        cachedMotorsList.clear()
        cachedCurrentSourcesList.clear()
        telemetryPublishSequence.set(0L)
        firstFailure?.let { throw it }
    }

    private fun retainFailure(primary: Throwable?, failure: Throwable): Throwable {
        if (primary == null) return failure
        if (primary !== failure && primary.suppressed.none { it === failure }) {
            primary.addSuppressed(failure)
        }
        return primary
    }

    /**
     * Clears all registered devices (useful between OpModes / tests).
     */
    fun clear() {
        closeAll()
    }

    /**
     * Publishes one coherent registration snapshot in order. Registration changes take effect on
     * the next pass. Each device and its successful heartbeat are isolated from later producers.
     * Heartbeats use exactly representable positive integers, wrapping to one before precision loss.
     */
    fun publishAll(telemetry: ITelemetry) {
        val snapshot = telemetrySnapshot
        val publishSequence = nextTelemetrySequence()
        for (i in snapshot.indices) {
            val entry = snapshot[i]
            try {
                entry.device.logTelemetry(telemetry, entry.prefix)
                if (entry.heartbeatTopic.isNotEmpty()) {
                    telemetry.putNumber(entry.heartbeatTopic, publishSequence)
                }
            } catch (_: Throwable) {
                // Diagnostics are best effort; a failed producer must not hide healthy successors.
            }
        }
    }

    private fun nextTelemetrySequence(): Double {
        while (true) {
            val current = telemetryPublishSequence.get()
            val next = if (current >= 0L && current < 9_007_199_254_740_991L) current + 1L else 1L
            if (telemetryPublishSequence.compareAndSet(current, next)) return next.toDouble()
        }
    }
}
