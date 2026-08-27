package com.ares.analytics.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Desktop
import java.net.URI
import org.jetbrains.skia.Image

/**
 * Stable ARES 23247 brand tokens shared with aresfirst.org.
 *
 * These colors identify the team. Semantic state such as error, warning, success, or selection
 * belongs in [AresColorPalette] and must not be inferred from a brand color alone.
 */
object AresBrandTokens {
    val Red = Color(0xFFC00000)
    val RedReadableOnDark = Color(0xFFFF6B6B)
    val Bronze = Color(0xFFCD7F32)
    val Gold = Color(0xFFFFB81C)
    val TechnicalCyan = Color(0xFF00E5FF)
    val Obsidian = Color(0xFF1A1A1A)
    val Marble = Color(0xFFF9F9F9)

    val AppBackground = Color(0xFF0D0F14)
    val AppSurface = Color(0xFF161A22)
    val AppSurfaceElevated = Color(0xFF1E2330)
    val TextPrimary = Color(0xFFE8ECF4)
    val TextSecondary = Color(0xFF9CA3B4)
    val TextTertiary = Color(0xFF8992A6)
    val Border = Color(0xFF6B7B98)
    val BorderFocused = Color(0xFF8B9BB8)
    val OnBrightAccent = Color(0xFF05070A)
    val Error = Color(0xFFFF5252)
    val Success = Color(0xFF66BB6A)
    val ColorblindError = Color(0xFFFF6D00)
    val ColorblindSuccess = Color(0xFF2979FF)
}

/** Official ARES destinations shown in app chrome and the learning center. */
enum class AresBrandDestination(val label: String, val buttonLabel: String, val url: String) {
    TEAM_WEBSITE("ARES 23247 website", "Team website", "https://aresfirst.org/"),
    TEAM_GITHUB("ARES 23247 on GitHub", "Team GitHub", "https://github.com/ARES-23247"),
}

/** Opens only a compile-time ARES destination; failure leaves the offline app fully usable. */
internal fun openAresBrandDestination(destination: AresBrandDestination): Boolean = runCatching {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        return@runCatching false
    }
    Desktop.getDesktop().browse(URI.create(destination.url))
    true
}.getOrDefault(false)

private val aresLogoBitmap by lazy(LazyThreadSafetyMode.PUBLICATION) {
    val bytes = requireNotNull(AresBrandTokens::class.java.classLoader.getResourceAsStream("brand/ares-mark.webp")) {
        "Packaged ARES logo resource is missing"
    }.use { it.readBytes() }
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

private val aresAppIconBitmap by lazy(LazyThreadSafetyMode.PUBLICATION) {
    val bytes = requireNotNull(AresBrandTokens::class.java.classLoader.getResourceAsStream("brand/ares-studio-app.png")) {
        "Packaged ARES Robotics Studio icon resource is missing"
    }.use { it.readBytes() }
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

/** Returns the packaged official team mark without relying on deprecated classpath painters. */
@Composable
fun rememberAresLogoPainter(): Painter = remember { BitmapPainter(aresLogoBitmap) }

/** Returns the simplified desktop application mark, legible at window and taskbar sizes. */
@Composable
fun rememberAresAppIconPainter(): Painter = remember { BitmapPainter(aresAppIconBitmap) }
