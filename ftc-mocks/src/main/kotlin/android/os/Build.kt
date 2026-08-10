package android.os

/**
 * Mock of `android.os.Build` for desktop simulation and unit tests.
 *
 * The real `android.jar` from the Android SDK contains stub implementations that throw
 * `RuntimeException("Stub!")` at runtime. Several third-party libraries access
 * `Build.VERSION.SDK_INT` during class initialization to decide which Java features are available.
 * This mock shadows the SDK stub when running desktop unit tests and simulation.
 */
object Build {
    /** Public constructor matches Android's nested class for reflection-heavy JVM libraries. */
    class VERSION {
        companion object {
            @JvmField
            val SDK_INT: Int = 0

            @JvmField
            val RELEASE: String = "0.0"

            @JvmField
            val CODENAME: String = "REL"
        }
    }

    @JvmField
    val MANUFACTURER: String = "ARES-Sim"

    @JvmField
    val MODEL: String = "DesktopSimulator"

    @JvmField
    val HARDWARE: String = "desktop"

    @JvmField
    val PRODUCT: String = "ares_sim"

    @JvmField
    val FINGERPRINT: String = "ares/sim/desktop:0.0/SIM/0:userdebug/test-keys"
}
