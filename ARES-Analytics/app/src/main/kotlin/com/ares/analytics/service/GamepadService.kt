package com.ares.analytics.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.lwjgl.glfw.GLFW.*

/**
 * Immutable data snapshot representing active USB gamepad joystick axes and digital button states.
 */
data class GamepadState(
    val connected: Boolean = false,
    val joystickId: Int = -1,
    val name: String = "",
    val guid: String = "",
    val usesStandardMapping: Boolean = false,
    /** Unmodified GLFW axes. These remain available even when a standard mapping is active. */
    val rawAxes: List<Float> = emptyList(),
    /** Unmodified GLFW buttons. Extra controller inputs are discovered from this list. */
    val rawButtons: List<Boolean> = emptyList(),
    /** GLFW hat bitmasks, retained for controller-profile learning and diagnostics. */
    val rawHats: List<Int> = emptyList(),
    val leftStickX: Float = 0f,
    val leftStickY: Float = 0f,
    val rightStickX: Float = 0f,
    val rightStickY: Float = 0f,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
    val a: Boolean = false,
    val b: Boolean = false,
    val x: Boolean = false,
    val y: Boolean = false,
    val leftBumper: Boolean = false,
    val rightBumper: Boolean = false,
    val dpadUp: Boolean = false,
    val dpadDown: Boolean = false,
    val dpadLeft: Boolean = false,
    val dpadRight: Boolean = false
) {
    val axisCount: Int get() = rawAxes.size
    val buttonCount: Int get() = rawButtons.size
    val hatCount: Int get() = rawHats.size
}

/** Raw device data captured independently of GLFW's optional standardized mapping. */
internal data class RawJoystickSnapshot(
    val joystickId: Int,
    val name: String,
    val guid: String,
    val axes: List<Float>,
    val buttons: List<Boolean>,
    val hats: List<Int>
)

/** Standard Xbox-style view returned by GLFW when a mapping is available. */
internal data class StandardGamepadSnapshot(
    val axes: List<Float>,
    val buttons: List<Boolean>
)

/**
 * Gamepad input service using LWJGL/GLFW instead of Jamepad/SDL.
 * GLFW bundles platform natives cleanly via Maven classifier JARs,
 * eliminating the org/libsdl/SDL ClassNotFoundError.
 *
 * Joystick polling in GLFW does NOT require a window context.
 */
class GamepadService {
    private var isInitialized = false

    private val _gamepad1State = MutableStateFlow(GamepadState())
    val gamepad1State: StateFlow<GamepadState> = _gamepad1State.asStateFlow()

    private val _gamepad2State = MutableStateFlow(GamepadState())
    val gamepad2State: StateFlow<GamepadState> = _gamepad2State.asStateFlow()

    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (pollingJob?.isActive == true) return

