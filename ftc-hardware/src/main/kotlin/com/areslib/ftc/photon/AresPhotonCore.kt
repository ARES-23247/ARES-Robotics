package com.areslib.ftc.photon

import android.content.Context
import com.qualcomm.ftccommon.FtcEventLoop
import com.qualcomm.hardware.lynx.LynxI2cDeviceSynch
import com.qualcomm.hardware.lynx.LynxModule
import com.qualcomm.hardware.lynx.LynxUnsupportedCommandException
import com.qualcomm.hardware.lynx.LynxUsbDevice
import com.qualcomm.hardware.lynx.LynxUsbDeviceDelegate
import com.qualcomm.hardware.lynx.LynxUsbDeviceImpl
import com.qualcomm.hardware.lynx.commands.LynxCommand
import com.qualcomm.hardware.lynx.commands.LynxDatagram
import com.qualcomm.hardware.lynx.commands.LynxMessage
import com.qualcomm.hardware.lynx.commands.LynxRespondable
import com.qualcomm.hardware.lynx.commands.core.LynxSetMotorConstantPowerCommand
import com.qualcomm.hardware.lynx.commands.core.LynxSetServoPulseWidthCommand
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.eventloop.opmode.OpModeManager
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier
import com.qualcomm.robotcore.hardware.HardwareDevice
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple
import com.qualcomm.robotcore.hardware.I2cDeviceSynchDevice
import com.qualcomm.robotcore.hardware.configuration.LynxConstants
import com.qualcomm.robotcore.hardware.usb.RobotUsbDevice
import com.qualcomm.robotcore.util.RobotLog
import com.areslib.telemetry.RobotStatusTracker
import org.firstinspires.ftc.ftccommon.external.OnCreateEventLoop
import org.firstinspires.ftc.robotcore.internal.usb.exception.RobotUsbException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Experimental reflective fast path for FTC Control Hub and Expansion Hub Lynx commands.
 *
 * Replaces SDK [LynxModule] instances during OpMode initialization so selected motor-power
 * ([LynxSetMotorConstantPowerCommand]) and servo-pulse ([LynxSetServoPulseWidthCommand]) commands
 * use a lower-overhead direct USB write. The wrapper retains the SDK transmission lock, unfinished
 * command tracking, and real hub acknowledgements. USB writes remain synchronous and serialized;
 * “parallel” refers only to allowing multiple real acknowledgements to remain pending.
 *
 * ### Performance Acceleration & Memory Rules:
 * - **Explicit opt-in**: interception is selected through [AresFtcRuntimeOptionsProvider] during
 *   pre-init. Generated projects default to [FtcHubCommandTransport.STANDARD_SDK].
 * - **Real acknowledgements**: no synthetic success is injected into the SDK lifecycle.
 * - **Outstanding-command limit**: [ExperimentalParameters.maximumParallelCommands] bounds the
 *   unfinished map best-effort; stalled entries are cleared after the bounded wait.
 * - **Lifecycle Management**: Automatically Hooks into FTC [FtcEventLoop] via `@OnCreateEventLoop` annotations and registers [OpModeManagerNotifier.Notifications].
 *
 * @see LynxModule
 * @see LynxUsbDevice
 * @see AresPhotonLynxModule
 */
object AresPhotonCore : OpModeManagerNotifier.Notifications {

    /** Global flag indicating whether the current OpMode requested Photon interception. */
    val isEnabled = AtomicBoolean(false)

    /** True only after at least one real Lynx module is routed through the direct-write wrapper. */
    val isActive = AtomicBoolean(false)

    private var modules: List<LynxModule> = emptyList()
    private var syncLock: Any? = null

    private val messageSync = Any()

    private var robotUsbDevice: RobotUsbDevice? = null
    private val usbDeviceMap = HashMap<LynxModule, RobotUsbDevice>()
    private val originalModules = HashMap<AresPhotonLynxModule, LynxModule>()
    private var lastUsbDevice: LynxUsbDeviceImpl? = null

    /** Control Hub [LynxModule] reference (if present on embedded serial number). */
    var CONTROL_HUB: LynxModule? = null
    /** Expansion Hub [LynxModule] reference. */
    var EXPANSION_HUB: LynxModule? = null

    /** Toggle controlling whether servo PWM write commands are parallelized alongside motor power commands. */
    var PARALLELIZE_SERVOS = false

    private var opModeManager: OpModeManagerImpl? = null

    /**
     * Configuration parameters controlling single-threaded execution optimizations and maximum parallel command queues.
     */
    class ExperimentalParameters {
        /** Allows similar commands to overlap when `true`; `false` falls back to the SDK for duplicates. */
        val singlethreadedOptimized = AtomicBoolean(true)
        /** Maximum number of parallel outstanding commands permitted in the REV Hub queue before throttling (default 8). */
        val maximumParallelCommands = AtomicInteger(8)

