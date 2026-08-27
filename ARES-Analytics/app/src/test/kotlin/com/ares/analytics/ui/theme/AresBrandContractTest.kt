package com.ares.analytics.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AresBrandContractTest {
    private val contract: JsonObject by lazy {
        val stream = javaClass.classLoader.getResourceAsStream("design/ares-design-tokens.json")
        assertNotNull(stream, "The checked-in ARES design-token snapshot must be packaged")
        stream.use { JsonParser.parseReader(InputStreamReader(it)).asJsonObject }
    }

    @Test
    fun `Kotlin brand tokens match the website-owned contract snapshot`() {
        val brand = contract.getAsJsonObject("brand")
        val semantic = contract.getAsJsonObject("semanticDark")

        assertColor(brand, "red", AresBrandTokens.Red)
        assertColor(brand, "redReadableOnDark", AresBrandTokens.RedReadableOnDark)
        assertColor(brand, "bronze", AresBrandTokens.Bronze)
        assertColor(brand, "gold", AresBrandTokens.Gold)
        assertColor(brand, "technicalCyan", AresBrandTokens.TechnicalCyan)
        assertColor(brand, "obsidian", AresBrandTokens.Obsidian)
        assertColor(brand, "marble", AresBrandTokens.Marble)
        assertColor(semantic, "background", AresBrandTokens.AppBackground)
        assertColor(semantic, "surface", AresBrandTokens.AppSurface)
        assertColor(semantic, "surfaceElevated", AresBrandTokens.AppSurfaceElevated)
        assertColor(semantic, "onBrightAccent", AresBrandTokens.OnBrightAccent)
        assertColor(semantic, "error", AresBrandTokens.Error)
        assertColor(semantic, "success", AresBrandTokens.Success)
    }

    @Test
    fun `Analytics product role stays cyan-led while brand red remains identity-only`() {
        val analytics = contract.getAsJsonObject("productRoles").getAsJsonObject("analytics")
        assertEquals("brand.technicalCyan", analytics.get("primaryAction").asString)
        assertEquals("brand.red", analytics.get("brandMoment").asString)
        assertEquals(AresBrandTokens.TechnicalCyan, getAresColors(false, false).cyan)
        assertEquals(AresBrandTokens.Gold, getAresColors(false, false).gold)
    }

    @Test
    fun `official destinations and logo are packaged with the app`() {
        val links = contract.getAsJsonObject("links")
        assertEquals(links.get("teamWebsite").asString, AresBrandDestination.TEAM_WEBSITE.url)
        assertEquals(links.get("teamGitHub").asString, AresBrandDestination.TEAM_GITHUB.url)

        val logo = javaClass.classLoader.getResourceAsStream("brand/ares-mark.webp")
        assertNotNull(logo, "The official ARES mark must be packaged for app chrome and the window icon")
        logo.use {
            val bytes = it.readBytes()
            assertTrue(bytes.size > 10_000, "The packaged mark should be the complete transparent team artwork")
            assertEquals("RIFF", bytes.take(4).map(Byte::toInt).map(Int::toChar).joinToString(""))
        }
    }

    @Test
    fun `shared bright accents meet the contract normal-text threshold`() {
        val minimum = contract.getAsJsonObject("accessibility")
            .get("normalTextMinimumContrast").asDouble
        val fills = listOf(
            AresBrandTokens.TechnicalCyan,
            AresBrandTokens.Gold,
            AresBrandTokens.Bronze,
            AresBrandTokens.Error,
            AresBrandTokens.Success,
        )
        fills.forEach { fill ->
            assertTrue(contrastRatio(AresBrandTokens.OnBrightAccent, fill) >= minimum)
        }
    }

    private fun assertColor(container: JsonObject, key: String, actual: Color) {
        assertEquals(container.get(key).asString, actual.toHexRgb(), key)
    }

    private fun Color.toHexRgb(): String = "#%06X".format(toArgb() and 0xFFFFFF)
}
