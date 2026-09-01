package com.ares.analytics.desktop

import java.awt.Component
import java.awt.Container
import java.awt.Canvas
import java.awt.EventQueue
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.ComponentListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowFocusListener
import java.awt.event.WindowListener
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skiko.SkiaLayer

/**
 * Window-facing operations the presentation controller needs. The production binding
 * targets the real AWT/Compose window; tests substitute a fake so controller sequencing
 * can be verified without native windows, JNA, or a Robot capture.
 *
 * [presentWindow] must keep validating through [NativeWindowProbe]: Compose owns
 * visibility and focus, and the exact-peer native check stays the acceptance criterion.
 */
internal interface DesktopWindowPort {
    /** Registers the AWT listeners owned by the presentation controller. */
    fun attachListeners(
        focusListener: WindowFocusListener,
        lifecycleListener: WindowListener,
        visibilityListener: ComponentListener,
    )

    /** Removes the AWT listeners previously registered by [attachListeners]. */
    fun detachListeners(
        focusListener: WindowFocusListener,
        lifecycleListener: WindowListener,
        visibilityListener: ComponentListener,
    )

    /** Current AWT visibility fragment used by the disposal diagnostic. */
    fun disposalDiagnostics(): String

    /** Observation-only native usability check of the exact Compose/AWT peer. */
    fun isNativeWindowUsable(): Boolean

    /** AWT-level presentation (toFront/requestFocus) followed by the native probe. */
    fun presentWindow(): Boolean

    /** Reads the Compose-owned always-on-top state; never writes it. */
    fun isAlwaysOnTop(): Boolean

    /** Verified native handle for diagnostics, or null when the window cannot be verified. */
    fun nativeWindowHandle(): Long?

    /** `size=..., location=..., showing=...` fragment for presentation diagnostics. */
    fun windowDiagnostics(): String

    /** `alwaysOnTop=..., focused=..., active=..., showing=...` fragment for settlement diagnostics. */
    fun windowFocusDiagnostics(): String

    /** Opt-in same-process startup capture; false unless requested and successful. */
    fun attemptStartupCapture(): Boolean

    /** Opt-in WM_CLOSE to the verified native window after a successful capture. */
    fun postCaptureCloseRequest(captureSucceeded: Boolean)
}

/** Production binding to the real desktop window. */
internal class AwtDesktopWindowPort(private val window: Window) : DesktopWindowPort {
    private val captureSequence = AtomicInteger(0)
    private var testCaptureDispatcher: KeyEventDispatcher? = null
    private var testControlServer: DesktopTestControlServer? = null

    override fun attachListeners(
        focusListener: WindowFocusListener,
        lifecycleListener: WindowListener,
        visibilityListener: ComponentListener,
    ) {
        window.addWindowFocusListener(focusListener)
        window.addWindowListener(lifecycleListener)
        window.addComponentListener(visibilityListener)
        attachTestCaptureShortcut()
        attachTestControlServer()
    }

    override fun detachListeners(
        focusListener: WindowFocusListener,
        lifecycleListener: WindowListener,
        visibilityListener: ComponentListener,
    ) {
        window.removeComponentListener(visibilityListener)
        window.removeWindowListener(lifecycleListener)
        window.removeWindowFocusListener(focusListener)
        detachTestControlServer()
        detachTestCaptureShortcut()
    }

    override fun disposalDiagnostics(): String =
        "displayable=${window.isDisplayable}, visible=${window.isVisible}, showing=${window.isShowing}"

    override fun isNativeWindowUsable(): Boolean = NativeWindowProbe.hasUsableNativeWindow(window)

    /** Compose owns visibility, native peer creation, and always-on-top state; native APIs stay observation-only. */
    override fun presentWindow(): Boolean = runCatching {
        require(window.isDisplayable && window.isVisible && window.isShowing) {
            "Compose window is not displayable and visible"
        }
        window.toFront()
        window.requestFocus()
        NativeWindowProbe.hasUsableNativeWindow(window)
    }.onFailure {
        System.err.println("[ARES-Analytics] Desktop window presentation failed: ${it.message}")
    }.getOrDefault(false)

    override fun isAlwaysOnTop(): Boolean = window.isAlwaysOnTop

    override fun nativeWindowHandle(): Long? =
        NativeWindowProbe.ownedTopLevelWindow(window)?.pointer?.let { com.sun.jna.Pointer.nativeValue(it) }