        pollingJob = scope.launch {
            if (!isInitialized) {
                try {
                    if (!glfwInit()) {
                        println("[GamepadService] Failed to initialize GLFW. Gamepad support disabled.")
                        return@launch
                    }
                    isInitialized = true
                    println("[GamepadService] GLFW initialized successfully.")
                } catch (e: Throwable) {
                    println("[GamepadService] GLFW init failed: ${e.message}")
                    return@launch
                }
            }

            val gamepadState = org.lwjgl.glfw.GLFWGamepadState.malloc()
            try {
                while (isActive) {
                    try {
                        pollJoystick(GLFW_JOYSTICK_1, gamepadState, _gamepad1State)
                        pollJoystick(GLFW_JOYSTICK_2, gamepadState, _gamepad2State)
                    } catch (e: Exception) {
                        // Swallow transient GLFW errors
                    }
                    delay(20) // 50 Hz polling rate
                }
            } finally {
                gamepadState.free()
            }
        }
    }

    private fun pollJoystick(
        joystickId: Int,
        gamepadState: org.lwjgl.glfw.GLFWGamepadState,
        stateFlow: MutableStateFlow<GamepadState>
    ) {
        if (!glfwJoystickPresent(joystickId)) {
            stateFlow.update { current ->
                if (current.connected) GamepadState(connected = false) else current
            }
            return
        }
        val raw = RawJoystickSnapshot(
            joystickId = joystickId,
            name = glfwGetJoystickName(joystickId) ?: "Unknown Gamepad",
            guid = glfwGetJoystickGUID(joystickId).orEmpty(),
            axes = glfwGetJoystickAxes(joystickId)?.let { axes ->
                List(axes.capacity()) { index -> axes[index] }
            }.orEmpty(),
            buttons = glfwGetJoystickButtons(joystickId)?.let { buttons ->
                List(buttons.capacity()) { index -> buttons[index] == GLFW_PRESS.toByte() }
            }.orEmpty(),
            hats = glfwGetJoystickHats(joystickId)?.let { hats ->
                List(hats.capacity()) { index -> hats[index].toInt() and 0xff }
            }.orEmpty()
        )

        if (glfwJoystickIsGamepad(joystickId) && glfwGetGamepadState(joystickId, gamepadState)) {
            val standard = StandardGamepadSnapshot(
                axes = List(GLFW_GAMEPAD_AXIS_LAST + 1) { index -> gamepadState.axes(index) },
                buttons = List(GLFW_GAMEPAD_BUTTON_LAST + 1) { index ->
                    gamepadState.buttons(index) == GLFW_PRESS.toByte()
                }
            )
            stateFlow.value = mapGamepadState(raw, standard)
        } else {
            stateFlow.value = mapGamepadState(raw, standard = null)
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        _gamepad1State.value = GamepadState()
        _gamepad2State.value = GamepadState()
    }

    fun dispose() {
        stop()
        if (isInitialized) {
            // Don't call glfwTerminate() — other parts of the app may use GLFW
            isInitialized = false
        }
    }

    companion object {
        private const val DEADZONE = 0.08f

        /** Apply a per-axis deadzone to stick axes. */
        internal fun applyDeadzone(value: Float): Float {
            return if (kotlin.math.abs(value) < DEADZONE) 0f else value
        }

        /** GLFW triggers range from -1.0 (released) to 1.0 (pressed). Normalize to 0.0..1.0 */
        internal fun normalizeTrigger(raw: Float): Float {
            return ((raw + 1f) / 2f).coerceIn(0f, 1f)
        }
    }
}

/**
 * Combines raw and standardized views without losing vendor-specific buttons.
 * Kept outside the GLFW polling loop so profile discovery can be covered by unit tests.
 */
internal fun mapGamepadState(
    raw: RawJoystickSnapshot,
    standard: StandardGamepadSnapshot?
): GamepadState {
    val axes = standard?.axes ?: raw.axes
    val buttons = standard?.buttons ?: raw.buttons
    fun axis(index: Int, fallback: Float = 0f): Float = axes.getOrElse(index) { fallback }
    fun button(index: Int): Boolean = buttons.getOrElse(index) { false }

    return GamepadState(
        connected = true,
        joystickId = raw.joystickId,
        name = raw.name,
        guid = raw.guid,
        usesStandardMapping = standard != null,
        rawAxes = raw.axes,
        rawButtons = raw.buttons,
        rawHats = raw.hats,
        leftStickX = GamepadService.applyDeadzone(axis(GLFW_GAMEPAD_AXIS_LEFT_X)),
        leftStickY = GamepadService.applyDeadzone(-axis(GLFW_GAMEPAD_AXIS_LEFT_Y)),
        rightStickX = GamepadService.applyDeadzone(axis(GLFW_GAMEPAD_AXIS_RIGHT_X)),
        rightStickY = GamepadService.applyDeadzone(-axis(GLFW_GAMEPAD_AXIS_RIGHT_Y)),
        leftTrigger = GamepadService.normalizeTrigger(axis(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER, -1f)),
        rightTrigger = GamepadService.normalizeTrigger(axis(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER, -1f)),
        a = button(GLFW_GAMEPAD_BUTTON_A),
        b = button(GLFW_GAMEPAD_BUTTON_B),
        x = button(GLFW_GAMEPAD_BUTTON_X),
        y = button(GLFW_GAMEPAD_BUTTON_Y),
        leftBumper = button(GLFW_GAMEPAD_BUTTON_LEFT_BUMPER),
        rightBumper = button(GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER),
        dpadUp = button(GLFW_GAMEPAD_BUTTON_DPAD_UP),
        dpadDown = button(GLFW_GAMEPAD_BUTTON_DPAD_DOWN),
        dpadLeft = button(GLFW_GAMEPAD_BUTTON_DPAD_LEFT),
        dpadRight = button(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT)
    )
}
