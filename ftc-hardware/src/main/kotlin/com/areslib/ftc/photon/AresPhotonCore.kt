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
import com.qualcomm.hardware.lynx.commands.standard.LynxAck
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
import org.firstinspires.ftc.ftccommon.external.OnCreateEventLoop
import org.firstinspires.ftc.robotcore.internal.usb.exception.RobotUsbException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Object implementation for Ares Photon Core.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
object AresPhotonCore : Runnable, OpModeManagerNotifier.Notifications {

    val isEnabled = AtomicBoolean(true)
    private val threadEnabled = AtomicBoolean(false)

    private var modules: List<LynxModule> = emptyList()
    private var thisThread: Thread? = null
    private var syncLock: Any? = null

    private val messageSync = Any()

    private var robotUsbDevice: RobotUsbDevice? = null
    private val usbDeviceMap = HashMap<LynxModule, RobotUsbDevice>()
    private val originalModules = HashMap<AresPhotonLynxModule, LynxModule>()
    private var lastUsbDevice: LynxUsbDeviceImpl? = null

    var CONTROL_HUB: LynxModule? = null
    var EXPANSION_HUB: LynxModule? = null

    var PARALLELIZE_SERVOS = true

    private var opModeManager: OpModeManagerImpl? = null

    /**
     * Class implementation for Experimental Parameters.
     *
     * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
     */
    class ExperimentalParameters {
        val singlethreadedOptimized = AtomicBoolean(true)
        val maximumParallelCommands = AtomicInteger(8)

        fun setMaximumParallelCommands(max: Int): Boolean {
            if (max <= 0) return false
            maximumParallelCommands.set(max)
            return true
        }
    }

    val experimental = ExperimentalParameters()

    /**
     * enable declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun enable() {
        isEnabled.set(true)
        // NOTE: Do NOT change bulkCachingMode here.
        // FtcPerformanceManager.initialize() already sets MANUAL mode and
        // FtcBaseRobot.readSensors() calls clearBulkCaches() each frame.
        // Switching to AUTO here would conflict with that pattern.
    }


    /**
     * disable declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun disable() {
        isEnabled.set(false)
    }

    @OnCreateEventLoop
    @JvmStatic
    /**
     * attachEventLoop declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun attachEventLoop(@Suppress("UNUSED_PARAMETER") context: Context, eventLoop: FtcEventLoop) {
        try {
            val manager = eventLoop.opModeManager
            if (manager is OpModeManagerNotifier) {
                val methods = manager.javaClass.methods
                val regMethod = methods.firstOrNull { it.name == "registerListener" && it.parameterCount == 1 }
                regMethod?.invoke(manager, this)
            }
        } catch (t: Throwable) {
            RobotLog.ww("AresPhotonCore", "Could not attach OpModeManagerNotifier: ${t.message}")
        }
        try {
            opModeManager = eventLoop.opModeManager
        } catch (_: Throwable) {}
    }

    @Throws(LynxUnsupportedCommandException::class, InterruptedException::class)
    /**
     * registerSend declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun registerSend(command: LynxCommand<*>): Boolean {
        val photonModule = command.module as AresPhotonLynxModule

        if (!usbDeviceMap.containsKey(photonModule)) {
            return false
        }

        synchronized(messageSync) {
            var spinCount = 0
            while (photonModule.getUnfinishedCommandsMap().size > experimental.maximumParallelCommands.get()) {
                spinCount++
                if (spinCount > 20) {
                    photonModule.getUnfinishedCommandsMap().clear()
                    break
                }
                Thread.sleep(1)
            }

            if (!experimental.singlethreadedOptimized.get()) {
                var noSimilar = false
                while (!noSimilar) {
                    noSimilar = true
                    for (respondable in photonModule.getUnfinishedCommandsMap().values) {
                        if (isSimilar(respondable, command)) {
                            noSimilar = false
                        }
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
                    photonModule.getUnfinishedCommandsMap()[command.messageNumber.toInt()] = command as LynxRespondable<LynxMessage>
                }

                val bytes = datagram.toByteArray()

                if (syncLock != null) {
                    synchronized(syncLock!!) {
                        usbDeviceMap[photonModule]!!.write(bytes)
                    }
                } else {
                    usbDeviceMap[photonModule]!!.write(bytes)
                }

                if (shouldAckImmediately(command)) {
                    @Suppress("UNCHECKED_CAST")
                    (command as LynxCommand<LynxMessage>).onAckReceived(LynxAck(photonModule, false))
                }
            } catch (e: LynxUnsupportedCommandException) {
                e.printStackTrace()
            } catch (e: RobotUsbException) {
                e.printStackTrace()
            }
        }
        return true
    }

    /**
     * shouldParallelize declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun shouldParallelize(command: LynxCommand<*>): Boolean {
        return command is LynxSetMotorConstantPowerCommand || (PARALLELIZE_SERVOS && command is LynxSetServoPulseWidthCommand)
    }

    /**
     * shouldAckImmediately declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun shouldAckImmediately(command: LynxCommand<*>): Boolean {
        return command is LynxSetMotorConstantPowerCommand || command is LynxSetServoPulseWidthCommand
    }

    private fun isSimilar(respondable1: LynxRespondable<*>, respondable2: LynxRespondable<*>): Boolean {
        return respondable1.destModuleAddress == respondable2.destModuleAddress &&
                respondable1.commandNumber == respondable2.commandNumber
    }

    /**
     * getCacheResponse declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun getCacheResponse(@Suppress("UNUSED_PARAMETER") command: LynxCommand<*>): LynxMessage? {
        return null
    }

    /**
     * run declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun run() {
        while (threadEnabled.get()) {
            try {
                Thread.sleep(5)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * onOpModePreInit declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun onOpModePreInit(opMode: OpMode) {
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

        if (thisThread == null || !thisThread!!.isAlive) {
            thisThread = Thread(this)
            threadEnabled.set(true)
            thisThread!!.start()
        }
        } catch (t: Throwable) {
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

    /**
     * onOpModePreStart declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun onOpModePreStart(@Suppress("UNUSED_PARAMETER") opMode: OpMode) {}

    /**
     * onOpModePostStop declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun onOpModePostStop(opMode: OpMode) {
        isEnabled.set(false)
        threadEnabled.set(false)
        if (lastUsbDevice != null) {
            @Suppress("UNCHECKED_CAST")
            val knownModules = AresPhotonReflectionUtils.getField(lastUsbDevice!!.javaClass, "knownModules")?.get(lastUsbDevice!!) as? ConcurrentHashMap<Int, LynxModule>
            if (knownModules != null) {
                synchronized(knownModules) {
                    for ((photon, original) in originalModules) {
                        lastUsbDevice!!.removeConfiguredModule(photon)
                        knownModules[original.moduleAddress] = original
                    }
                }
            }
        }
        originalModules.clear()
        lastUsbDevice = null
        usbDeviceMap.clear()
        CONTROL_HUB = null
        EXPANSION_HUB = null
    }
}
