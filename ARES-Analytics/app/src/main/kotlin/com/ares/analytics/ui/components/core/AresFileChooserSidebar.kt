package com.ares.analytics.ui.components.core

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import java.io.File
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AresFileChooserSidebar(state: AresFileChooserState) = with(state) {
// Left Quick Access Sidebar
Column(
    modifier = Modifier
        .width(210.dp)
        .fillMaxHeight()
        .verticalScroll(rememberScrollState())
        .background(AresSurfaceElevated)
        .border(1.dp, AresBorder)
        .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
) {
    Text(
        text = "QUICK ACCESS",
        color = AresTextSecondary,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
    )

    SidebarItem(
        icon = Icons.Default.Home,
        label = "User Home",
        selected = currentDirectory == userHome,
        onClick = { navigateTo(userHome) }
    )

    val docDir = File(userHome, "Documents")
    if (docDir.exists()) {
        SidebarItem(
            icon = Icons.Default.Folder,
            label = "Documents",
            selected = currentDirectory == docDir,
            onClick = { navigateTo(docDir) }
        )
    }

    val downloadDir = File(userHome, "Downloads")
    if (downloadDir.exists()) {
        SidebarItem(
            icon = Icons.Default.Download,
            label = "Downloads",
            selected = currentDirectory == downloadDir,
            onClick = { navigateTo(downloadDir) }
        )
    }

    val desktopDir = File(userHome, "Desktop")
    if (desktopDir.exists()) {
        SidebarItem(
            icon = Icons.Default.Computer,
            label = "Desktop",
            selected = currentDirectory == desktopDir,
            onClick = { navigateTo(desktopDir) }
        )
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = "ROBOTICS",
        color = AresTextSecondary,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
    )

    val aresDocDir = File(docDir, "ARES")
    if (aresDocDir.exists()) {
        SidebarItem(
            icon = Icons.Default.PrecisionManufacturing,
            label = "ARES Projects",
            selected = currentDirectory == aresDocDir,
            badgeText = "ARES",
            onClick = { navigateTo(aresDocDir) }
        )
    }

    val robotsDir = File(userHome, "Robots")
    if (robotsDir.exists()) {
        SidebarItem(
            icon = Icons.Default.PrecisionManufacturing,
            label = "Robots",
            selected = currentDirectory == robotsDir,
            onClick = { navigateTo(robotsDir) }
        )
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = "DRIVES",
        color = AresTextSecondary,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
    )

    val roots = remember { File.listRoots().orEmpty() }
    roots.forEach { root ->
        val driveLabel = root.absolutePath.ifEmpty { "Drive" }
        SidebarItem(
            icon = Icons.Default.Storage,
            label = driveLabel,
            selected = currentDirectory == root,
            onClick = { navigateTo(root) }
        )
    }
}


}
@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badgeText: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) AresCyanGlow else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) AresCyan else AresTextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = if (selected) AresCyan else AresTextPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (badgeText != null) {
            Surface(
                color = AresCyan.copy(alpha = 0.15f),
                shape = RoundedCornerShape(3.dp),
            ) {
                Text(
                    text = badgeText,
                    color = AresCyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}
