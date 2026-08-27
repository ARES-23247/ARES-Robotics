package com.areslib.tuning

/**
 * Compiled boundary between a declared tuning parameter and the controller that consumes it.
 *
 * Implementations update only existing controller/configuration storage. They do not write
 * canonical profiles, dispatch Redux actions, or operate hardware. Callers remain responsible
 * for [TypedTuningRuntime] policy and bound validation before invoking this method.
 */
interface TypedTuningConsumer {
    fun supportsTuningParameter(parameterUid: String): Boolean

    /** Returns true only when the value was committed to the controller's runtime storage. */
    fun applyTuningParameter(parameterUid: String, value: TuningValue): Boolean
}
