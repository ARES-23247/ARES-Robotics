@file:Suppress("UNUSED_PARAMETER")
package android.content

/**
 * Class implementation for Android [Context].
 *
 * Robotics framework desktop double providing access to mock Android runtime services,
 * specifically asset resolution via [assets].
 *
 * In headless desktop simulation and test execution, this replaces the Android OS Context
 * allowing simulated OpModes and robot control loops to query configuration files,
 * Apriltag layout definitions, and local simulation assets.
 *
 * All lookups operate against local filesystem paths relative to the current workspace root.
 * This ensures deterministic execution across developer workstations and CI runners.
 */
open class Context {
    /**
     * Desktop asset resolver compatible with the Android `Context.assets` surface.
     *
     * Provides file stream access to assets bundled under standard `TeamCode/src/main/assets`
     * or repository asset directories without requiring an Android device or emulator.
     *
     * Returns an [android.content.res.AssetManager] instance configured for desktop file access.
     */
    open val assets: android.content.res.AssetManager = android.content.res.AssetManager()
}
