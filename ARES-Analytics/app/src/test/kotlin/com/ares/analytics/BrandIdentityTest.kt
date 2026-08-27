package com.ares.analytics

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrandIdentityTest {
    @Test
    fun `public identity is robotics studio while legacy storage identity remains explicit`() {
        assertEquals("ARES Robotics Studio", BuildConfig.PRODUCT_NAME)
        assertEquals("Design • Simulate • Operate • Analyze", BuildConfig.PRODUCT_TAGLINE)
        assertEquals("ARES Analytics", BuildConfig.LEGACY_PRODUCT_NAME)
    }

    @Test
    fun `runtime icon is square transparent and taskbar sized`() {
        val resource = assertNotNull(javaClass.classLoader.getResourceAsStream("brand/ares-studio-app.png"))
        val image: BufferedImage = resource.use { assertNotNull(ImageIO.read(it)) }
        assertEquals(256, image.width)
        assertEquals(256, image.height)
        assertTrue(image.colorModel.hasAlpha())
        assertEquals(0, image.getRGB(0, 0).ushr(24), "Rounded icon corners must remain transparent")
    }
}
