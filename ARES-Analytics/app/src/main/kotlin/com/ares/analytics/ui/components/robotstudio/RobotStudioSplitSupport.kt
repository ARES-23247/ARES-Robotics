package com.ares.analytics.ui.components.robotstudio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import java.awt.Cursor

internal const val MIN_HIERARCHY_WIDTH_DP = 180f
internal const val MAX_HIERARCHY_WIDTH_DP = 380f
internal const val DEFAULT_HIERARCHY_WIDTH_DP = 220f

internal const val MIN_INSPECTOR_WIDTH_DP = 220f
internal const val MAX_INSPECTOR_WIDTH_DP = 440f
internal const val DEFAULT_INSPECTOR_WIDTH_DP = 270f

internal enum class RobotStudioShortcutAction {
    TOGGLE_HIERARCHY,
    TOGGLE_INSPECTOR,
    SAVE_ALL,
}

internal fun clampSplitPaneWidthDp(
    currentDp: Float,
    deltaDp: Float,
    minDp: Float,
    maxDp: Float,
): Float {
    val safeMin = minOf(minDp, maxDp)
    val safeMax = maxOf(minDp, maxDp)
    val next = if (deltaDp.isFinite()) currentDp + deltaDp else currentDp
    return next.coerceIn(safeMin, safeMax)
}

internal fun classifyRobotStudioShortcut(
    isCtrlOrMeta: Boolean,
    isShift: Boolean,
    isAlt: Boolean,
    key: Key,
): RobotStudioShortcutAction? {
    if (!isCtrlOrMeta || isShift || isAlt) return null
    return when (key) {
        Key.B, Key.LeftBracket -> RobotStudioShortcutAction.TOGGLE_HIERARCHY
        Key.I, Key.RightBracket -> RobotStudioShortcutAction.TOGGLE_INSPECTOR
        Key.S -> RobotStudioShortcutAction.SAVE_ALL
        else -> null
    }
}

internal fun collectDirtyRobotStudioStages(
    identityDirty: Boolean,
    drivebaseDirty: Boolean,
    subsystemDirty: Boolean,
    superstructureDirty: Boolean,
    controlsDirty: Boolean,
    routineDirty: Boolean,
): List<String> = buildList {
    if (identityDirty) add("Identity")
    if (drivebaseDirty) add("Drivetrain")
    if (subsystemDirty) add("Subsystems")
    if (superstructureDirty) add("Superstructure")
    if (controlsDirty) add("Controls")
    if (routineDirty) add("Autonomous")
}

internal fun Modifier.robotStudioKeyboardShortcuts(
    onToggleHierarchy: () -> Unit,
    onToggleInspector: () -> Unit,
    onSaveAll: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val action = classifyRobotStudioShortcut(
        isCtrlOrMeta = event.isCtrlPressed || event.isMetaPressed,
        isShift = event.isShiftPressed,
        isAlt = event.isAltPressed,
        key = event.key,
    ) ?: return@onPreviewKeyEvent false
    when (action) {
        RobotStudioShortcutAction.TOGGLE_HIERARCHY -> onToggleHierarchy()
        RobotStudioShortcutAction.TOGGLE_INSPECTOR -> onToggleInspector()
        RobotStudioShortcutAction.SAVE_ALL -> onSaveAll()
    }
    true
}

@Composable
internal fun RobotStudioVerticalSplitter(
    onDragDeltaDp: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .width(6.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(density) {
                detectHorizontalDragGestures { change, dragAmountPx ->
                    change.consume()
                    val deltaDp = with(density) { dragAmountPx.toDp().value }
                    onDragDeltaDp(deltaDp)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(36.dp)
                .background(AresBorder, RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
internal fun RobotStudioDirtySummaryBar(
    dirtyStages: List<String>,
    onSaveAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (dirtyStages.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = AresAmber.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, AresAmber.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Unsaved drafts in ${dirtyStages.joinToString(", ")}",
                color = AresTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Ctrl+B / Ctrl+I panes",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
            Button(
                onClick = onSaveAll,
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                shape = RoundedCornerShape(6.dp),
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save All (Ctrl+S)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun RobotStudioActionToast(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = AresSurfaceElevated,
        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = AresCyan, modifier = Modifier.size(16.dp))
            Text(
                text = message,
                color = AresTextPrimary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss message", tint = AresTextSecondary, modifier = Modifier.size(14.dp))
            }
        }
    }
}
