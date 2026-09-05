package com.ares.analytics.ui

import com.ares.analytics.ui.components.core.AresFileChooserLauncher
import com.ares.analytics.ui.components.core.AresFileChooserMode
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.EventQueue
import java.awt.Component
import java.awt.Container
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.junit.Assume.assumeTrue

/** Explicit Windows desktop validation; ordinary headless CI uses the rendering test. */
class AresFileChooserNativeTest {
    @Test fun nativeDialogRendersSelectsAndCancels() {
        assumeTrue(System.getenv("ARES_CHOOSER_NATIVE_TEST") == "true")
        val root = createTempDirectory("ares-native-chooser").toFile().canonicalFile
        File(root, "Lightbot").mkdir()
        try {
            repeat(2) { cycle ->
                val result = CompletableFuture<List<File>?>()
                val thread = Thread({
                    try {
                        result.complete(AresFileChooserLauncher.show(AresFileChooserMode.DIRECTORY,
                            "ARES chooser validation", root))
                    } catch (failure: Throwable) { result.completeExceptionally(failure) }
                }, "ares-chooser-validation").apply { isDaemon = true; start() }
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                    while (System.nanoTime() < deadline && AresFileChooserLauncher.activeDialog?.isShowing != true) {
                        if (result.isDone) result.get()
                        Thread.sleep(50)
                    }
                    val dialog = assertNotNull(AresFileChooserLauncher.activeDialog)
                    Thread.sleep(1500)
                    EventQueue.invokeAndWait {
                        assertTrue(dialog.isShowing)
                        val handle = HWND(Native.getWindowPointer(dialog))
                        assertTrue(User32.INSTANCE.IsWindow(handle))
                        assertTrue(User32.INSTANCE.IsWindowVisible(handle))
                        val output = File("build/diagnostics/file-chooser-tests/native-$cycle.png")
                        output.parentFile.mkdirs()
                        val layer = assertNotNull(findLayer(dialog))
                        layer.renderImmediately()
                        assertNotNull(layer.screenshot()).use { bitmap ->
                            Image.makeFromBitmap(bitmap).use { image ->
                                assertNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                                    output.writeBytes(data.bytes)
                                }
                            }
                        }
                        if (cycle == 0) assertNotNull(AresFileChooserLauncher.testSelectionOverride).invoke(root)
                        else dialog.dispose()
                    }
                    val selected = result.get(10, TimeUnit.SECONDS)
                    if (cycle == 0) assertEquals(listOf(root), selected) else assertNull(selected)
                    thread.join(1000)
                    assertFalse(thread.isAlive)
                    assertNull(AresFileChooserLauncher.activeDialog)
                    assertNull(AresFileChooserLauncher.testSelectionOverride)
                } finally {
                    EventQueue.invokeAndWait { AresFileChooserLauncher.activeDialog?.dispose() }
                }
            }
        } finally { root.deleteRecursively() }
    }

    private fun findLayer(component: Component): SkiaLayer? {
        if (component is SkiaLayer) return component
        if (component is Container) component.components.forEach { findLayer(it)?.let { layer -> return layer } }
        return null
    }
}
