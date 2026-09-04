package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.hardware.HardwareAddressKind
import com.ares.analytics.service.hardware.HardwareInventoryItem
import com.ares.analytics.ui.theme.*

@Composable
internal fun HardwareItemCard(item: HardwareInventoryItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.displayName, color = AresTextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (item.required) "REQUIRED" else "OPTIONAL", color = if (item.required) AresAmber else AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text("${item.ownerDisplayName} · ${item.role}", color = AresTextSecondary, fontSize = 12.sp)
            Text(item.addressDescription, color = AresTextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            item.configurationDetails.forEach { detail ->
                Text("• $detail", color = AresTextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
            }
            Text(item.setupInstruction(), color = AresCyan, fontSize = 11.sp, lineHeight = 16.sp)
            Text(
                if (item.inverted) "Direction: reversed at the hardware boundary" else "Direction: normal at the hardware boundary",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
            Text(item.sourcePath, color = AresTextTertiary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}

private fun HardwareInventoryItem.setupInstruction(): String = when (addressKind) {
    HardwareAddressKind.FTC_HARDWARE_MAP -> if (address.isBlank()) {
        "Add this device in Configure Robot → Hardware on the Driver Station, then enter the exact same name here."
    } else {
        "In Configure Robot → Hardware on the Driver Station, name this device exactly: $address"
    }
    HardwareAddressKind.XRP_PORT ->
        "Connect this device to $addressDescription; the built-in drivetrain uses motor ports 1 and 2."
    HardwareAddressKind.CAN -> "Set this device to $addressDescription; CAN IDs must be unique on each bus."
    HardwareAddressKind.PWM -> "Connect this device to $addressDescription."
    HardwareAddressKind.I2C -> "Confirm the configured I²C device and address match $addressDescription."
    HardwareAddressKind.SPI -> "Confirm the onboard SPI device is present and its orientation matches the robot descriptor."
    HardwareAddressKind.PNEUMATICS -> "Match both the pneumatics module CAN ID/type and solenoid channel shown in $addressDescription."
    HardwareAddressKind.DIO,
    HardwareAddressKind.ANALOG,
    HardwareAddressKind.UNKNOWN -> "Match the controller configuration to $addressDescription."
}
