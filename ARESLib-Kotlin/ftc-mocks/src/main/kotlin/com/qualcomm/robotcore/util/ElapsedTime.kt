package com.qualcomm.robotcore.util

/**
 * Desktop subset of FTC SDK `ElapsedTime` backed by `RobotClock`.
 *
 * The reflection lookup keeps the mock tolerant of alternate class-loading layouts, while the
 * direct fallback is used in the normal ARESLib simulator. Because `RobotClock` can be rewound,
 * elapsed values may be negative during an intentionally rewound replay. Instances are not
 * thread-safe; reset and reads should share one simulation owner.
 */
open class ElapsedTime {
    companion object {
        private val robotClockClass: Class<*>? = try {
            Class.forName("com.areslib.util.RobotClock")
        } catch (_: ClassNotFoundException) {
            null
        }

        private val currentTimeMillisMethod = try {
            robotClockClass?.getMethod("currentTimeMillis")
        } catch (_: Exception) {
            null
        }

        private fun getVirtualTimeMs(): Long {
            return if (currentTimeMillisMethod != null) {
                try {
                    currentTimeMillisMethod.invoke(null) as Long
                } catch (_: Exception) {
                    com.areslib.util.RobotClock.currentTimeMillis()
                }
            } else {
                com.areslib.util.RobotClock.currentTimeMillis()
            }
        }
    }

    private var startTime: Long = getVirtualTimeMs()

    /** Sets the elapsed-time origin to the current robot-clock millisecond. */
    fun reset() {
        startTime = getVirtualTimeMs()
    }

    /** Returns elapsed robot-clock time in seconds with millisecond resolution. */
    fun seconds(): Double = (getVirtualTimeMs() - startTime) / 1000.0
    /** Returns elapsed robot-clock time in milliseconds. */
    fun milliseconds(): Double = (getVirtualTimeMs() - startTime).toDouble()
}
