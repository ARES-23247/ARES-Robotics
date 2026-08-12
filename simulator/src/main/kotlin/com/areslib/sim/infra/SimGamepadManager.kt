package com.areslib.sim.infra

import com.areslib.math.geometry.ChassisSpeeds
import java.awt.event.KeyEvent
import org.lwjgl.glfw.GLFW.*

internal interface GlfwLifecycleBackend {
    fun initialize(): Boolean
    fun terminate()
}

internal class GlfwLifecycleCoordinator(private val backend: GlfwLifecycleBackend) {
    internal class Lease internal constructor(internal val generation: Long) {
        internal var released = false
    }

    private var generation = 0L
    private var activeLeases = 0

    @Synchronized
    fun acquire(): Lease? {
        if (activeLeases == 0) {
            if (!backend.initialize()) return null
            generation++
        }
        activeLeases++
        return Lease(generation)
    }

    @Synchronized
    fun release(lease: Lease) {
        if (lease.released) return
        check(lease.generation == generation && activeLeases > 0) {
            "GLFW lease belongs to an inactive runtime generation"
        }
        lease.released = true
        activeLeases--
        if (activeLeases == 0) backend.terminate()
    }
}

internal fun interface SimGamepadPollingLoop {
    fun run(shouldContinue: () -> Boolean, onRepaint: () -> Unit)
}

private val sharedGlfwLifecycle = GlfwLifecycleCoordinator(object : GlfwLifecycleBackend {
    override fun initialize(): Boolean = glfwInit()
    override fun terminate() = glfwTerminate()
})

/**
 * Class implementation for Sim Gamepad Manager.
 *
 * Robotics framework control component.
 */
