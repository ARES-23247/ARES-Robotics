package com.ares.analytics.ui.components.subsystems

import androidx.compose.runtime.Composable
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.areslib.subsystem.SubsystemHardwareConnection
import com.areslib.subsystem.SubsystemHardwareDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemMeasurementSource

@Composable
internal fun XrpHardwareConnectionEditor(
    device: SubsystemHardwareDocument,
    viewModel: SubsystemGeneratorViewModel,
) {
    fun updateChannel(channel: Int) {
        viewModel.updateHardware(device.hardwareId) {
            it.copy(connection = SubsystemHardwareConnection(channel = channel))
        }
    }

    when (device.kind) {
        SubsystemHardwareKind.MOTOR -> IntInput(
            "XRP motor channel (3 or 4)",
            device.connection.channel ?: 3,
            ::updateChannel,
        )
        SubsystemHardwareKind.POSITIONAL_SERVO -> IntInput(
            "XRP servo channel (1–4)",
            device.connection.channel ?: 1,
            ::updateChannel,
        )
        SubsystemHardwareKind.DIGITAL_INPUT -> IntInput(
            "GPIO channel (blank is the built-in button)",
            device.connection.channel ?: 0,
            ::updateChannel,
        )
        SubsystemHardwareKind.DIGITAL_OUTPUT,
        SubsystemHardwareKind.PWM_OUTPUT -> IntInput(
            "XRP GPIO channel (0–29)",
            device.connection.channel ?: 0,
            ::updateChannel,
        )
        SubsystemHardwareKind.ANALOG_INPUT -> {
            val reflectance = device.measurements.any {
                it.source == SubsystemMeasurementSource.REFLECTANCE_NORMALIZED
            }
            IntInput(
                if (reflectance) "Reflectance channel (0 left, 1 middle, 2 right)" else "ADC-capable GPIO channel (26–29)",
                device.connection.channel ?: if (reflectance) 0 else 26,
                ::updateChannel,
            )
        }
        SubsystemHardwareKind.DISTANCE_SENSOR -> FieldGuidance(
            "Uses the XRP's built-in rangefinder; no pin or name is required.",
        )
        SubsystemHardwareKind.IMU -> FieldGuidance(
            "Uses the XRP's built-in IMU for yaw, pitch, roll, angular velocity, and acceleration; no pin or name is required.",
        )
        SubsystemHardwareKind.INDICATOR_LIGHT -> FieldGuidance(
            "No channel uses the green status LED. Channels 0, 1, and 2 control the red, green, and blue RGB components.",
        )
        SubsystemHardwareKind.BUZZER -> FieldGuidance(
            "Uses the board's built-in buzzer. The device preflight verifies it exists before deployment.",
        )
        else -> FieldGuidance("This hardware type is not available in the generated XRP runtime.")
    }
}
