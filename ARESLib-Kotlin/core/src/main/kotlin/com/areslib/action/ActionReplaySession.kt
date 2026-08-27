package com.areslib.action

import com.areslib.state.RobotState
import com.areslib.reducer.rootReducer
import java.io.File

/**
 * A cursor-based replay session for time-travel debugging.
 *
 * Loads a recorded action log (JSONL from [ActionLogger]) and provides
 * step-by-step navigation through the state history. States are lazily
 * computed and cached via sparse checkpoints for efficient random-access
 * scrubbing without requiring O(n) storage of every intermediate state.
 *
 * **Checkpoint Strategy**: A [RobotState] snapshot is cached every
 * [CHECKPOINT_INTERVAL] actions. Seeking to any index requires at most
 * [CHECKPOINT_INTERVAL] reducer applications from the nearest cached
 * checkpoint, giving O(1) amortized cost for sequential scrubbing and
 * O([CHECKPOINT_INTERVAL]) worst-case cost for random seeks.
 *
 * Usage:
 * ```
 * val session = ActionReplaySession.load(logFile)
 * session.seekToIndex(100)  // Jump to action #100
 * val state = session.currentState
 * session.stepForward()     // Advance one action
 * session.stepBackward()    // Rewind one action
 * ```
 *
 * @property actions The ordered list of deserialized actions from the log file.
 * @property reducer The pure reducer function used to compute state transitions.
 * @property initialState The initial [RobotState] before any actions are applied.
 */
