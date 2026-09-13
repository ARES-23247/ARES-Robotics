package com.areslib.telemetry

/** One complete gamepad wire mapping shared by state frames and the public helper. */
internal object GamepadTelemetry {
    private val suffixes = arrayOf(
        "LeftStick_X", "LeftStick_Y", "RightStick_X", "RightStick_Y", "LeftTrigger", "RightTrigger",
        "A", "B", "X", "Y", "DpadUp", "DpadDown", "DpadLeft", "DpadRight", "LeftBumper", "RightBumper",
        "C", "Z", "M1", "M2", "M3", "M4", "Touchpad", "Share", "Options",
        "LeftStickButton", "RightStickButton", "Start", "Back",
        "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"
    )

    fun topics(prefix: String): Array<String> = Array(suffixes.size) { "$prefix/${suffixes[it]}" }

    // Public helpers may alternate controllers or use custom prefixes. Bound retained keys per
    // publishing thread while avoiding repeated string construction for the usual stable prefixes.
    private val helperTopics = object : ThreadLocal<LinkedHashMap<String, Array<String>>>() {
        override fun initialValue() = object : LinkedHashMap<String, Array<String>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Array<String>>?): Boolean = size > 16
        }
    }

    fun cachedTopics(prefix: String): Array<String> {
        val cache = helperTopics.get()!!
        return cache[prefix] ?: topics(prefix).also { cache[prefix] = it }
    }

    fun publish(telemetry: ITelemetry, topics: Array<String>, gamepad: GamepadState) {
        telemetry.putNumber(topics[0], gamepad.leftStickX.toDouble())
        telemetry.putNumber(topics[1], gamepad.leftStickY.toDouble())
        telemetry.putNumber(topics[2], gamepad.rightStickX.toDouble())
        telemetry.putNumber(topics[3], gamepad.rightStickY.toDouble())
        telemetry.putNumber(topics[4], gamepad.leftTrigger.toDouble())
        telemetry.putNumber(topics[5], gamepad.rightTrigger.toDouble())
        telemetry.putBoolean(topics[6], gamepad.a)
        telemetry.putBoolean(topics[7], gamepad.b)
        telemetry.putBoolean(topics[8], gamepad.x)
        telemetry.putBoolean(topics[9], gamepad.y)
        telemetry.putBoolean(topics[10], gamepad.dpadUp)
        telemetry.putBoolean(topics[11], gamepad.dpadDown)
        telemetry.putBoolean(topics[12], gamepad.dpadLeft)
        telemetry.putBoolean(topics[13], gamepad.dpadRight)
        telemetry.putBoolean(topics[14], gamepad.leftBumper)
        telemetry.putBoolean(topics[15], gamepad.rightBumper)
        telemetry.putBoolean(topics[16], gamepad.c)
        telemetry.putBoolean(topics[17], gamepad.z)
        telemetry.putBoolean(topics[18], gamepad.m1)
        telemetry.putBoolean(topics[19], gamepad.m2)
        telemetry.putBoolean(topics[20], gamepad.m3)
        telemetry.putBoolean(topics[21], gamepad.m4)
        telemetry.putBoolean(topics[22], gamepad.touchpad)
        telemetry.putBoolean(topics[23], gamepad.share)
        telemetry.putBoolean(topics[24], gamepad.options)
        telemetry.putBoolean(topics[25], gamepad.leftStickButton)
        telemetry.putBoolean(topics[26], gamepad.rightStickButton)
        telemetry.putBoolean(topics[27], gamepad.start)
        telemetry.putBoolean(topics[28], gamepad.back)
        telemetry.putBoolean(topics[29], gamepad.f1)
        telemetry.putBoolean(topics[30], gamepad.f2)
        telemetry.putBoolean(topics[31], gamepad.f3)
        telemetry.putBoolean(topics[32], gamepad.f4)
        telemetry.putBoolean(topics[33], gamepad.f5)
        telemetry.putBoolean(topics[34], gamepad.f6)
        telemetry.putBoolean(topics[35], gamepad.f7)
        telemetry.putBoolean(topics[36], gamepad.f8)
        telemetry.putBoolean(topics[37], gamepad.f9)
        telemetry.putBoolean(topics[38], gamepad.f10)
        telemetry.putBoolean(topics[39], gamepad.f11)
        telemetry.putBoolean(topics[40], gamepad.f12)
    }
}
