@file:Suppress("UNUSED_PARAMETER")
package android.content

/**
 * Class implementation for Context.
 *
 * Robotics framework control component.
 */
open class Context {
    /** Desktop asset resolver compatible with the Android `Context.assets` surface. */
    open val assets: android.content.res.AssetManager = android.content.res.AssetManager()
}
