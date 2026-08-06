package com.areslib.control.feedback

import kotlin.math.abs
import com.areslib.util.RobotClock

/**
 * A championship-grade Discrete-Time State-Space **Linear-Quadratic Regulator (LQR)**
 * coupled with a Discrete Kalman Filter (**LQE State Observer**).
 *
 * This controller tracks multi-variable systems optimally by evaluating feedback gain matrices
 * resolved from dynamic value-iteration solutions of the Discrete-Time Algebraic Riccati Equation (DARE):
 * $$u_k = -K (x_k - x_{\text{ref}, k})$$
 *
 * It incorporates:
 * - A full state Kalman observer to estimate unmeasured states (such as estimating joint velocities from raw encoder positions).
 * - Control input voltage saturation clipping to prevent motor burnouts.
 */
class LQRController(
    val numStates: Int,
    val numInputs: Int,
    val numOutputs: Int
) {
    // State space representations
    var A = Matrix(numStates, numStates)
    var B = Matrix(numStates, numInputs)
    var C = Matrix(numOutputs, numStates)

    // LQR optimal feedback gain matrix K (numInputs x numStates)
    var K = Matrix(numInputs, numStates)

    // Kalman Observer matrices
    var L = Matrix(numStates, numOutputs) // Kalman gain
    var xHat = Matrix(numStates, 1)      // Estimated state vector
    var u = Matrix(numInputs, 1)         // Last calculated control input

    // Safety and physical limits
    var minU = -12.0 // Min control effort (typically motor voltage)
    var maxU = 12.0  // Max control effort
    var maxUChangePerSec: Double = Double.NaN // Optional slew-rate limit

    // Pre-allocated buffers for zero-allocation calculate()
    private val yMat = Matrix(numOutputs, 1)
    private val xRefMat = Matrix(numStates, 1)
    private val stateError = Matrix(numStates, 1)
    private val kTimesError = Matrix(numInputs, 1)
    private val rawU = Matrix(numInputs, 1)
    private val saturatedU = Matrix(numInputs, 1)
    private val aTimesXHat = Matrix(numStates, 1)
    private val bTimesU = Matrix(numStates, 1)
    private val prediction = Matrix(numStates, 1)
    private val cTimesXHat = Matrix(numOutputs, 1)
    private val measuredDiff = Matrix(numOutputs, 1)
    private val correction = Matrix(numStates, 1)
    private val nextXHat = Matrix(numStates, 1)
    private val outU = DoubleArray(numInputs)
    private var lastWarningTime: Long = 0L

    /**
     * Initializes state space matrices.
     */
    fun setSystemCoefficients(aData: DoubleArray, bData: DoubleArray, cData: DoubleArray) {
        A = Matrix(numStates, numStates, aData)
        B = Matrix(numStates, numInputs, bData)
        C = Matrix(numOutputs, numStates, cData)
    }

    /**
     * Resets the estimated state to the initial condition.
     */
    fun reset(initialState: DoubleArray) {
        require(initialState.size == numStates) { "Initial state must match system dimensions" }
        xHat = Matrix(numStates, 1, initialState)
        u = Matrix(numInputs, 1)
    }

    /**
     * Solves the Discrete-Time Algebraic Riccati Equation (DARE) dynamically
     * using the dynamic programming value iteration approach to compute the optimal gain matrix K.
     *
     * @param Q State cost weighting matrix (numStates x numStates)
     * @param R Control cost weighting matrix (numInputs x numInputs)
     * @param maxIterations Number of iterations to solve DARE.
     * @param tolerance Convergence tolerance.
     */
    fun computeFeedbackGains(Q: Matrix, R: Matrix, maxIterations: Int = 1000, tolerance: Double = 1e-6) {
        require(Q.rows == numStates && Q.cols == numStates)
        require(R.rows == numInputs && R.cols == numInputs)

        var P = Q.copy()
        val AT = A.transpose()
        val BT = B.transpose()

        for (iter in 0 until maxIterations) {
            // P_next = A^T P A - (A^T P B) * (R + B^T P B)^-1 * (B^T P A) + Q
            val ATP = AT.multiply(P)
            val ATPA = ATP.multiply(A)
            val ATPB = ATP.multiply(B)
            val BTP = BT.multiply(P)
            val BTPB = BTP.multiply(B)

            val invTerm = R.add(BTPB).inverse()
            val BTPA = BTP.multiply(A)
            val feedbackTerm = ATPB.multiply(invTerm).multiply(BTPA)

            val PNext = ATPA.subtract(feedbackTerm).add(Q)

            var maxDiff = 0.0
            for (i in 0 until P.rows * P.cols) {
                maxDiff = maxOf(maxDiff, abs(PNext.data[i] - P.data[i]))
            }

            P = PNext
            if (maxDiff < tolerance) break
        }

        // K = (R + B^T P B)^-1 * B^T P A
        val BTP = BT.multiply(P)
        val BTPB = BTP.multiply(B)
        val invTerm = R.add(BTPB).inverse()
        val BTPA = BTP.multiply(A)
        K = invTerm.multiply(BTPA)
    }

    /**
     * Calculates the optimal control input voltage.
     * Updates the discrete Kalman observer state under the hood.
     *
     * WARNING: Returns a pre-allocated array. Do not store the reference or use across threads.
     *
     * @param y Measured sensor outputs from the system.
     * @param xRef Desired reference target state.
     * @param dtSeconds Elapsed time since last update.
     */
    fun calculate(
        y: DoubleArray,
        xRef: DoubleArray,
        dtSeconds: Double
    ): DoubleArray {
        try {
            require(y.size == numOutputs) { "Measurement dimensions mismatch" }
            require(xRef.size == numStates) { "Reference state dimensions mismatch" }
            var inputsValid = y.all { it.isFinite() } && xRef.all { it.isFinite() }
            when {
                !inputsValid || dtSeconds <= 0.0 -> {
                    val now = RobotClock.currentTimeMillis()
                    when {
                        now - lastWarningTime > 2000L -> {
                            System.err.println("LQRController: Invalid inputs detected (finite/dt check failed). Returning pre-allocated zero/last output.")
                            lastWarningTime = now
                        }
                    }
                    return outU
                }
            }

            yMat.copyFrom(y)
            xRefMat.copyFrom(xRef)

            // 1. Calculate control input: u = -K * (xHat - xRef)
            xHat.subtractInto(xRefMat, stateError)
            K.multiplyInto(stateError, kTimesError)
            kTimesError.multiplyScalarInto(-1.0, rawU)

            // 2. Apply motor saturation constraints
            for (i in 0 until numInputs) {
                var inputVal = rawU.get(i, 0)
                
                // Apply slew rate limits
                if (!maxUChangePerSec.isNaN()) {
                    val maxChange = kotlin.math.abs(maxUChangePerSec) * dtSeconds
                    val lastVal = u.get(i, 0)
                    val change = (inputVal - lastVal).coerceIn(-maxChange, maxChange)
                    inputVal = lastVal + change
                }

                // Apply voltage clipping and NaN protection
                val lowLimit = kotlin.math.min(minU, maxU)
                val highLimit = kotlin.math.max(minU, maxU)
                inputVal = inputVal.coerceIn(lowLimit, highLimit)
                if (inputVal.isNaN() || inputVal.isInfinite()) inputVal = 0.0

                saturatedU.set(i, 0, inputVal)
                outU[i] = inputVal
            }

            // 3. Update Discrete Kalman Filter Observer:
            // xHat_next = A * xHat + B * u + L * (y - C * xHat)
            A.multiplyInto(xHat, aTimesXHat)
            B.multiplyInto(saturatedU, bTimesU)
            aTimesXHat.addInto(bTimesU, prediction)
            
            C.multiplyInto(xHat, cTimesXHat)
            yMat.subtractInto(cTimesXHat, measuredDiff)
            L.multiplyInto(measuredDiff, correction)
            
            prediction.addInto(correction, nextXHat)
            xHat.copyFrom(nextXHat)

            u.copyFrom(saturatedU)
            return outU
        } catch (e: Throwable) {
            System.err.println("LQRController FATAL ERROR: ${e.message}")
            // Zero the outputs to prevent runaway
            for (i in 0 until numInputs) outU[i] = 0.0
            return outU
        }
    }

    /**
     * Lightweight Matrix class to support discrete state-space matrix arithmetic.
     */
    class Matrix(val rows: Int, val cols: Int, val data: DoubleArray) {
        constructor(rows: Int, cols: Int) : this(rows, cols, DoubleArray(rows * cols))

        fun get(r: Int, c: Int): Double = data[r * cols + c]
        fun set(r: Int, c: Int, v: Double) { data[r * cols + c] = v }

        fun copyFrom(other: Matrix) {
            System.arraycopy(other.data, 0, this.data, 0, this.data.size)
        }

        fun copyFrom(arr: DoubleArray) {
            System.arraycopy(arr, 0, this.data, 0, this.data.size)
        }

        fun subtractInto(other: Matrix, out: Matrix) {
            for (i in data.indices) out.data[i] = this.data[i] - other.data[i]
        }

        fun addInto(other: Matrix, out: Matrix) {
            for (i in data.indices) out.data[i] = this.data[i] + other.data[i]
        }

        fun multiplyScalarInto(s: Double, out: Matrix) {
            for (i in data.indices) out.data[i] = this.data[i] * s
        }

        fun multiplyInto(other: Matrix, out: Matrix) {
            for (r in 0 until rows) {
                for (c in 0 until other.cols) {
                    var sum = 0.0
                    for (k in 0 until cols) {
                        sum += this.get(r, k) * other.get(k, c)
                    }
                    out.set(r, c, sum)
                }
            }
        }

        fun copy(): Matrix = Matrix(rows, cols, data.clone())

        fun getColumn(col: Int): DoubleArray {
            val colData = DoubleArray(rows)
            for (r in 0 until rows) {
                colData[r] = get(r, col)
            }
            return colData
        }

        fun getColumnInto(col: Int, out: DoubleArray) {
            for (r in 0 until rows) {
                out[r] = get(r, col)
            }
        }

        fun add(other: Matrix): Matrix {
            require(rows == other.rows && cols == other.cols)
            val result = Matrix(rows, cols)
            for (i in data.indices) {
                result.data[i] = this.data[i] + other.data[i]
            }
            return result
        }

        fun subtract(other: Matrix): Matrix {
            require(rows == other.rows && cols == other.cols)
            val result = Matrix(rows, cols)
            for (i in data.indices) {
                result.data[i] = this.data[i] - other.data[i]
            }
            return result
        }

        fun multiplyScalar(s: Double): Matrix {
            val result = Matrix(rows, cols)
            for (i in data.indices) {
                result.data[i] = this.data[i] * s
            }
            return result
        }

        fun multiply(other: Matrix): Matrix {
            require(cols == other.rows)
            val result = Matrix(rows, other.cols)
            for (r in 0 until rows) {
                for (c in 0 until other.cols) {
                    var sum = 0.0
                    for (k in 0 until cols) {
                        sum += this.get(r, k) * other.get(k, c)
                    }
                    result.set(r, c, sum)
                }
            }
            return result
        }

        fun transpose(): Matrix {
            val result = Matrix(cols, rows)
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    result.set(c, r, this.get(r, c))
                }
            }
            return result
        }

        /**
         * Matrix Inversion supporting 1x1, 2x2, and 3x3 matrices.
         */
        fun inverse(): Matrix {
            require(rows == cols) { "Only square matrices can be inverted" }
            val result = Matrix(rows, cols)
            when (rows) {
                1 -> {
                    val det = get(0, 0)
                    if (abs(det) <= 1e-12 || det.isNaN() || det.isInfinite()) {
                        System.err.println("LQRController: 1x1 Matrix is singular! Falling back to Identity to prevent crash.")
                        result.set(0, 0, 1.0)
                    } else {
                        result.set(0, 0, 1.0 / det)
                    }
                }
                2 -> {
                    val a = get(0, 0)
                    val b = get(0, 1)
                    val c = get(1, 0)
                    val d = get(1, 1)
                    val det = a * d - b * c
                    if (abs(det) <= 1e-12 || det.isNaN() || det.isInfinite()) {
                        System.err.println("LQRController: 2x2 Matrix is singular! Falling back to Identity to prevent crash.")
                        result.set(0, 0, 1.0)
                        result.set(0, 1, 0.0)
                        result.set(1, 0, 0.0)
                        result.set(1, 1, 1.0)
                    } else {
                        val invDet = 1.0 / det
                        result.set(0, 0, d * invDet)
                        result.set(0, 1, -b * invDet)
                        result.set(1, 0, -c * invDet)
                        result.set(1, 1, a * invDet)
                    }
                }
                3 -> {
                    val a00 = get(0, 0); val a01 = get(0, 1); val a02 = get(0, 2)
                    val a10 = get(1, 0); val a11 = get(1, 1); val a12 = get(1, 2)
                    val a20 = get(2, 0); val a21 = get(2, 1); val a22 = get(2, 2)

                    val det = a00 * (a11 * a22 - a12 * a21) -
                              a01 * (a10 * a22 - a12 * a20) +
                              a02 * (a10 * a21 - a11 * a20)
                    if (abs(det) <= 1e-12 || det.isNaN() || det.isInfinite()) {
                        System.err.println("LQRController: 3x3 Matrix is singular! Falling back to Identity to prevent crash.")
                        for (i in 0 until 3) {
                            for (j in 0 until 3) {
                                result.set(i, j, if (i == j) 1.0 else 0.0)
                            }
                        }
                    } else {
                        val invDet = 1.0 / det

                    result.set(0, 0, (a11 * a22 - a12 * a21) * invDet)
                    result.set(0, 1, (a02 * a21 - a01 * a22) * invDet)
                    result.set(0, 2, (a01 * a12 - a02 * a11) * invDet)
                    result.set(1, 0, (a12 * a20 - a10 * a22) * invDet)
                    result.set(1, 1, (a00 * a22 - a02 * a20) * invDet)
                    result.set(1, 2, (a02 * a10 - a00 * a12) * invDet)
                    result.set(2, 0, (a10 * a21 - a11 * a20) * invDet)
                    result.set(2, 1, (a01 * a20 - a00 * a21) * invDet)
                    result.set(2, 2, (a00 * a11 - a01 * a10) * invDet)
                    }
                }
                else -> {
                    // Fallback using Gauss-Jordan elimination for higher dimensions
                    val n = rows
                    val temp = Array(n) { DoubleArray(2 * n) }
                    for (i in 0 until n) {
                        for (j in 0 until n) {
                            temp[i][j] = get(i, j)
                        }
                        temp[i][n + i] = 1.0
                    }

                    for (i in 0 until n) {
                        var pivotRow = i
                        for (j in i + 1 until n) {
                            if (abs(temp[j][i]) > abs(temp[pivotRow][i])) {
                                pivotRow = j
                            }
                        }
                        if (pivotRow != i) {
                            val tRow = temp[i]
                            temp[i] = temp[pivotRow]
                            temp[pivotRow] = tRow
                        }
                        val pivot = temp[i][i]
                        if (abs(pivot) <= 1e-12 || pivot.isNaN() || pivot.isInfinite()) {
                            System.err.println("LQRController: Higher-dim Matrix is singular at pivot $i! Falling back to Identity to prevent crash.")
                            for (row in 0 until n) {
                                for (col in 0 until n) {
                                    result.set(row, col, if (row == col) 1.0 else 0.0)
                                }
                            }
                            return result
                        }

                        for (j in 0 until 2 * n) {
                            temp[i][j] /= pivot
                        }
                        for (j in 0 until n) {
                            if (j != i) {
                                val factor = temp[j][i]
                                for (k in 0 until 2 * n) {
                                    temp[j][k] -= factor * temp[i][k]
                                }
                            }
                        }
                    }

                    for (i in 0 until n) {
                        for (j in 0 until n) {
                            result.set(i, j, temp[i][n + j])
                        }
                    }
                }
            }
            return result
        }
    }
}