        /**
         * Sets maximum allowable parallel outstanding commands in the REV queue.
         *
         * @param max Target positive integer limit.
         * @return `true` if limit was valid and applied successfully.
         */
        fun setMaximumParallelCommands(max: Int): Boolean {
            if (max <= 0) return false
            maximumParallelCommands.set(max)
            return true
        }
    }


    val experimental = ExperimentalParameters()

    /**
     * Opts into interception without changing REV bulk caching.
     * Must run before the FTC pre-init notification so modules can be replaced safely.
     */
    fun enable() {
        isEnabled.set(true)
        // NOTE: Do NOT change bulkCachingMode here.
        // FtcPerformanceManager.initialize() already sets MANUAL mode and
        // FtcBaseRobot.readSensors() calls clearBulkCaches() each frame.
        // Switching to AUTO here would conflict with that pattern.
    }


    /** Disables interception; wrapped modules delegate new commands to the SDK path. */
    fun disable() {
        isEnabled.set(false)
        isActive.set(false)
        RobotStatusTracker.ftcPhotonActive = false
    }

    @OnCreateEventLoop
    @JvmStatic
    /** Best-effort FTC lifecycle-hook registration. Reflection/API failures are logged or ignored. */
    fun attachEventLoop(@Suppress("UNUSED_PARAMETER") context: Context, eventLoop: FtcEventLoop) {
        try {
            val manager = eventLoop.opModeManager
            val methods = manager.javaClass.methods
            val regMethod = methods.firstOrNull { it.name == "registerListener" && it.parameterCount == 1 }
            regMethod?.invoke(manager, this)
        } catch (t: Throwable) {
            RobotLog.ww("AresPhotonCore", "Could not attach OpModeManagerNotifier: ${t.message}")
        }
        try {
            opModeManager = eventLoop.opModeManager
        } catch (_: Throwable) {}
    }

    @Throws(LynxUnsupportedCommandException::class, InterruptedException::class)
    /**
     * Sends one selected command through the module's mapped USB device.
     * Returns `false` when mapping, queue capacity, or USB transport prevents a safe direct send; the
     * caller then uses the normal SDK path while still holding its transmission lock.
     */
    fun registerSend(command: LynxCommand<*>): Boolean {
        val photonModule = command.module as? AresPhotonLynxModule ?: return false

        synchronized(messageSync) {
            val mappedUsbDevice = usbDeviceMap[photonModule] ?: return false
            val unfinishedCommands = photonModule.getUnfinishedCommandsMap()
            if (unfinishedCommands.size >= experimental.maximumParallelCommands.get()) {
                return false
            }

            if (!experimental.singlethreadedOptimized.get()) {
                for (respondable in unfinishedCommands.values) {
                    if (isSimilar(respondable, command)) {
                        return false
                    }
                }
            }

            val messageNum = photonModule.getNewMessageNumber()
            command.messageNumber = messageNum.toInt()

            try {
                val datagram = LynxDatagram(command)
                command.serialization = datagram

                if (command.isAckable || command.isResponseExpected) {
                    @Suppress("UNCHECKED_CAST")
                    unfinishedCommands[command.messageNumber.toInt()] = command as LynxRespondable<LynxMessage>
                }

                val bytes = datagram.toByteArray()

                if (syncLock != null) {
                    synchronized(syncLock!!) {
                        mappedUsbDevice.write(bytes)
                    }
                } else {
                    mappedUsbDevice.write(bytes)
                }

            } catch (e: InterruptedException) {
                unfinishedCommands.remove(command.messageNumber.toInt())
                Thread.currentThread().interrupt()
                throw e
            } catch (e: LynxUnsupportedCommandException) {
                unfinishedCommands.remove(command.messageNumber.toInt())
                RobotLog.ww("AresPhotonCore", "Direct write unsupported; using SDK path: ${e.message}")
                return false
            } catch (e: RobotUsbException) {
                unfinishedCommands.remove(command.messageNumber.toInt())
                RobotLog.ww("AresPhotonCore", "Direct USB write failed; using SDK path: ${e.message}")
                return false
            } catch (e: Exception) {
                unfinishedCommands.remove(command.messageNumber.toInt())
                RobotLog.ww("AresPhotonCore", "Direct write failed; using SDK path: ${e.message}")
                return false
            }
        }
        return true
    }

