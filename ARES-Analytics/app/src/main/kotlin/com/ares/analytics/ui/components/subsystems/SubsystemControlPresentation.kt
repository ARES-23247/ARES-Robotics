package com.ares.analytics.ui.components.subsystems

import com.areslib.subsystem.SubsystemHardwareKind

internal fun subsystemControlOutputUnit(kind: SubsystemHardwareKind?): String = when (kind) {
    SubsystemHardwareKind.MOTOR -> "V"
    SubsystemHardwareKind.PRISM_DRIVER -> "µs"
    SubsystemHardwareKind.BUZZER -> "MIDI note"
    SubsystemHardwareKind.DIGITAL_OUTPUT -> "0 / 1"
    else -> "normalized"
}
