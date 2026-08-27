package android.util

/**
 * Mock of `android.util.Log` for desktop simulation and unit tests.
 */
object Log {
    const val VERBOSE: Int = 2
    const val DEBUG: Int = 3
    const val INFO: Int = 4
    const val WARN: Int = 5
    const val ERROR: Int = 6
    const val ASSERT: Int = 7

    @JvmStatic
    fun isLoggable(tag: String?, level: Int): Boolean = true

    @JvmStatic
    fun println(priority: Int, tag: String?, msg: String?): Int {
        System.out.println("$priority/$tag: $msg")
        return 0
    }

    @JvmStatic
    fun v(tag: String?, msg: String?): Int {
        System.out.println("V/$tag: $msg")
        return 0
    }

    @JvmStatic
    fun d(tag: String?, msg: String?): Int {
        System.out.println("D/$tag: $msg")
        return 0
    }

    @JvmStatic
    fun i(tag: String?, msg: String?): Int {
        System.out.println("I/$tag: $msg")
        return 0
    }

    @JvmStatic
    fun w(tag: String?, msg: String?): Int {
        System.out.println("W/$tag: $msg")
        return 0
    }

    @JvmStatic
    fun w(tag: String?, msg: String?, tr: Throwable?): Int {
        System.out.println("W/$tag: $msg")
        tr?.printStackTrace()
        return 0
    }

    @JvmStatic
    fun e(tag: String?, msg: String?): Int {
        System.err.println("E/$tag: $msg")
        return 0
    }

    @JvmStatic
    fun e(tag: String?, msg: String?, tr: Throwable?): Int {
        System.err.println("E/$tag: $msg")
        tr?.printStackTrace()
        return 0
    }

    @JvmStatic
    fun getStackTraceString(tr: Throwable?): String {
        return tr?.toString() ?: ""
    }
}
