package com.ares.analytics.ui.components.dashboard

/** Combines the physical menu-button spellings used by Xbox and PlayStation telemetry. */
internal fun gamepadMenuPressed(primary: Double?, alternate: Double?): Boolean =
    (primary != null && primary.isFinite() && primary > 0.5) ||
        (alternate != null && alternate.isFinite() && alternate > 0.5)

/** Retains the independent menu-button topics arriving as live telemetry deltas. */
internal class GamepadMenuButtons(prefix: String) {
    private val start = "$prefix/Start"
    private val options = "$prefix/Options"
    private val back = "$prefix/Back"
    private val share = "$prefix/Share"
    private var startValue = false
    private var optionsValue = false
    private var backValue = false
    private var shareValue = false
    val startPressed: Boolean get() = startValue || optionsValue
    val backPressed: Boolean get() = backValue || shareValue

    fun accept(key: String, value: Double): Boolean {
        when (key) {
            start -> startValue = gamepadMenuPressed(value, null)
            options -> optionsValue = gamepadMenuPressed(value, null)
            back -> backValue = gamepadMenuPressed(value, null)
            share -> shareValue = gamepadMenuPressed(value, null)
            else -> return false
        }
        return true
    }
}
