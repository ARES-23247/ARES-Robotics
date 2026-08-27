@file:Suppress("UNUSED_PARAMETER")
package android.util

/**
 * Object implementation for Base64.
 *
 * Robotics framework control component.
 */
object Base64 {
    @JvmField
    val NO_WRAP = 2
    
    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        return java.util.Base64.getEncoder().encodeToString(input)
    }
}
