package com.areslib.sim.infra

import com.areslib.math.geometry.ChassisSpeeds
import java.awt.event.KeyEvent
import org.lwjgl.glfw.GLFW.*

/**
 * Class implementation for Sim Gamepad Manager.
 *
 * Robotics framework control component.
 */
class SimGamepadManager {
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

    // Web inputs
    @Volatile var webVx = 0.0
    @Volatile var webVy = 0.0
    @Volatile var webOmega = 0.0

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

    fun startPolling(onRepaint: () -> Unit) {
        if (glfwInit()) {
            Thread { pollGamepad(onRepaint) }.apply { isDaemon = true; start() }
        } else {
            println("Failed to initialize GLFW. Gamepad support disabled.")
        }
    }

    private fun pollGamepad(onRepaint: () -> Unit) {
        val gamepadState = org.lwjgl.glfw.GLFWGamepadState.malloc()
        try {
            while (true) {
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
                } catch (e: Exception) {
                    Thread.sleep(1000)
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

        // Web Inputs
        vx += webVx
        vy += webVy
        omega += webOmega

        // Clamp to max speeds
        vx = vx.coerceIn(-MAX_LINEAR_SPEED, MAX_LINEAR_SPEED)
        vy = vy.coerceIn(-MAX_LINEAR_SPEED, MAX_LINEAR_SPEED)
        omega = omega.coerceIn(-MAX_ANGULAR_SPEED, MAX_ANGULAR_SPEED)

        return ChassisSpeeds(vx, vy, omega)
    }
}
