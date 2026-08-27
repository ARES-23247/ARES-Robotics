package com.ares.analytics.desktop

/**
 * Read-only native window verification. The exact peer handle from
 * `Native.getWindowPointer(window)` must appear in `EnumWindows`, owned by the current PID,
 * valid, and visible — a same-process GLFW/helper HWND is never accepted as the app window.
 *
 * Observation-only by contract: nothing here may restore, focus, move, or close a window.
 */
internal object NativeWindowProbe {
    fun isExpectedNativeWindow(
        expectedHandle: Long,
        candidateHandle: Long,
        ownerPid: Int,
        currentPid: Int,
        valid: Boolean,
        visible: Boolean,
    ): Boolean = expectedHandle != 0L &&
        candidateHandle == expectedHandle &&
        ownerPid == currentPid &&
        valid &&
        visible

    fun ownedTopLevelWindow(window: java.awt.Window): com.sun.jna.platform.win32.WinDef.HWND? = runCatching {
        if (!window.isDisplayable || !window.isVisible || !window.isShowing) return@runCatching null

        val user32 = com.sun.jna.platform.win32.User32.INSTANCE
        val currentPid = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcessId()
        val expectedPointer = com.sun.jna.Native.getWindowPointer(window) ?: return@runCatching null
        val expectedHandle = com.sun.jna.Pointer.nativeValue(expectedPointer)
        if (expectedHandle == 0L) return@runCatching null
        var ownedWindow: com.sun.jna.platform.win32.WinDef.HWND? = null

        // Native.getWindowPointer identifies the actual Compose/AWT peer but is not sufficient by
        // itself: AWT can retain a stale handle after its HWND disappears. Cross-check that exact
        // handle against EnumWindows, which is the same OS-level truth used by strict UI capture.
        user32.EnumWindows({ candidate, _ ->
            val ownerPid = com.sun.jna.ptr.IntByReference()
            user32.GetWindowThreadProcessId(candidate, ownerPid)
            val matches = isExpectedNativeWindow(
                expectedHandle = expectedHandle,
                candidateHandle = com.sun.jna.Pointer.nativeValue(candidate.pointer),
                ownerPid = ownerPid.value,
                currentPid = currentPid,
                valid = user32.IsWindow(candidate),
                visible = user32.IsWindowVisible(candidate),
            )
            if (matches) ownedWindow = candidate
            !matches
        }, null)

        ownedWindow
    }.getOrNull()

    fun hasUsableNativeWindow(window: java.awt.Window): Boolean {
        if (!window.isDisplayable || !window.isVisible || !window.isShowing) return false
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return true
        return ownedTopLevelWindow(window) != null
    }
}
