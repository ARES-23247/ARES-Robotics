// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.hardware.actuator.PrismPwmPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightingColorMapperTest {
    @Test
    fun `indicator solid stops agree with named local output presets`() {
        val expected = mapOf(
            IndicatorLightColor.OFF to ("Off" to 0xFF202020),
            IndicatorLightColor.RED to ("Red" to 0xFFFF0000),
            IndicatorLightColor.ORANGE to ("Orange" to 0xFFFF8000),
            IndicatorLightColor.YELLOW to ("Yellow" to 0xFFFFFF00),
            IndicatorLightColor.GREEN to ("Green" to 0xFF00FF00),
            IndicatorLightColor.CYAN to ("Cyan" to 0xFF00FFFF),
            IndicatorLightColor.BLUE to ("Blue" to 0xFF0000FF),
            IndicatorLightColor.PURPLE to ("Purple" to 0xFF8000FF),
            IndicatorLightColor.VIOLET to ("Purple" to 0xFF8000FF),
            IndicatorLightColor.WHITE to ("White" to 0xFFFFFFFF),
        )
        for ((preset, display) in expected) {
            assertEquals(display.first, IndicatorLightColorMapper.positionToName(preset.position))
            assertEquals(display.second.toInt(), IndicatorLightColorMapper.positionToColor(preset.position).toArgb())
        }
        for (position in listOf(0.240, 0.2525, 0.265)) {
            assertEquals("Rainbow", IndicatorLightColorMapper.positionToName(position))
            assertEquals(0xFFFF007F.toInt(), IndicatorLightColorMapper.positionToColor(position).toArgb())
        }
        assertEquals("Off", IndicatorLightColorMapper.positionToName(-1.0))
        assertEquals("White", IndicatorLightColorMapper.positionToName(2.0))
        for (i in 0..1000) {
            val color = IndicatorLightColorMapper.positionToColor(i / 1000.0)
            assertEquals(1f, color.alpha)
            assertTrue(listOf(color.red, color.green, color.blue).all { it.isFinite() && it in 0f..1f })
        }
    }

    @Test
    fun `nonfinite inputs never acquire a valid light program color or name`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals("Unknown", IndicatorLightColorMapper.positionToName(invalid))
            assertEquals(0xFF202020.toInt(), IndicatorLightColorMapper.positionToColor(invalid).toArgb())
            assertEquals(Color.Transparent, PrismColorMapper.pulseWidthToColor(invalid))
        }
    }

    @Test
    fun `Prism local solid and basic animation presets have representative colors`() {
        val expected = mapOf(
            PrismPwmPreset.SOLID_OFF to Color.Transparent,
            PrismPwmPreset.SOLID_RED to Color.Red, PrismPwmPreset.SOLID_ORANGE to Color(0xFFFF8A00),
            PrismPwmPreset.SOLID_YELLOW to Color.Yellow, PrismPwmPreset.SOLID_GREEN to Color.Green,
            PrismPwmPreset.SOLID_CYAN to Color.Cyan, PrismPwmPreset.SOLID_BLUE to Color.Blue,
            PrismPwmPreset.SOLID_PURPLE to Color(0xFF9C5CFF), PrismPwmPreset.SOLID_WHITE to Color.White,
            PrismPwmPreset.SINE_WAVE_RED to Color.Red, PrismPwmPreset.SINE_WAVE_YELLOW to Color.Yellow,
            PrismPwmPreset.SINE_WAVE_GREEN to Color.Green, PrismPwmPreset.SINE_WAVE_BLUE to Color.Blue,
            PrismPwmPreset.SINE_WAVE_PURPLE to Color(0xFF9C5CFF),
            PrismPwmPreset.PULSE_RED to Color.Red, PrismPwmPreset.PULSE_YELLOW to Color.Yellow,
            PrismPwmPreset.PULSE_GREEN to Color.Green, PrismPwmPreset.PULSE_BLUE to Color.Blue,
        )
        for ((preset, color) in expected)
            assertEquals(color, PrismColorMapper.pulseWidthToColor(preset.pulseWidthUs.toDouble()), preset.name)
        for (preset in PrismPwmPreset.entries.filter { it != PrismPwmPreset.SOLID_OFF })
            assertEquals(1f, PrismColorMapper.pulseWidthToColor(preset.pulseWidthUs.toDouble()).alpha, preset.name)
        for (pulse in listOf(499.0, 2501.0, 1049.0, 1090.0))
            assertEquals(Color.Transparent, PrismColorMapper.pulseWidthToColor(pulse))
    }
}