/**
 * Class implementation for Action Replay Session.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
class ActionReplaySession private constructor(
    private val actions: List<RobotAction>,
    private val reducer: (RobotState, RobotAction) -> RobotState,
    private val initialState: RobotState
) {
    /** Total number of actions in this replay session. */
    val size: Int get() = actions.size

    /**
     * Current cursor position.
     *
     * - `0` = initial state (before any actions have been applied).
     * - `n` = state after the first `n` actions have been applied.
     * - `size` = final state (after all actions).
     */
    var currentIndex: Int = 0
        private set

    /**
     * Sparse checkpoint cache: stores [RobotState] snapshots at regular intervals
     * to avoid full-history replay on every seek. Key = action index, Value = state
     * after applying all actions up to that index.
     */
    private val checkpoints = java.util.TreeMap<Int, RobotState>()
    private var lastComputedIndex: Int = -1
    private var lastComputedState: RobotState? = null

    init {
        checkpoints[0] = initialState
    }

    /** The [RobotState] at the current cursor position. */
    val currentState: RobotState
        get() = stateAtIndex(currentIndex)

    /**
     * The action that was applied to reach the current state,
     * or `null` if the cursor is at index 0 (initial state).
     */
    val currentAction: RobotAction?
        get() = if (currentIndex > 0) actions[currentIndex - 1] else null

    /** The timestamp (ms) of the current action, or 0 if at the initial state. */
    val currentTimestampMs: Long
        get() = currentAction?.timestampMs ?: 0L

    /**
     * Advance the cursor by one action.
     *
     * @return `true` if the cursor was advanced, `false` if already at the end.
     */
    fun stepForward(): Boolean {
        if (currentIndex >= actions.size) return false
        currentIndex++
        return true
    }

    /**
     * Rewind the cursor by one action.
     *
     * @return `true` if the cursor was rewound, `false` if already at the start.
     */
    fun stepBackward(): Boolean {
        if (currentIndex <= 0) return false
        currentIndex--
        return true
    }

    /**
     * Seek to a specific action index.
     *
     * @param index Target index in range `[0, size]`. Index 0 is the initial
     *              state, index [size] is the state after all actions.
     * @throws IllegalArgumentException if [index] is out of range.
     */
    fun seekToIndex(index: Int) {
        require(index in 0..actions.size) {
            "Index $index out of range [0, ${actions.size}]"
        }
        currentIndex = index
    }

    /**
     * Seek to the state closest to the given timestamp using binary search.
     *
     * If the exact timestamp is found, the cursor lands immediately after that
     * action. If not, the cursor lands at the first action whose timestamp
     * exceeds [timestampMs] (i.e. the latest state at or before the target time).
     *
     * @param timestampMs Target timestamp in milliseconds (same epoch as
     *                    [RobotAction.timestampMs]).
     */
    fun seekToTimestamp(timestampMs: Long) {
        var lo = 0
        var hi = actions.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val actionTs = actions[mid].timestampMs
            when {
                actionTs < timestampMs -> lo = mid + 1
                actionTs > timestampMs -> hi = mid - 1
                else -> { currentIndex = mid + 1; return }
            }
        }
        currentIndex = lo
    }

    /**
     * Compute the state at a given index, using cached checkpoints for efficiency.
     *
     * Finds the nearest checkpoint at or before [index], replays the remaining
     * actions from that checkpoint, and caches new checkpoints along the way.
     */
    private fun stateAtIndex(index: Int): RobotState {
        if (index == lastComputedIndex && lastComputedState != null) {
            return lastComputedState!!
        }

        val exactHit = checkpoints[index]
        if (exactHit != null) {
            lastComputedIndex = index
            lastComputedState = exactHit
            return exactHit
        }

        // Use floorEntry to find nearest cached checkpoint <= index
        val floorEntry = checkpoints.floorEntry(index)
        val startIdx = floorEntry?.key ?: 0
        var state = floorEntry?.value ?: initialState

        for (i in startIdx until index) {
            state = reducer(state, actions[i])
            if ((i + 1) % CHECKPOINT_INTERVAL == 0) {
                checkpoints[i + 1] = state
            }
        }

        lastComputedIndex = index
        lastComputedState = state
        return state
    }

    /**
     * Recomputes state from the initial state to [targetIndex], caching
     * checkpoints along the way. This is the cold-start path only invoked
     * when no closer checkpoint exists.
     */
    private fun recomputeFromStart(targetIndex: Int): RobotState {
        var state = initialState
        for (i in 0 until targetIndex) {
            state = reducer(state, actions[i])
            if ((i + 1) % CHECKPOINT_INTERVAL == 0) {
                checkpoints[i + 1] = state
            }
        }
        return state
    }

    /**
     * Returns the time range of this replay session as a pair of
     * `(firstActionTimestampMs, lastActionTimestampMs)`.
     *
     * @return `(0L, 0L)` if the session contains no actions.
     */
    fun getTimeRange(): Pair<Long, Long> {
        if (actions.isEmpty()) return 0L to 0L
        return actions.first().timestampMs to actions.last().timestampMs
    }

    /**
     * Returns the total duration of this replay session in milliseconds,
     * measured from the first action's timestamp to the last.
     */
    fun getDurationMs(): Long {
        val (start, end) = getTimeRange()
        return end - start
    }

    /**
     * Returns `true` if the cursor is at the initial state (before any actions).
     */
    fun isAtStart(): Boolean = currentIndex == 0

    /**
     * Returns `true` if the cursor is at the final state (after all actions).
     */
    fun isAtEnd(): Boolean = currentIndex == actions.size

    /**
     * Returns the progress through the replay as a fraction in `[0.0, 1.0]`.
     * Returns `0.0` if the session is empty.
     */
    fun getProgress(): Double {
        if (actions.isEmpty()) return 0.0
        return currentIndex.toDouble() / actions.size
    }

    companion object {
        /**
         * Number of actions between cached [RobotState] checkpoints.
         * Lower values use more memory but make random seeks faster.
         * Higher values save memory but make worst-case seeks slower.
         */
        private const val CHECKPOINT_INTERVAL = 50

        /**
         * Load a replay session from a JSONL action log file.
         *
         * @param logFile The `.jsonl` file recorded by [ActionLogger].
         * @param reducer The reducer function to use for state computation.
         *                Defaults to [rootReducer].
         * @param initialState The initial state before any actions are applied.
         * @return A new [ActionReplaySession] ready for cursor-based navigation.
         */
        fun load(
            logFile: File,
            reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer,
            initialState: RobotState = RobotState()
        ): ActionReplaySession {
            val actions = ActionReplay.parseActions(logFile)
            return ActionReplaySession(actions, reducer, initialState)
        }

        /**
         * Create a replay session from an already-parsed list of actions.
         *
         * Useful when actions have been filtered, transformed, or sourced
         * from a non-file origin (e.g. network stream, in-memory buffer).
         *
         * @param actions The ordered list of actions to replay.
         * @param reducer The reducer function to use for state computation.
         * @param initialState The initial state before any actions are applied.
         */
        fun fromActions(
            actions: List<RobotAction>,
            reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer,
            initialState: RobotState = RobotState()
        ): ActionReplaySession {
            return ActionReplaySession(actions, reducer, initialState)
        }
    }
}
