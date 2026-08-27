package com.ares.analytics.ui
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

@Composable
fun test() {
    DropdownMenu(expanded = true, onDismissRequest = {}, offset = DpOffset(0.dp, 0.dp)) {
        DropdownMenuItem(onClick = {}) { Text("Hello") }
    }
}