    /** Whether [command] is eligible for the experimental direct-write path. */
    fun shouldParallelize(command: LynxCommand<*>): Boolean {
        return command is LynxSetMotorConstantPowerCommand || (PARALLELIZE_SERVOS && command is LynxSetServoPulseWidthCommand)
    }

    private fun isSimilar(respondable1: LynxRespondable<*>, respondable2: LynxRespondable<*>): Boolean {
        return respondable1.destModuleAddress == respondable2.destModuleAddress &&
                respondable1.commandNumber == respondable2.commandNumber
    }

    /** Reserved response-cache hook. The current implementation always returns `null`. */
    fun getCacheResponse(@Suppress("UNUSED_PARAMETER") command: LynxCommand<*>): LynxMessage? {
        return null
    }

    /** Reflectively replaces Lynx modules/device references before an enabled OpMode initializes. */
    override fun onOpModePreInit(opMode: OpMode) {
        val runtimeOptions = resolveAresFtcRuntimeOptions(opMode)
        RobotStatusTracker.ftcHubCommandTransport = runtimeOptions.hubCommandTransport.name
        RobotStatusTracker.ftcLimelightProxyConfigured = runtimeOptions.limelightProxyEnabled
        disable()
        RobotStatusTracker.ftcPhotonActive = false
        if (runtimeOptions.hubCommandTransport == FtcHubCommandTransport.ARES_PHOTON) enable()
        if (!isEnabled.get()) return
        if (opModeManager?.activeOpModeName == OpModeManager.DEFAULT_OP_MODE_NAME) {
            return
        }
        try {

        val map = opMode.hardwareMap

        var replacedPrev = false
        var hasChub = false
        for (module in map.getAll(LynxModule::class.java)) {
            if (module is AresPhotonLynxModule) {
                replacedPrev = true
            }
            if (LynxConstants.isEmbeddedSerialNumber(module.serialNumber)) {
                hasChub = true
            }
        }

        if (replacedPrev) {
            val toRemove = HashMap<String, HardwareDevice>()
            for (module in map.getAll(LynxModule::class.java)) {
                if (module !is AresPhotonLynxModule) {
                    toRemove[map.getNamesOf(module).first()] = module
                }
            }
            for ((s, module) in toRemove) {
                map.remove(s, module)
            }
        } else {
            CONTROL_HUB = null
            EXPANSION_HUB = null
        }

        modules = map.getAll(LynxModule::class.java)
        val moduleNames = ArrayList<String>()
        val replacements = HashMap<LynxModule, AresPhotonLynxModule>()
        for (module in modules) {
            val names = map.getNamesOf(module)
            if (names.isNotEmpty()) {
                moduleNames.add(names.first())
            }
        }

        var usbDevice: LynxUsbDeviceImpl? = null
        for (s in moduleNames) {
            val module = map.get(LynxModule::class.java, s)
            
            val targetModule: AresPhotonLynxModule
            if (module is AresPhotonLynxModule) {
                targetModule = module
            } else {
                try {
                    val lynxUsbDeviceField = AresPhotonReflectionUtils.getField(module.javaClass, "lynxUsbDevice")?.get(module) as? LynxUsbDevice
                    val moduleAddressField = module.moduleAddress
                    val isParentField = module.isParent
                    val isUserModuleField = module.isUserModule

                    targetModule = AresPhotonLynxModule(
                        lynxUsbDeviceField,
                        moduleAddressField,
                        isParentField,
                        isUserModuleField
                    )
                    
                    AresPhotonReflectionUtils.deepCopy(module, targetModule)
                    map.remove(s, module)
                    map.put(s, targetModule)
                    replacements[module] = targetModule
                    originalModules[targetModule] = module
                } catch (e: Exception) {
                    e.printStackTrace()
                    continue
                }
            }

            try {
                if (targetModule.isParent && hasChub && LynxConstants.isEmbeddedSerialNumber(targetModule.serialNumber) && CONTROL_HUB == null) {
                    CONTROL_HUB = targetModule
                } else if (targetModule.isParent) {
                    EXPANSION_HUB = targetModule
                }

                if (targetModule.isParent) {
                    val f1 = AresPhotonReflectionUtils.getField(targetModule.javaClass, "lynxUsbDevice")
                    val tmp = f1?.get(targetModule) as? LynxUsbDevice
                    if (tmp != null) {
                        if (tmp is LynxUsbDeviceDelegate) {
                            val tmp2 = AresPhotonReflectionUtils.getField(LynxUsbDeviceDelegate::class.java, "delegate")
                            tmp2?.isAccessible = true
                            usbDevice = tmp2?.get(tmp) as? LynxUsbDeviceImpl
                        } else {
                            usbDevice = tmp as? LynxUsbDeviceImpl
                        }
                        if (usbDevice != null) {
                            val f2 = AresPhotonReflectionUtils.getField(usbDevice.javaClass.superclass, "robotUsbDevice")
                            f2?.isAccessible = true
                            robotUsbDevice = f2?.get(usbDevice) as? RobotUsbDevice
                            
                            val f3 = AresPhotonReflectionUtils.getField(usbDevice.javaClass, "engageLock")
                            f3?.isAccessible = true
                            syncLock = f3?.get(usbDevice)

                            if (robotUsbDevice != null) {
                                usbDeviceMap[targetModule] = robotUsbDevice!!
                            }
                            lastUsbDevice = usbDevice
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (usbDevice != null) {
            for (m in replacements.keys) {
                usbDevice.removeConfiguredModule(m)
                try {
                    @Suppress("UNCHECKED_CAST")
                    val knownModules = AresPhotonReflectionUtils.getField(usbDevice.javaClass, "knownModules")?.get(usbDevice) as? ConcurrentHashMap<Int, LynxModule>
                    if (knownModules != null) {
                        synchronized(knownModules) {
                            val photonLynxModule = replacements[m]!!
                            knownModules[photonLynxModule.moduleAddress] = photonLynxModule
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }


        for (device in map.getAll(HardwareDevice::class.java)) {
            if (device !is LynxModule) {
                if (device is I2cDeviceSynchDevice<*>) {
                    try {
                        var device2 = AresPhotonReflectionUtils.getField(device.javaClass, "deviceClient")?.get(device) as? I2cDeviceSynchSimple
                        if (device2 != null && device2 !is LynxI2cDeviceSynch) {
                            device2 = AresPhotonReflectionUtils.getField(device2.javaClass, "i2cDeviceSynchSimple")?.get(device2) as? I2cDeviceSynchSimple
                        }
                        if (device2 != null) {
                            setLynxObject(device2, replacements)
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                } else if (device is I2cDeviceSynchSimple) {
                    try {
                        val device2 = AresPhotonReflectionUtils.getField(device.javaClass, "deviceClient")?.get(device) as? I2cDeviceSynchSimple
                        if (device2 != null) {
                            setLynxObject(device2, replacements)
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                } else {
                    setLynxObject(device, replacements)
                }
            }
        }

        isActive.set(replacedPrev || replacements.isNotEmpty())
        RobotStatusTracker.ftcPhotonActive = isActive.get()

        } catch (t: Throwable) {
            disable()
            RobotStatusTracker.ftcPhotonActive = false
            RobotLog.ww("AresPhotonCore", "Photon preInit skipped: ${t.message}")
        }
    }

    private fun setLynxObject(device: Any, replacements: HashMap<LynxModule, AresPhotonLynxModule>) {
        val f = AresPhotonReflectionUtils.getField(device.javaClass, LynxModule::class.java)
        if (f != null) {
            f.isAccessible = true
            try {
                val module = f.get(device) as? LynxModule
                if (module != null && replacements.containsKey(module)) {
                    f.set(device, replacements[module])
                }
            } catch (_: Exception) {}
        }
    }

    /** Lifecycle no-op; all replacement work occurs during pre-init. */
    override fun onOpModePreStart(@Suppress("UNUSED_PARAMETER") opMode: OpMode) {}

    /** Disables interception, restores original modules where possible, and clears retained hardware references. */
    override fun onOpModePostStop(@Suppress("UNUSED_PARAMETER") opMode: OpMode) {
        disable()
        RobotStatusTracker.ftcPhotonActive = false
        synchronized(messageSync) {
            val usbDevice = lastUsbDevice
            if (usbDevice != null) {
                @Suppress("UNCHECKED_CAST")
                val knownModules = AresPhotonReflectionUtils.getField(usbDevice.javaClass, "knownModules")
                    ?.get(usbDevice) as? ConcurrentHashMap<Int, LynxModule>
                if (knownModules != null) {
                    synchronized(knownModules) {
                        for ((photon, original) in originalModules) {
                            usbDevice.removeConfiguredModule(photon)
                            knownModules[original.moduleAddress] = original
                        }
                    }
                }
            }
            originalModules.clear()
            lastUsbDevice = null
            usbDeviceMap.clear()
            robotUsbDevice = null
            syncLock = null
            CONTROL_HUB = null
            EXPANSION_HUB = null
        }
    }
}

/** Pure policy boundary used by pre-init and simulator/unit verification. */
internal fun resolveAresFtcRuntimeOptions(opMode: OpMode): AresFtcRuntimeOptions =
    (opMode as? AresFtcRuntimeOptionsProvider)?.aresFtcRuntimeOptions ?: AresFtcRuntimeOptions()