    override fun windowDiagnostics(): String =
        "size=${window.size}, location=${window.location}, showing=${window.isShowing}"

    override fun windowFocusDiagnostics(): String =
        "alwaysOnTop=${window.isAlwaysOnTop}, focused=${window.isFocused}, " +
            "active=${window.isActive}, showing=${window.isShowing}"

    /**
     * Captures the real Compose framebuffer only when the desktop test harness explicitly
     * requests it. Compose renders through Skia, so Java2D `paintAll` and native
     * `PrintWindow` are not valid evidence: both can return a blank client area while the
     * real window is healthy. Keeping capture inside the ARES JVM also avoids false
     * negatives when test-tool processes are assigned different Windows desktops or window
     * stations. Normal application launches do no I/O.
     */
    override fun attemptStartupCapture(): Boolean {
        val outputPath = System.getenv(STARTUP_CAPTURE_ENV)?.trim().orEmpty()
        if (!DesktopPresentationPolicy.captureRequested(outputPath)) return false

        return captureComposeFramebuffer(
            outputFile = File(outputPath).absoluteFile,
            diagnosticLabel = "startup",
        )
    }

    /**
     * Opt-in, on-demand visual evidence for real desktop interaction tests. When
     * [TEST_CAPTURE_DIRECTORY_ENV] is present, F12 writes the next numbered PNG from the
     * live Skia framebuffer. The shortcut does not exist in normal launches.
     */
    private fun attachTestCaptureShortcut() {
        val directoryPath = System.getenv(TEST_CAPTURE_DIRECTORY_ENV)?.trim().orEmpty()
        if (directoryPath.isEmpty() || testCaptureDispatcher != null) return

        val outputDirectory = File(directoryPath).absoluteFile
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED || event.keyCode != KeyEvent.VK_F12) {
                return@KeyEventDispatcher false
            }
            val sequence = captureSequence.incrementAndGet()
            captureNext(outputDirectory, sequence)
            true
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        testCaptureDispatcher = dispatcher
        println(
            "[ARES-Analytics] Desktop test capture enabled: " +
                "press F12 to write PNG evidence under ${outputDirectory.absolutePath}"
        )
    }

    private fun detachTestCaptureShortcut() {
        val dispatcher = testCaptureDispatcher ?: return
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
        testCaptureDispatcher = null
    }

    private fun attachTestControlServer() {
        val rawPort = System.getenv(TEST_CONTROL_PORT_ENV)?.trim().orEmpty()
        if (rawPort.isEmpty() || testControlServer != null) return
        val port = rawPort.toIntOrNull()
        if (port == null || port !in 1_024..65_535) {
            System.err.println("[ARES-Analytics] Ignoring invalid desktop test control port '$rawPort'")
            return
        }

        val server = DesktopTestControlServer(port, ::executeTestCommand)
        runCatching { server.start() }
            .onSuccess {
                testControlServer = server
                println("[ARES-Analytics] Desktop test control listening on 127.0.0.1:$port")
            }
            .onFailure {
                server.close()
                System.err.println("[ARES-Analytics] Desktop test control failed: ${it.message}")
            }
    }

    private fun detachTestControlServer() {
        testControlServer?.close()
        testControlServer = null
    }

    private fun executeTestCommand(command: DesktopTestCommand): String {
        return when (command) {
            is DesktopTestCommand.Click -> executeTestClick(command)
            is DesktopTestCommand.Wheel -> executeTestWheel(command)
            is DesktopTestCommand.Key -> executeTestKey(command)
            is DesktopTestCommand.KeyDown -> {
                dispatchTestKeyEvent(KeyEvent.KEY_PRESSED, command.keyCode, command.modifiers)
                "key down ${command.keyCode}"
            }
            is DesktopTestCommand.KeyUp -> {
                dispatchTestKeyEvent(KeyEvent.KEY_RELEASED, command.keyCode, command.modifiers)
                "key up ${command.keyCode}"
            }
            is DesktopTestCommand.Text -> {
                command.value.forEach { character ->
                    onEventThread {
                        val inputSurface = requireSkiaInputSurface()
                        inputSurface.dispatchEvent(
                            KeyEvent(
                                inputSurface,
                                KeyEvent.KEY_TYPED,
                                System.currentTimeMillis(),
                                0,
                                KeyEvent.VK_UNDEFINED,
                                character,
                            )
                        )
                    }
                }
                "typed ${command.value.length} character(s)"
            }
            is DesktopTestCommand.ChoosePath -> chooseTestPath(command.value)
            DesktopTestCommand.Capture -> onEventThread { captureTestFramebuffer() }
            DesktopTestCommand.Close -> {
                onEventThread { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) }
                "close requested"
            }
            DesktopTestCommand.Ping -> "pong"
        }
    }

    private fun chooseTestPath(path: String): String {
        val requestedPath = File(path)
        require(requestedPath.isAbsolute) { "chooser path must be absolute" }
        val selectedPath = requestedPath.absoluteFile
        require(selectedPath.exists()) { "chooser path does not exist: ${selectedPath.absolutePath}" }
        onEventThread {
            val chooser = Window.getWindows()
                .asSequence()
                .filter { it.isShowing }
                .mapNotNull(::findFileChooser)
                .firstOrNull()
                ?: error("no visible file chooser is open")
            chooser.selectedFile = selectedPath
            chooser.approveSelection()
        }
        return "selected ${selectedPath.absolutePath}"
    }

    private fun executeTestClick(command: DesktopTestCommand.Click): String {
        onEventThread {
            val inputSurface = requireSkiaInputSurface()
            require(command.x in 0 until inputSurface.width && command.y in 0 until inputSurface.height) {
                "click is outside the Compose surface: ${command.x},${command.y}"
            }
            inputSurface.requestFocusInWindow()
            val timestamp = System.currentTimeMillis()
            inputSurface.dispatchEvent(
                MouseEvent(inputSurface, MouseEvent.MOUSE_MOVED, timestamp, 0, command.x, command.y, 0, false)
            )
            inputSurface.dispatchEvent(
                MouseEvent(
                    inputSurface,
                    MouseEvent.MOUSE_PRESSED,
                    timestamp,
                    InputEvent.BUTTON1_DOWN_MASK,
                    command.x,
                    command.y,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                )
            )
        }
        Thread.sleep(75)
        onEventThread {
            val inputSurface = requireSkiaInputSurface()
            inputSurface.dispatchEvent(
                MouseEvent(
                    inputSurface,
                    MouseEvent.MOUSE_RELEASED,
                    System.currentTimeMillis(),
                    0,
                    command.x,
                    command.y,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                )
            )
        }
        return "clicked ${command.x},${command.y}"
    }

    private fun executeTestWheel(command: DesktopTestCommand.Wheel): String {
        onEventThread {
            val inputSurface = requireSkiaInputSurface()
            require(command.x in 0 until inputSurface.width && command.y in 0 until inputSurface.height) {
                "wheel position is outside the Compose surface: ${command.x},${command.y}"
            }
            inputSurface.requestFocusInWindow()
            inputSurface.dispatchEvent(
                MouseWheelEvent(
                    inputSurface,
                    MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(),
                    0,
                    command.x,
                    command.y,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    3,
                    command.rotation,
                )
            )
        }
        return "wheel ${command.rotation} at ${command.x},${command.y}"
    }

    private fun executeTestKey(command: DesktopTestCommand.Key): String {
        dispatchTestKeyEvent(KeyEvent.KEY_PRESSED, command.keyCode, command.modifiers)
        Thread.sleep(40)
        dispatchTestKeyEvent(KeyEvent.KEY_RELEASED, command.keyCode, command.modifiers)
        return "key ${command.keyCode}"
    }

    private fun dispatchTestKeyEvent(eventId: Int, keyCode: Int, modifiers: Int) {
        onEventThread {
            val inputSurface = requireSkiaInputSurface()
            inputSurface.dispatchEvent(
                KeyEvent(
                    inputSurface,
                    eventId,
                    System.currentTimeMillis(),
                    modifiers,
                    keyCode,
                    KeyEvent.CHAR_UNDEFINED,
                )
            )
        }
    }

    private fun captureTestFramebuffer(): String {
        val outputDirectoryPath = System.getenv(TEST_CAPTURE_DIRECTORY_ENV)?.trim().orEmpty()
        require(outputDirectoryPath.isNotEmpty()) {
            "$TEST_CAPTURE_DIRECTORY_ENV is required for CAPTURE"
        }
        val sequence = captureSequence.incrementAndGet()
        check(captureNext(File(outputDirectoryPath).absoluteFile, sequence)) {
            "Compose framebuffer capture failed"
        }
        return "captured $sequence"
    }

    private fun requireSkiaLayer(): SkiaLayer =
        findSkiaLayer(window) ?: error("the Compose SkiaLayer is not attached to the desktop window")

    /** Skiko delegates input listeners to the heavyweight Canvas nested inside SkiaLayer. */
    private fun requireSkiaInputSurface(): Component {
        val layer = requireSkiaLayer()
        return findCanvas(layer) ?: error("the Compose SkiaLayer input Canvas is not attached")
    }

    private fun findCanvas(component: Component): Canvas? {
        if (component is Canvas) return component
        if (component !is Container) return null
        component.components.forEach { child ->
            findCanvas(child)?.let { return it }
        }
        return null
    }

    private fun findFileChooser(component: Component): JFileChooser? {
        if (component is JFileChooser) return component
        if (component !is Container) return null
        component.components.forEach { child ->
            findFileChooser(child)?.let { return it }
        }
        return null
    }

    private fun <T> onEventThread(action: () -> T): T {
        if (EventQueue.isDispatchThread()) return action()
        val result = AtomicReference<Result<T>>()
        EventQueue.invokeAndWait { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }

    private fun captureNext(outputDirectory: File, sequence: Int): Boolean =
        captureComposeFramebuffer(
            outputFile = File(outputDirectory, "capture-${sequence.toString().padStart(3, '0')}.png"),
            diagnosticLabel = "on-demand",
        )

    private fun captureComposeFramebuffer(outputFile: File, diagnosticLabel: String): Boolean = runCatching {
        require(window.isShowing) { "desktop window is not showing" }
        val layer = findSkiaLayer(window)
            ?: error("the Compose SkiaLayer is not attached to the desktop window")
        layer.renderImmediately()
        val bitmap = layer.screenshot()
            ?: error("the Compose SkiaLayer did not provide a framebuffer snapshot")

        val width = bitmap.width
        val height = bitmap.height
        require(width > 0 && height > 0) { "the Compose framebuffer has invalid size ${width}x$height" }
        outputFile.parentFile?.mkdirs()
        bitmap.use { capturedBitmap ->
            Image.makeFromBitmap(capturedBitmap).use { image ->
                val encoded = image.encodeToData(EncodedImageFormat.PNG, 100)
                    ?: error("Skia could not encode the Compose framebuffer as PNG")
                encoded.use { data -> outputFile.writeBytes(data.bytes) }
            }
        }
        println(
            "[ARES-Analytics] Desktop $diagnosticLabel capture written: " +
                "path=${outputFile.absolutePath}, size=${width}x$height"
        )
    }.onFailure {
        System.err.println("[ARES-Analytics] Desktop $diagnosticLabel capture failed: ${it.message}")
    }.isSuccess

    private fun findSkiaLayer(component: Component): SkiaLayer? {
        if (component is SkiaLayer) return component
        if (component !is Container) return null
        component.components.forEach { child ->
            findSkiaLayer(child)?.let { return it }
        }
        return null
    }

    /** Posts the same native WM_CLOSE used by the external tester, but only in opt-in capture runs. */
    override fun postCaptureCloseRequest(captureSucceeded: Boolean) {
        if (!captureSucceeded ||
            !DesktopPresentationPolicy.closeAfterCaptureRequested(System.getenv(STARTUP_CAPTURE_CLOSE_ENV))
        ) {
            return
        }
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return

        val hwnd = NativeWindowProbe.ownedTopLevelWindow(window)
        if (hwnd == null) {
            System.err.println("[ARES-Analytics] Desktop startup capture close failed: native HWND is missing")
            return
        }

        com.sun.jna.platform.win32.User32.INSTANCE.PostMessage(
            hwnd,
            com.sun.jna.platform.win32.WinUser.WM_CLOSE,
            com.sun.jna.platform.win32.WinDef.WPARAM(0L),
            com.sun.jna.platform.win32.WinDef.LPARAM(0L),
        )
        println(
            "[ARES-Analytics] Desktop startup capture WM_CLOSE posted: " +
                "hwnd=${com.sun.jna.Pointer.nativeValue(hwnd.pointer)}, requestSent=true"
        )
    }
}
