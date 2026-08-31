package com.areslib.frc.input

import com.areslib.input.InputFrame
import com.areslib.util.RobotClock
import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.GenericHID

/**
 * Stable zero-based button indexes reserved after the raw WPILib button range.
 *
 * WPILib numbers raw buttons from **1**, while [InputFrame] numbers them from **0**. Therefore
 * WPILib raw button `n` is always frame button `n - 1`. The first POV is exposed as four virtual
 * buttons at indexes 120–123 so its location does not shift when a Vader 5 Pro or another HID
 * reports more raw buttons than a standard Xbox controller.
 */
public object FrcButtonIndex {
    public const val MAX_RAW_BUTTON_COUNT: Int = 120
    public const val POV_UP: Int = 120
    public const val POV_RIGHT: Int = 121
    public const val POV_DOWN: Int = 122
    public const val POV_LEFT: Int = 123
    public const val COUNT_WITH_PRIMARY_POV: Int = 124

    /** Converts one WPILib one-based raw button number to its [InputFrame] index. */
    public fun fromWpilibRawButton(buttonNumber: Int): Int {
        require(buttonNumber >= 1 && buttonNumber <= MAX_RAW_BUTTON_COUNT) {
            "WPILib raw button must be in 1..$MAX_RAW_BUTTON_COUNT"
        }
        return buttonNumber - 1
    }
}

/**
 * Minimal HID read surface used by [FrcInputFrameAdapter].
 *
 * The interface also makes the safety behavior testable without loading WPILib JNI. Raw button
 * numbers passed to [rawButton] retain WPILib's one-based convention; axes and POV indexes are
 * zero-based.
 */
public interface FrcHidSource {
    public fun isConnected(): Boolean
    public fun axisCount(): Int
    public fun buttonCount(): Int
    public fun povCount(): Int
    public fun rawAxis(axisIndex: Int): Double
    public fun rawButton(buttonNumber: Int): Boolean
    public fun pov(povIndex: Int): Int
}

/** Production [FrcHidSource] backed by a WPILib [GenericHID] and Driver Station metadata. */
public class WpilibGenericHidSource(private val hid: GenericHID) : FrcHidSource {
    public override fun isConnected(): Boolean = DriverStation.isJoystickConnected(hid.port)

    public override fun axisCount(): Int = DriverStation.getStickAxisCount(hid.port)

    public override fun buttonCount(): Int = DriverStation.getStickButtonCount(hid.port)

    public override fun povCount(): Int = DriverStation.getStickPOVCount(hid.port)

    public override fun rawAxis(axisIndex: Int): Double = hid.getRawAxis(axisIndex)

    public override fun rawButton(buttonNumber: Int): Boolean = hid.getRawButton(buttonNumber)

    public override fun pov(povIndex: Int): Int = hid.getPOV(povIndex)
}

/**
 * Copies every Driver Station-reported axis, raw button, and the primary POV into an [InputFrame].
 *
 * Unlike an `XboxController` adapter, this class never truncates the device to the standard Xbox
 * surface. All raw buttons reported by devices such as the Flydigi Vader 5 Pro are preserved (up
 * to [FrcButtonIndex.MAX_RAW_BUTTON_COUNT]). Raw axes retain the WPILib convention and must be
 * finite in `[-1, 1]`; malformed readings become zero. Disconnects, impossible metadata counts,
 * or a runtime read failure publish a completely neutral disconnected frame.
 *
 * The backing [InputFrame] is owned by the caller and reused on every robot loop. Normal sampling
 * performs no collection, array, lambda, or snapshot allocation.
 */
public class FrcInputFrameAdapter(
    private val source: FrcHidSource,
) {
    public constructor(hid: GenericHID) : this(WpilibGenericHidSource(hid))

    /** Samples the current HID values into [frame] without allocating on the normal path. */
    public fun sampleInto(
        frame: InputFrame,
        sampleTimeNanos: Long = RobotClock.nanoTime(),
    ) {
        require(frame.buttonCapacity >= FrcButtonIndex.COUNT_WITH_PRIMARY_POV) {
            "FRC input frame requires at least ${FrcButtonIndex.COUNT_WITH_PRIMARY_POV} buttons"
        }

        val connected: Boolean
        val axisCount: Int
        val rawButtonCount: Int
        val povCount: Int
        try {
            connected = source.isConnected()
            if (!connected) {
                frame.beginSample(connected = false, sampleTimeNanos = sampleTimeNanos)
                return
            }
            axisCount = source.axisCount()
            rawButtonCount = source.buttonCount()
            povCount = source.povCount()
        } catch (_: RuntimeException) {
            frame.beginSample(connected = false, sampleTimeNanos = sampleTimeNanos)
            return
        }

        if (
            axisCount < 0 || axisCount > frame.axisCapacity ||
            rawButtonCount < 0 || rawButtonCount > FrcButtonIndex.MAX_RAW_BUTTON_COUNT ||
            povCount < 0
        ) {
            frame.beginSample(connected = false, sampleTimeNanos = sampleTimeNanos)
            return
        }

        frame.beginSample(
            connected = true,
            reportedAxisCount = axisCount,
            reportedButtonCount = FrcButtonIndex.COUNT_WITH_PRIMARY_POV,
            sampleTimeNanos = sampleTimeNanos,
        )

        try {
            var axisIndex = 0
            while (axisIndex < axisCount) {
                frame.setAxis(axisIndex, validAxis(source.rawAxis(axisIndex)))
                axisIndex++
            }

            var wpilibButtonNumber = 1
            while (wpilibButtonNumber <= rawButtonCount) {
                frame.setButton(
                    FrcButtonIndex.fromWpilibRawButton(wpilibButtonNumber),
                    source.rawButton(wpilibButtonNumber),
                )
                wpilibButtonNumber++
            }

            val primaryPov = if (povCount > 0) source.pov(0) else POV_NOT_PRESSED
            val validPov = primaryPov >= 0 && primaryPov <= 359
            frame.setButton(
                FrcButtonIndex.POV_UP,
                validPov && (primaryPov >= 315 || primaryPov <= 45),
            )
            frame.setButton(
                FrcButtonIndex.POV_RIGHT,
                validPov && primaryPov >= 45 && primaryPov <= 135,
            )
            frame.setButton(
                FrcButtonIndex.POV_DOWN,
                validPov && primaryPov >= 135 && primaryPov <= 225,
            )
            frame.setButton(
                FrcButtonIndex.POV_LEFT,
                validPov && primaryPov >= 225 && primaryPov <= 315,
            )
        } catch (_: RuntimeException) {
            // A changing/disconnected Driver Station device can invalidate counts between metadata
            // and value reads. Clear the partial sample so no stale or half-read command survives.
            frame.beginSample(connected = false, sampleTimeNanos = sampleTimeNanos)
        }
    }

    private fun validAxis(value: Double): Double =
        if (value.isFinite() && value >= -1.0 && value <= 1.0) value else 0.0

    private companion object {
        const val POV_NOT_PRESSED: Int = -1
    }
}
