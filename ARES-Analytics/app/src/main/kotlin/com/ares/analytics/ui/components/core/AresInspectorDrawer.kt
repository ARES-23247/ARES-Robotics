package com.ares.analytics.ui.components.core

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

/**
 * Standardized slide-out inspector drawer for ARES Builder environments.
 * Provides a non-intrusive, focused overlay on the right side of the screen
 * for editing hardware devices, statefields, control loops, bindings, and postures
 * without causing in-page layout shifts or vertical scrolling jumps.
 */
@Composable
fun AresInspectorDrawer(
    isOpen: Boolean,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    categoryBadge: String? = null,
    stableId: String? = null,
    icon: ImageVector? = null,
    width: Dp = 480.dp,
    doneButtonText: String = "Done",
    isDoneEnabled: Boolean = true,
    onDone: (() -> Unit)? = onDismiss,
    onDelete: (() -> Unit)? = null,
    deleteButtonText: String = "Delete",
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (!isOpen) return

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        // Semi-transparent backdrop to dismiss drawer on outside click
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        // Slide-in drawer container
        AnimatedVisibility(
            visible = isOpen,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(min = 380.dp, max = width)
                    .semantics { contentDescription = "$title Inspector" },
                color = AresSurfaceElevated,
                border = BorderStroke(1.dp, AresBorder),
                shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
                shadowElevation = 16.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Sticky Header
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AresSurface)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (icon != null) {
                                    Icon(icon, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                                }
                                Text(
                                    text = title,
                                    color = AresTextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                headerAction?.invoke()
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close Inspector", tint = AresTextSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        if (categoryBadge != null || stableId != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (categoryBadge != null) {
                                    Surface(
                                        color = AresCyan.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp),
                                        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.4f)),
                                    ) {
                                        Text(
                                            text = categoryBadge.uppercase(),
                                            color = AresCyan,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                if (stableId != null) {
                                    Text(
                                        text = "ID · $stableId",
                                        color = AresTextTertiary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = AresBorder)

                    // Scrollable Inspector Body
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content,
                    )

                    HorizontalDivider(color = AresBorder)

                    // Sticky Action Footer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AresSurface)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onDelete != null) {
                            OutlinedButton(
                                onClick = onDelete,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AresError),
                                border = BorderStroke(1.dp, AresError.copy(alpha = 0.5f)),
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(deleteButtonText, color = AresError, fontSize = 12.sp)
                            }
                        } else {
                            Spacer(Modifier.width(1.dp))
                        }

                        Button(
                            onClick = { onDone?.invoke() ?: onDismiss() },
                            enabled = isDoneEnabled,
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        ) {
                            Text(doneButtonText, color = AresBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