class SimGamepadManager private constructor(
    private val glfwLifecycle: GlfwLifecycleCoordinator,
    private val injectedPollingLoop: SimGamepadPollingLoop?,
    @Suppress("UNUSED_PARAMETER") injected: Boolean,
) : AutoCloseable {
    constructor() : this(sharedGlfwLifecycle, null, false)

    internal constructor(
        glfwLifecycle: GlfwLifecycleCoordinator,
        pollingLoop: SimGamepadPollingLoop,
    ) : this(glfwLifecycle, pollingLoop, true)

    val pressedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    // Teleop maximum speeds
    private val MAX_LINEAR_SPEED = 4.0 // m/s
    private val MAX_ANGULAR_SPEED = 4.0 // rad/s

    // Mode toggles
    @Volatile var isTeleopMode = true
    @Volatile var isFieldCentric = true
    @Volatile var isRedAlliance = true

    // FSM Toggles
    @Volatile var isIntaking = false
    @Volatile var isFlywheelOn = false
    @Volatile var isTransferring = false
    @Volatile var isPoseReset = false
    @Volatile var isButtonAPressed = false
    @Volatile var isButtonBPressed = false
    @Volatile var isButtonXPressed = false

    @Volatile private var remoteAuthority = false
    @Volatile private var remoteVx = 0.0
    @Volatile private var remoteVy = 0.0
    @Volatile private var remoteOmega = 0.0
    @Volatile private var remoteIsTeleopMode = false
    @Volatile private var remoteIsFieldCentric = false
    @Volatile private var remoteIsRedAlliance = true
    @Volatile private var remoteIsIntaking = false
    @Volatile private var remoteIsFlywheelOn = false
    @Volatile private var remoteIsTransferring = false
    @Volatile private var remoteIsPoseReset = false
    @Volatile private var remoteButtonA = false
    @Volatile private var remoteButtonB = false
    @Volatile private var remoteButtonX = false
    private var mechanismStateObserved = false
    private var intakePulseHigh = false
    private var flywheelPulseHigh = false
    private val effectiveSpeeds = ChassisSpeeds()

    @Volatile
    var appliedIsFieldCentric: Boolean = true
        private set
    @Volatile
    var appliedIsIntaking: Boolean = false
        private set
    @Volatile
    var appliedIsFlywheelOn: Boolean = false
        private set

    val hasRemoteAuthority: Boolean
        get() = remoteAuthority
    val effectiveIsTeleopMode: Boolean
        get() = if (remoteAuthority) remoteIsTeleopMode else isTeleopMode
    val effectiveIsFieldCentric: Boolean
        get() = if (remoteAuthority) remoteIsFieldCentric else isFieldCentric
    val effectiveIsRedAlliance: Boolean
        get() = if (remoteAuthority) remoteIsRedAlliance else isRedAlliance
    val effectiveIsIntaking: Boolean
        get() = if (remoteAuthority) remoteIsIntaking else isIntaking
    val effectiveIsFlywheelOn: Boolean
        get() = if (remoteAuthority) remoteIsFlywheelOn else isFlywheelOn
    val effectiveIsTransferring: Boolean
        get() = if (remoteAuthority) remoteIsTransferring else isTransferring

    // Gamepad axes
    @Volatile var gamepadLx = 0f
    @Volatile var gamepadLy = 0f
    @Volatile var gamepadRx = 0f
    @Volatile var gamepadRy = 0f

    @Volatile var lastGamepadShift = false
    @Volatile var lastGamepadRb = false
    @Volatile var isGamepadConnected = false
    @Volatile var gamepadName = "No Gamepad Detected"

    @Volatile var lastLx = 0f
    @Volatile var lastLy = 0f
    @Volatile var lastRx = 0f
    @Volatile var lastRy = 0f
    @Volatile var lastGamepadEnter = false
    @Volatile var lastRawButtons: ByteArray? = null
    private val pollingLock = Any()
    @Volatile private var polling = false
    @Volatile private var pollingThread: Thread? = null

    fun startPolling(onRepaint: () -> Unit) {
        synchronized(pollingLock) {
            if (pollingThread?.isAlive == true || polling) return
            val lease = glfwLifecycle.acquire()
            if (lease == null) {
                println("Failed to initialize GLFW. Gamepad support disabled.")
                return
            }
            polling = true
            val worker = Thread({
                try {
                    val pollingLoop = injectedPollingLoop
                    if (pollingLoop == null) {
                        pollGamepad(onRepaint)
                    } else {
                        pollingLoop.run({ polling }, onRepaint)
                    }
                } finally {
                    polling = false
                    glfwLifecycle.release(lease)
                    synchronized(pollingLock) {
                        if (pollingThread === Thread.currentThread()) pollingThread = null
                    }
                }
            }, "ARES-SimGamepad-Poller").apply {
                isDaemon = true
            }
            pollingThread = worker
            try {
                worker.start()
            } catch (failure: Throwable) {
                pollingThread = null
                polling = false
                glfwLifecycle.release(lease)
                throw failure
            }
        }
    }

    private fun pollGamepad(onRepaint: () -> Unit) {
        val gamepadState = org.lwjgl.glfw.GLFWGamepadState.malloc()
        try {
            while (polling) {
                try {
                    var activeJoy = -1
                    for (i in GLFW_JOYSTICK_1..GLFW_JOYSTICK_16) {
                        if (glfwJoystickPresent(i)) {
                            activeJoy = i
                            break
                        }
                    }

                    if (activeJoy != -1) {
                        isGamepadConnected = true
                        gamepadName = glfwGetJoystickName(activeJoy) ?: "Unknown Gamepad"
                        
                        var lbPressedThisFrame = false
                        var rbPressedThisFrame = false
                        var rtPressedThisFrame = false

                        val isBluetoothXbox = gamepadName.contains("Bluetooth", ignoreCase = true) || 
                                              gamepadName.contains("LE XINPUT", ignoreCase = true)
                        val isDS4 = gamepadName.contains("Wireless Controller", ignoreCase = true) ||
                                    gamepadName.contains("DualShock", ignoreCase = true) ||
                                    gamepadName.contains("PS4", ignoreCase = true)

                        val axes = glfwGetJoystickAxes(activeJoy)
                        val buttons = glfwGetJoystickButtons(activeJoy)

                        when {
                            isBluetoothXbox && axes != null && axes.capacity() >= 5 -> {
                                gamepadLx = axes[0]
                                gamepadLy = axes[1]
                                gamepadRx = axes[3]
                                gamepadRy = axes[4]
                            }
                            isBluetoothXbox -> {
                                gamepadLx = 0f; gamepadLy = 0f; gamepadRx = 0f; gamepadRy = 0f
                            }
                            isDS4 && axes != null && axes.capacity() >= 6 -> {
                                gamepadLx = axes[0]
                                gamepadLy = axes[1]
                                gamepadRx = axes[2]
                                gamepadRy = axes[5]
                            }
                            isDS4 -> {
                                gamepadLx = 0f; gamepadLy = 0f; gamepadRx = 0f; gamepadRy = 0f
                            }
                            glfwJoystickIsGamepad(activeJoy) && glfwGetGamepadState(activeJoy, gamepadState) -> {
                                gamepadLx = gamepadState.axes(GLFW_GAMEPAD_AXIS_LEFT_X)
                                gamepadLy = gamepadState.axes(GLFW_GAMEPAD_AXIS_LEFT_Y)
                                gamepadRx = gamepadState.axes(GLFW_GAMEPAD_AXIS_RIGHT_X)
                                gamepadRy = gamepadState.axes(GLFW_GAMEPAD_AXIS_RIGHT_Y)
                            }
                            axes != null && axes.capacity() >= 6 -> {
                                gamepadLx = axes[0]
                                gamepadLy = axes[1]
                                gamepadRx = axes[2]
                                gamepadRy = axes[3]
                                if (axes[5] > 0.5f) rtPressedThisFrame = true
                            }
                            axes != null && axes.capacity() >= 4 -> {
                                gamepadLx = axes[0]
                                gamepadLy = axes[1]
                                gamepadRx = axes[2]
                                gamepadRy = axes[3]
                            }
                            else -> {
                                gamepadLx = 0f; gamepadLy = 0f; gamepadRx = 0f; gamepadRy = 0f
                            }
                        }

                        when {
                            (isBluetoothXbox || isDS4) && buttons != null -> {
                                val capacity = buttons.capacity()
                                lbPressedThisFrame = (capacity > 6 && buttons[6] == GLFW_PRESS.toByte()) || 
                                                     (capacity > 4 && buttons[4] == GLFW_PRESS.toByte())
                                                     
                                rbPressedThisFrame = (capacity > 7 && buttons[7] == GLFW_PRESS.toByte()) || 
                                                     (capacity > 5 && buttons[5] == GLFW_PRESS.toByte())
                                
                                rtPressedThisFrame = rtPressedThisFrame || (capacity > 9 && buttons[9] == GLFW_PRESS.toByte()) ||
                                                     (capacity > 16 && buttons[16] == GLFW_PRESS.toByte()) ||
                                                     (capacity > 12 && buttons[12] == GLFW_PRESS.toByte()) ||
                                                     (capacity > 11 && buttons[11] == GLFW_PRESS.toByte())
                                
                                if (capacity > 0) isButtonAPressed = buttons[0] == GLFW_PRESS.toByte()
                                if (capacity > 1) isButtonBPressed = buttons[1] == GLFW_PRESS.toByte()
                                if (capacity > 2) isButtonXPressed = buttons[2] == GLFW_PRESS.toByte()
                                if (capacity > 3) isPoseReset = buttons[3] == GLFW_PRESS.toByte()
                            }
                            glfwJoystickIsGamepad(activeJoy) && glfwGetGamepadState(activeJoy, gamepadState) -> {
                                val rtValue = gamepadState.axes(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER)
                                if (rtValue > 0.0f) rtPressedThisFrame = true
                                
                                lbPressedThisFrame = gamepadState.buttons(GLFW_GAMEPAD_BUTTON_LEFT_BUMPER) == GLFW_PRESS.toByte()
                                rbPressedThisFrame = gamepadState.buttons(GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER) == GLFW_PRESS.toByte()
                                
                                isButtonAPressed = gamepadState.buttons(GLFW_GAMEPAD_BUTTON_A) == GLFW_PRESS.toByte()
                                isButtonBPressed = gamepadState.buttons(GLFW_GAMEPAD_BUTTON_B) == GLFW_PRESS.toByte()
                                isButtonXPressed = gamepadState.buttons(GLFW_GAMEPAD_BUTTON_X) == GLFW_PRESS.toByte()
                                isPoseReset = gamepadState.buttons(GLFW_GAMEPAD_BUTTON_Y) == GLFW_PRESS.toByte()
                            }
                            buttons != null -> {
                                val capacity = buttons.capacity()
                                if (capacity >= 6) {
                                    lbPressedThisFrame = buttons[4] == GLFW_PRESS.toByte()
                                    rbPressedThisFrame = buttons[5] == GLFW_PRESS.toByte()
                                }
                                rtPressedThisFrame = rtPressedThisFrame || (capacity > 9 && buttons[9] == GLFW_PRESS.toByte()) ||
                                                     (capacity > 16 && buttons[16] == GLFW_PRESS.toByte()) ||
                                                     (capacity > 12 && buttons[12] == GLFW_PRESS.toByte())
                                                     
                                if (capacity > 0) isButtonAPressed = buttons[0] == GLFW_PRESS.toByte()
                                if (capacity > 1) isButtonBPressed = buttons[1] == GLFW_PRESS.toByte()
                                if (capacity > 2) isButtonXPressed = buttons[2] == GLFW_PRESS.toByte()
                                if (capacity > 3) isPoseReset = buttons[3] == GLFW_PRESS.toByte()
                            }
                        }

                        // Edge-detect LB/RB for toggle behavior
                        if (lbPressedThisFrame && !lastGamepadShift) {
                            isIntaking = !isIntaking
                        }
                        if (rbPressedThisFrame && !lastGamepadRb) {
                            isFlywheelOn = !isFlywheelOn
                        }
                        
                        lastGamepadShift = lbPressedThisFrame
                        lastGamepadRb = rbPressedThisFrame

                        // Transfer/Shoot momentary
                        val isKeyboardTransferring = pressedKeys.contains(KeyEvent.VK_ENTER)
                        val newIsTransferring = rtPressedThisFrame || isKeyboardTransferring
                        if (isTransferring != newIsTransferring) {
                            isTransferring = newIsTransferring
                        }

                        // Any raw button state changes
                        var anyButtonChanged = false
                        val rawButtons = glfwGetJoystickButtons(activeJoy)
                        if (rawButtons != null) {
                            val capacity = rawButtons.capacity()
                            val localLast = lastRawButtons
                            if (localLast == null || localLast.size != capacity) {
                                val newArr = ByteArray(capacity) { rawButtons[it] }
                                lastRawButtons = newArr
                                anyButtonChanged = true
                            } else {
                                for (idx in 0 until capacity) {
                                    if (rawButtons[idx] != localLast[idx]) {
                                        anyButtonChanged = true
                                        localLast[idx] = rawButtons[idx]
                                    }
                                }
                            }
                        }

                        // Repaint conditional check
                        val changed = kotlin.math.abs(gamepadLx - lastLx) > 0.05f ||
                                      kotlin.math.abs(gamepadLy - lastLy) > 0.05f ||
                                      kotlin.math.abs(gamepadRx - lastRx) > 0.05f ||
                                      kotlin.math.abs(gamepadRy - lastRy) > 0.05f ||
                                      lbPressedThisFrame != lastGamepadShift ||
                                      rbPressedThisFrame != lastGamepadRb ||
                                      rtPressedThisFrame != lastGamepadEnter ||
                                      anyButtonChanged

                        if (changed || isKeyboardTransferring) {
                            lastLx = gamepadLx; lastLy = gamepadLy; lastRx = gamepadRx; lastRy = gamepadRy
                            lastGamepadEnter = rtPressedThisFrame
                            onRepaint()
                        }
                    } else {
                        if (isGamepadConnected) {
                            isGamepadConnected = false
                            gamepadName = "No Gamepad Detected"
                            gamepadLx = 0f; gamepadLy = 0f; gamepadRx = 0f; gamepadRy = 0f
                            onRepaint()
                        }
                    }
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (_: Exception) {
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        } finally {
            gamepadState.free()
        }
    }

    fun handleKeyPressed(keyCode: Int, onRepaint: () -> Unit) {
        pressedKeys.add(keyCode)
        when (keyCode) {
            KeyEvent.VK_SPACE -> isTeleopMode = !isTeleopMode
            KeyEvent.VK_C -> isFieldCentric = !isFieldCentric
            KeyEvent.VK_R -> isRedAlliance = !isRedAlliance
            KeyEvent.VK_SHIFT -> isIntaking = !isIntaking
            KeyEvent.VK_F -> isFlywheelOn = !isFlywheelOn
            KeyEvent.VK_ENTER -> isTransferring = true
            KeyEvent.VK_Y -> isPoseReset = true
            KeyEvent.VK_1 -> isButtonAPressed = true
            KeyEvent.VK_2 -> isButtonBPressed = true
            KeyEvent.VK_3 -> isButtonXPressed = true
        }
        onRepaint()
    }

    fun handleKeyReleased(keyCode: Int, onRepaint: () -> Unit) {
        pressedKeys.remove(keyCode)
        when (keyCode) {
            KeyEvent.VK_ENTER -> isTransferring = false
            KeyEvent.VK_Y -> isPoseReset = false
            KeyEvent.VK_1 -> isButtonAPressed = false
            KeyEvent.VK_2 -> isButtonBPressed = false
            KeyEvent.VK_3 -> isButtonXPressed = false
        }
        onRepaint()
    }

    fun getChassisSpeeds(): ChassisSpeeds {
        if (remoteAuthority) {
            effectiveSpeeds.vxMetersPerSecond = remoteVx.coerceIn(-MAX_LINEAR_SPEED, MAX_LINEAR_SPEED)
            effectiveSpeeds.vyMetersPerSecond = remoteVy.coerceIn(-MAX_LINEAR_SPEED, MAX_LINEAR_SPEED)
            effectiveSpeeds.omegaRadiansPerSecond = remoteOmega.coerceIn(-MAX_ANGULAR_SPEED, MAX_ANGULAR_SPEED)
            return effectiveSpeeds
        }
        var vx = 0.0
        var vy = 0.0
        var omega = 0.0

        // Keyboard
        if (pressedKeys.contains(KeyEvent.VK_W)) vx += MAX_LINEAR_SPEED
        if (pressedKeys.contains(KeyEvent.VK_S)) vx -= MAX_LINEAR_SPEED
        if (pressedKeys.contains(KeyEvent.VK_A)) vy += MAX_LINEAR_SPEED
        if (pressedKeys.contains(KeyEvent.VK_D)) vy -= MAX_LINEAR_SPEED
        if (pressedKeys.contains(KeyEvent.VK_Q)) omega += MAX_ANGULAR_SPEED
        if (pressedKeys.contains(KeyEvent.VK_E)) omega -= MAX_ANGULAR_SPEED

        // Gamepad (Deadzone applied)
        if (kotlin.math.abs(gamepadLy) > 0.1) vx += -gamepadLy * MAX_LINEAR_SPEED
        if (kotlin.math.abs(gamepadLx) > 0.1) vy += -gamepadLx * MAX_LINEAR_SPEED
        if (kotlin.math.abs(gamepadRx) > 0.1) omega += -gamepadRx * MAX_ANGULAR_SPEED

        // Clamp to max speeds
        vx = vx.coerceIn(-MAX_LINEAR_SPEED, MAX_LINEAR_SPEED)
        vy = vy.coerceIn(-MAX_LINEAR_SPEED, MAX_LINEAR_SPEED)
        omega = omega.coerceIn(-MAX_ANGULAR_SPEED, MAX_ANGULAR_SPEED)

        effectiveSpeeds.vxMetersPerSecond = vx
        effectiveSpeeds.vyMetersPerSecond = vy
        effectiveSpeeds.omegaRadiansPerSecond = omega
        return effectiveSpeeds
    }

    /** Installs one fresh atomic dashboard command as the exclusive effective input authority. */
    fun applyRemoteCommand(command: com.areslib.telemetry.SimInputBridge.CommandFrame) {
        require(command.sessionNonce > 0L && command.receivedAtMs >= 0L) { "Remote command must have a live lease" }
        remoteVx = command.vx
        remoteVy = command.vy
        remoteOmega = command.omega
        remoteIsIntaking = command.isIntaking
        remoteIsFlywheelOn = command.isFlywheelOn
        remoteIsTransferring = command.isTransferring
        remoteIsTeleopMode = command.isTeleopMode
        remoteIsFieldCentric = command.isFieldCentric
        remoteIsRedAlliance = command.isRedAlliance
        remoteButtonA = command.isButtonAPressed
        remoteButtonB = command.isButtonBPressed
        remoteButtonX = command.isButtonXPressed
        remoteIsPoseReset = command.isPoseReset
        // Publish the complete snapshot before making it observable.
        remoteAuthority = true
    }

    /** Restores untouched keyboard/gamepad state after an invalid or expired remote lease. */
    fun clearRemoteCommand() {
        remoteAuthority = false
        remoteVx = 0.0
        remoteVy = 0.0
        remoteOmega = 0.0
    }

    /** Records the frame that the active FTC drivetrain will consume this simulator tick. */
    fun recordAppliedDriveFrame(fieldCentric: Boolean) {
        appliedIsFieldCentric = fieldCentric
    }

    /**
     * Reconciles toggle injection and UI state with Redux intent accepted by the season robot.
     *
     * A rejected interlocked edge leaves desired and accepted state different. The pulse generator
     * then emits alternating press/release frames until the interlock clears and Redux converges.
     */
    fun observeAcceptedMechanismState(intakeAccepted: Boolean, flywheelAccepted: Boolean) {
        appliedIsIntaking = intakeAccepted
        appliedIsFlywheelOn = flywheelAccepted
        mechanismStateObserved = true
    }

    /** Makes desired toggle state re-synchronize with a newly initialized OpMode. */
    fun resetInjectionState() {
        mechanismStateObserved = false
        intakePulseHigh = false
        flywheelPulseHigh = false
        appliedIsIntaking = false
        appliedIsFlywheelOn = false
    }

    /** Writes the effective local-or-remote command into the active FTC SDK gamepad mocks. */
    fun writeEffectiveGamepads(
        driver: com.qualcomm.robotcore.hardware.Gamepad,
        operator: com.qualcomm.robotcore.hardware.Gamepad,
    ) {
        val speeds = getChassisSpeeds()
        driver.left_stick_y = (-speeds.vxMetersPerSecond / MAX_LINEAR_SPEED).coerceIn(-1.0, 1.0).toFloat()
        driver.left_stick_x = (-speeds.vyMetersPerSecond / MAX_LINEAR_SPEED).coerceIn(-1.0, 1.0).toFloat()
        driver.right_stick_x = (-speeds.omegaRadiansPerSecond / MAX_ANGULAR_SPEED).coerceIn(-1.0, 1.0).toFloat()
        driver.left_stick_button = false

        val intakeDesired = effectiveIsIntaking
        val flywheelDesired = effectiveIsFlywheelOn
        // Season TeleOps expose toggle buttons, so a mismatch is retried as press/release pulses
        // until the accepted Redux state confirms convergence. Before the first observation a new
        // OpMode is safely assumed neutral.
        val acceptedIntake = if (mechanismStateObserved) appliedIsIntaking else false
        val acceptedFlywheel = if (mechanismStateObserved) appliedIsFlywheelOn else false
        val intakePress = intakeDesired != acceptedIntake && !intakePulseHigh
        val flywheelPress = flywheelDesired != acceptedFlywheel && !flywheelPulseHigh
        driver.left_bumper = intakePress
        driver.right_bumper = flywheelPress
        intakePulseHigh = intakePress
        flywheelPulseHigh = flywheelPress
        driver.right_trigger = if (effectiveIsTransferring) 1.0f else 0.0f
        driver.a = if (remoteAuthority) remoteButtonA else isButtonAPressed
        driver.b = if (remoteAuthority) remoteButtonB else isButtonBPressed
        driver.x = if (remoteAuthority) remoteButtonX else isButtonXPressed
        driver.y = if (remoteAuthority) remoteIsPoseReset else isPoseReset

        // No season controls currently bind operator input, but clear it so retained values from a
        // prior simulated OpMode cannot leak into a future one.
        operator.left_stick_x = 0.0f
        operator.left_stick_y = 0.0f
        operator.right_stick_x = 0.0f
        operator.right_stick_y = 0.0f
        operator.left_bumper = false
        operator.right_bumper = false
        operator.left_trigger = 0.0f
        operator.right_trigger = 0.0f
    }

    /** Stops optional native gamepad polling; headless instances have no thread to stop. */
    override fun close() {
        val worker = synchronized(pollingLock) {
            polling = false
            pollingThread
        }
        worker?.interrupt()
        var stopFailure: Throwable? = null
        if (worker != null && worker !== Thread.currentThread()) {
            try {
                worker.join(POLLING_STOP_JOIN_MS)
                if (worker.isAlive) {
                    stopFailure = IllegalStateException("Simulator gamepad polling thread did not terminate")
                }
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                stopFailure = failure
            }
        }
        clearRemoteCommand()
        resetInjectionState()
        isIntaking = false
        isFlywheelOn = false
        isTransferring = false
        stopFailure?.let { throw it }
    }

    private companion object {
        const val POLLING_STOP_JOIN_MS = 2_000L
    }
}
