package com.areslib.input

import java.util.Arrays

/**
 * Per-frame button-consumption state shared by generated chord and single-button sources.
 *
 * The state is preallocated and keyed by [InputFrame.sequence]. Generated runtimes evaluate
 * suppressing chords before lower-priority constituent bindings. When a chord activates, its raw
 * buttons read as neutral to [SuppressibleButtonSource] for the remainder of that sample.
 */
class ButtonSuppressionState(buttonCapacity: Int = InputFrame.DEFAULT_BUTTON_CAPACITY) {
    private val suppressedWords = LongArray((buttonCapacity + Long.SIZE_BITS - 1) / Long.SIZE_BITS)
    private var sequence = Long.MIN_VALUE

    val capacity: Int = buttonCapacity

    init {
        require(buttonCapacity > 0) { "buttonCapacity must be positive" }
    }

    internal fun beginFrame(frameSequence: Long) {
        if (sequence == frameSequence) return
        Arrays.fill(suppressedWords, 0L)
        sequence = frameSequence
    }

    internal fun suppress(buttonIndex: Int) {
        require(buttonIndex in 0 until capacity) { "button index is outside suppression state" }
        val wordIndex = buttonIndex / Long.SIZE_BITS
        suppressedWords[wordIndex] = suppressedWords[wordIndex] or (1L shl (buttonIndex % Long.SIZE_BITS))
    }

    internal fun isSuppressed(buttonIndex: Int): Boolean {
        require(buttonIndex in 0 until capacity) { "button index is outside suppression state" }
        val wordIndex = buttonIndex / Long.SIZE_BITS
        return suppressedWords[wordIndex] and (1L shl (buttonIndex % Long.SIZE_BITS)) != 0L
    }
}

/** Raw button that becomes neutral when a higher-priority chord consumes it in this sample. */
class SuppressibleButtonSource(
    val buttonIndex: Int,
    private val suppression: ButtonSuppressionState
) : DigitalSource {
    init {
        require(buttonIndex in 0 until suppression.capacity) { "buttonIndex is outside suppression state" }
    }

    override fun sample(frame: InputFrame, nowNanos: Long): Boolean {
        suppression.beginFrame(frame.sequence)
        return !suppression.isSuppressed(buttonIndex) && frame.button(buttonIndex)
    }

    override fun reset() = Unit
}

/**
 * Button chord that consumes every constituent button through complete neutral re-arming.
 *
 * Place its [DigitalBinding] before constituent bindings. To prevent a single-button press from
 * leaking before the rest of the chord arrives, generated runtimes use an effective constituent
 * press debounce at least as long as [simultaneityWindowNanos]. Once accepted, every member stays
 * consumed until all members are released, preventing a partial chord release from firing a
 * constituent single-button action.
 */
class SuppressingButtonChordSource(
    buttonIndexes: IntArray,
    val simultaneityWindowNanos: Long = ChordSource.DEFAULT_SIMULTANEITY_WINDOW_NANOS,
    private val suppression: ButtonSuppressionState
) : DigitalSource {
    private val indexes = buttonIndexes.copyOf()
    private val delegate: ChordSource
    private var consumedUntilNeutral = false

    init {
        require(indexes.size >= 2) { "a suppressing chord requires at least two buttons" }
        require(indexes.distinct().size == indexes.size) { "chord buttons must be unique" }
        indexes.forEach { require(it in 0 until suppression.capacity) { "button index is outside suppression state" } }
        delegate = ChordSource(indexes.map(::RawButtonSource), simultaneityWindowNanos)
    }

    override fun sample(frame: InputFrame, nowNanos: Long): Boolean {
        suppression.beginFrame(frame.sequence)
        val active = delegate.sample(frame, nowNanos)
        if (active) consumedUntilNeutral = true
        if (consumedUntilNeutral) {
            var allNeutral = true
            var index = 0
            while (index < indexes.size) {
                if (frame.button(indexes[index])) allNeutral = false
                suppression.suppress(indexes[index])
                index++
            }
            if (allNeutral) consumedUntilNeutral = false
        }
        return active
    }

    override fun reset() {
        delegate.reset()
        consumedUntilNeutral = false
    }
}
