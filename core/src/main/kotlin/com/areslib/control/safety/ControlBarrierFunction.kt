package com.areslib.control.safety

/**
 * Mutable Output Buffer Container for Filtered Control Barrier Function (CBF) State Targets.
 *
 * Populated in-place by [ControlBarrierFunction.filter] to eliminate heap allocation overhead in 50Hz–1000Hz loops.
 *
 * @property x1Filtered Filtered, safety-guaranteed target value for protective state $x_1$.
 * @property x2Filtered Filtered, safety-guaranteed target value for invading state $x_2$.
 */
class CBFFilteredOutput {
    @JvmField var x1Filtered: Double = 0.0
    @JvmField var x2Filtered: Double = 0.0

    /**
     * Updates internal buffer values in-place without heap allocations.
     *
     * @param x1 Target value for protective state $x_1$.
     * @param x2 Target value for invading state $x_2$.
     */
    fun setTo(x1: Double, x2: Double) {
        this.x1Filtered = x1
        this.x2Filtered = x2
    }
}

/**
 * Graduate-Grade Analytical First-Order Control Barrier Function (CBF) Safety Filter.
 *
 * Enforces provable forward-invariant mechanical safety constraints coupling two kinematic degrees of freedom:
 * $$\mathcal{C} = \left\{(x_1, x_2) \in \mathbb{R}^2 : h(x_1, x_2) = x_1 - m \cdot x_2 - c \ge 0\right\}$$
 * where $x_1$ is a protective state (e.g. intake deployment angle in degrees), $x_2$ is an invading state (e.g. elevator extension in meters),
 * $m$ is the coupling gradient slope ($^\circ/m$), and $c$ is the baseline safety margin offset.
 *
 * ### Mathematical Control Barrier Theory & Orthogonal Projection:
 * Continuous-time differential inequality for forward invariance:
 * $$\dot{h}(x_1, x_2) \ge -\alpha \cdot h(x_1, x_2)$$
 * Discrete-time lower bound for next step safety margin:
 * $$h_{next, min} = \max\left(0, (1 - \alpha \cdot \Delta t) \cdot h(x_{current})\right)$$
 * If commanded targets $(x_{1,target}, x_{2,target})$ violate $h_{next, min}$, they are orthogonally projected (least-squares distance) onto the safe half-space boundary $x_1 - m x_2 = h_{next,min} + c$:
 * $$\lambda = \frac{m \cdot x_{2,target} - x_{1,target} + h_{next,min} + c}{1 + m^2}$$
 * $$x_{1,projected} = x_{1,target} + \lambda, \quad x_{2,projected} = x_{2,target} - m \cdot \lambda$$
 *
 * ### Physical Units & Properties:
 * - Protective State ($x_1$): Degrees ($^\circ$) or Radians ($rad$)
 * - Invading State ($x_2$): Meters ($m$) or Radians ($rad$)
 * - Coupling Slope ($m$): Units of $x_1$ per unit of $x_2$
 * - Margin Offset ($c$): Units of $x_1$
 * - Safety Rate ($\alpha$): Inverse seconds ($s^{-1}$)
 * - Timestep ($\Delta t$): Seconds ($s$)
 *
 * ### Zero-GC Compliance:
 * Operates in-place populating a pre-allocated [CBFFilteredOutput] buffer to guarantee zero heap allocations.
 *
 * @property m Scaling gradient coupling slope between $x_1$ and $x_2$.
 * @property c Absolute baseline safety margin offset threshold.
 * @property alpha Safety convergence rate parameter $\alpha > 0$ ($s^{-1}$).
 * @see CBFFilteredOutput
 */
class ControlBarrierFunction(
    val m: Double,
    val c: Double = 0.0,
    val alpha: Double = 5.0
) {
    /**
     * Filters commanded target values $(x_{1,target}, x_{2,target})$ to mathematically guarantee the safety invariant $h(x) \ge 0$.
     *
     * Writes results directly into pre-allocated [outBuffer].
     *
     * @param x1Target Commanded target value for protective state $x_1$.
     * @param x2Target Commanded target value for invading state $x_2$.
     * @param x1Current Current physical measured value of state $x_1$.
     * @param x2Current Current physical measured value of state $x_2$.
     * @param dtSeconds Timestep interval in seconds ($\Delta t > 0$).
     * @param outBuffer Pre-allocated [CBFFilteredOutput] buffer receiving safe target values.
     */
    fun filter(
        x1Target: Double,
        x2Target: Double,
        x1Current: Double,
        x2Current: Double,
        dtSeconds: Double,
        outBuffer: CBFFilteredOutput
    ) {
        filter(x1Target, x2Target, x1Current, x2Current, dtSeconds, c, outBuffer)
    }

    /**
     * Filters commanded target values using a dynamic margin offset override [cOverride].
     *
     * @param x1Target Commanded target value for protective state $x_1$.
     * @param x2Target Commanded target value for invading state $x_2$.
     * @param x1Current Current physical measured value of state $x_1$.
     * @param x2Current Current physical measured value of state $x_2$.
     * @param dtSeconds Timestep interval in seconds ($\Delta t > 0$).
     * @param cOverride Dynamic safety margin offset override.
     * @param outBuffer Pre-allocated [CBFFilteredOutput] buffer receiving safe target values.
     */
    fun filter(
        x1Target: Double,
        x2Target: Double,
        x1Current: Double,
        x2Current: Double,
        dtSeconds: Double,
        cOverride: Double,
        outBuffer: CBFFilteredOutput
    ) {
        if (x1Target.isNaN() || x1Target.isInfinite() ||
            x2Target.isNaN() || x2Target.isInfinite() ||
            x1Current.isNaN() || x1Current.isInfinite() ||
            x2Current.isNaN() || x2Current.isInfinite() ||
            dtSeconds.isNaN() || dtSeconds.isInfinite() || dtSeconds <= 0.0
        ) {
            outBuffer.setTo(x1Target, x2Target)
            return
        }

        // 1. Calculate current safety margin h(x_current)
        val hCurrent = x1Current - m * x2Current - cOverride

        // 2. Compute minimum allowed safety margin for next step hNextMin
        // If current state is already violating boundary, aggressively force safety convergence to 0.0 immediately
        val hNextMin = if (hCurrent < 0.0) 0.0 else kotlin.math.max(0.0, (1.0 - alpha * dtSeconds) * hCurrent)

        // Check if desired target is already safe and satisfies rate limits
        val hTarget = x1Target - m * x2Target - cOverride
        if (hTarget >= hNextMin) {
            // Target is in the safe set, pass through unchanged
            outBuffer.setTo(x1Target, x2Target)
            return
        }

        // 3. Target violates safety envelope. Project desired targets orthogonally onto the safe boundary line
        // Line equation: x1 - m * x2 = hNextMin + cOverride
        val lLimit = hNextMin + cOverride
        val factor = (m * x2Target - x1Target + lLimit) / (1.0 + m * m)
        var x1Projected = x1Target + factor
        var x2Projected = x2Target - m * factor

        // Safety clamp: Ensure the projected invading state does not go negative if physically bounded
        if (x2Projected < 0.0) {
            x2Projected = 0.0
            if (x1Projected < cOverride) {
                x1Projected = cOverride
            }
        }

        // Clean up numeric noise near stow position
        if (x2Projected < 1e-4) {
            x2Projected = 0.0
        }

        outBuffer.setTo(x1Projected, x2Projected)
    }
}
