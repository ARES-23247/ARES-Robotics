// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.models.League
import com.ares.analytics.util.ProjectLayout
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldImageLoaderTest {
    @Test
    fun `image free configuration succeeds without inventing a filename`() {
        val root = Files.createTempDirectory("field-image-absent").toFile()
        try {
            for (league in League.entries) for (path in listOf(null, "", " \t ")) {
                assertNull(FieldImageLoader.load(root.path, league, path).getOrThrow())
            }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `missing corrupt and escaped images return failures without modifying files`() {
        val root = Files.createTempDirectory("field-image-failures").toFile()
        try {
            for (league in League.entries) {
                val corrupt = ProjectLayout.fieldImageFile(root.path, league, "corrupt.png")
                corrupt.parentFile.mkdirs()
                corrupt.writeText("not an image")
                for (path in listOf("missing.png", "corrupt.png", "../outside.png", corrupt.absolutePath)) {
                    assertTrue(FieldImageLoader.load(root.path, league, path).isFailure, "$league: $path")
                }
                assertEquals("not an image", corrupt.readText())
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `relative image paths decode through the native loader in all leagues`() {
        val root = Files.createTempDirectory("field-image-decode").toFile()
        try {
            val bytes = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
            for (league in League.entries) {
                val image = ProjectLayout.fieldImageFile(root.path, league, "maps/field.png")
                image.parentFile.mkdirs()
                image.writeBytes(bytes)
                val bitmap = assertNotNull(FieldImageLoader.load(root.path, league, " maps/field.png ").getOrThrow())
                assertEquals(1, bitmap.width)
                assertEquals(1, bitmap.height)
                assertTrue(bytes.contentEquals(image.readBytes()))
            }
        } finally { root.deleteRecursively() }
    }
}
