package com.areslib.math.geometry

/**
 * Mutable 3x3 Double Precision Matrix Scratchpad.
 *
 * Optimized for 100% Zero-GC memory compliance in 3-DOF EKF covariance update loops ($\mathbf{P}_k, \mathbf{Q}, \mathbf{R}, \mathbf{S}, \mathbf{K}$).
 *
 * ### Mathematical Formulations:
 * 1. **Matrix Determinant $\det(\mathbf{A})$**:
 *    $$\det(\mathbf{A}) = m_{00}(m_{11}m_{22} - m_{12}m_{21}) - m_{01}(m_{10}m_{22} - m_{12}m_{20}) + m_{02}(m_{10}m_{21} - m_{11}m_{20})$$
 * 2. **Analytic Matrix Inversion $\mathbf{A}^{-1}$**:
 *    $$\mathbf{A}^{-1} = \frac{1}{\det(\mathbf{A})} \begin{bmatrix}
 *    m_{11}m_{22} - m_{12}m_{21} & m_{02}m_{21} - m_{01}m_{22} & m_{01}m_{12} - m_{02}m_{11} \\
 *    m_{12}m_{20} - m_{10}m_{22} & m_{00}m_{22} - m_{02}m_{20} & m_{02}m_{10} - m_{00}m_{12} \\
 *    m_{10}m_{21} - m_{11}m_{20} & m_{01}m_{20} - m_{00}m_{21} & m_{00}m_{11} - m_{01}m_{10}
 *    \end{bmatrix}$$
 * 3. **Matrix-Vector Product $\mathbf{y} = \mathbf{A} \mathbf{v}$**:
 *    $$\begin{bmatrix} y_0 \\ y_1 \\ y_2 \end{bmatrix} = \begin{bmatrix}
 *    m_{00}v_x + m_{01}v_y + m_{02}v_z \\
 *    m_{10}v_x + m_{11}v_y + m_{12}v_z \\
 *    m_{20}v_x + m_{21}v_y + m_{22}v_z
 *    \end{bmatrix}$$
 *
 * ### Zero-GC Guarantee:
 * Fields are `var` with in-place mutators ([setTo], [addInPlace], [multiplyInPlace])
 * allowing caller code to reuse static/thread-local scratchpads without triggering GC pauses.
 */
data class Matrix3x3(
    var m00: Double = 0.0, var m01: Double = 0.0, var m02: Double = 0.0,
    var m10: Double = 0.0, var m11: Double = 0.0, var m12: Double = 0.0,
    var m20: Double = 0.0, var m21: Double = 0.0, var m22: Double = 0.0
) {
    /** Matrix addition returning a new instance. */
    operator fun plus(other: Matrix3x3) = Matrix3x3(
        m00 + other.m00, m01 + other.m01, m02 + other.m02,
        m10 + other.m10, m11 + other.m11, m12 + other.m12,
        m20 + other.m20, m21 + other.m21, m22 + other.m22
    )

    /**
     * Copies all elements from [other] into this matrix in-place without dynamic memory allocation.
     *
     * @param other Source [Matrix3x3] to copy from.
     */
    fun setTo(other: Matrix3x3) {
        m00 = other.m00; m01 = other.m01; m02 = other.m02
        m10 = other.m10; m11 = other.m11; m12 = other.m12
        m20 = other.m20; m21 = other.m21; m22 = other.m22
    }

    /**
     * Adds [other] into this matrix in-place without dynamic memory allocation.
     *
     * @param other Matrix to add.
     */
    fun addInPlace(other: Matrix3x3) {
        m00 += other.m00; m01 += other.m01; m02 += other.m02
        m10 += other.m10; m11 += other.m11; m12 += other.m12
        m20 += other.m20; m21 += other.m21; m22 += other.m22
    }

    /**
     * Multiplies all matrix elements by a scalar factor in-place without dynamic memory allocation.
     *
     * @param scalar Multiplier factor $s$.
     */
    fun multiplyInPlace(scalar: Double) {
        m00 *= scalar; m01 *= scalar; m02 *= scalar
        m10 *= scalar; m11 *= scalar; m12 *= scalar
        m20 *= scalar; m21 *= scalar; m22 *= scalar
    }

    /** Matrix subtraction. */
    operator fun minus(other: Matrix3x3) = Matrix3x3(
        m00 - other.m00, m01 - other.m01, m02 - other.m02,
        m10 - other.m10, m11 - other.m11, m12 - other.m12,
        m20 - other.m20, m21 - other.m21, m22 - other.m22
    )

    /** Scalar multiplication. */
    operator fun times(scalar: Double) = Matrix3x3(
        m00 * scalar, m01 * scalar, m02 * scalar,
        m10 * scalar, m11 * scalar, m12 * scalar,
        m20 * scalar, m21 * scalar, m22 * scalar
    )

    /** Matrix product multiplication $\mathbf{C} = \mathbf{A} \mathbf{B}$. */
    operator fun times(other: Matrix3x3): Matrix3x3 {
        return Matrix3x3(
            m00 * other.m00 + m01 * other.m10 + m02 * other.m20,
            m00 * other.m01 + m01 * other.m11 + m02 * other.m21,
            m00 * other.m02 + m01 * other.m12 + m02 * other.m22,
            
            m10 * other.m00 + m11 * other.m10 + m12 * other.m20,
            m10 * other.m01 + m11 * other.m11 + m12 * other.m21,
            m10 * other.m02 + m11 * other.m12 + m12 * other.m22,
            
            m20 * other.m00 + m21 * other.m10 + m22 * other.m20,
            m20 * other.m01 + m21 * other.m11 + m22 * other.m21,
            m20 * other.m02 + m21 * other.m12 + m22 * other.m22
        )
    }
    
    /** Matrix-Vector multiplication $\mathbf{y} = \mathbf{A} \mathbf{v}$. */
    operator fun times(vector: Vector3): Vector3 {
        return Vector3(
            m00 * vector.x + m01 * vector.y + m02 * vector.z,
            m10 * vector.x + m11 * vector.y + m12 * vector.z,
            m20 * vector.x + m21 * vector.y + m22 * vector.z
        )
    }

    /**
     * Transposes the matrix $\mathbf{A}^T$.
     *
     * @return Transposed [Matrix3x3].
     */
    fun transpose() = Matrix3x3(
        m00, m10, m20,
        m01, m11, m21,
        m02, m12, m22
    )

    /**
     * Computes the analytic matrix inverse $\mathbf{A}^{-1}$.
     *
     * @return Inverted [Matrix3x3] (returns zero matrix if determinant is non-invertible/near-zero).
     */
    fun inverse(): Matrix3x3 {
        val det = m00 * (m11 * m22 - m12 * m21) -
                  m01 * (m10 * m22 - m12 * m20) +
                  m02 * (m10 * m21 - m11 * m20)

        if (det.isNaN() || det.isInfinite() || kotlin.math.abs(det) < 1e-9) return Matrix3x3() // Return zero matrix if non-invertible

        val invDet = 1.0 / det

        return Matrix3x3(
             (m11 * m22 - m12 * m21) * invDet,
            -(m01 * m22 - m02 * m21) * invDet,
             (m01 * m12 - m02 * m11) * invDet,
            
            -(m10 * m22 - m12 * m20) * invDet,
             (m00 * m22 - m02 * m20) * invDet,
            -(m00 * m12 - m02 * m10) * invDet,
            
             (m10 * m21 - m11 * m20) * invDet,
            -(m00 * m21 - m01 * m20) * invDet,
             (m00 * m11 - m01 * m10) * invDet
        )
    }

    companion object {
        /** Returns a 3x3 Identity Matrix $\mathbf{I}_3$. */
        val IDENTITY get() = Matrix3x3(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        )
    }
}

/**
 * 3D Column Vector $(x, y, z)$.
 *
 * @property x Vector X component.
 * @property y Vector Y component.
 * @property z Vector Z component.
 */
data class Vector3(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    /** Vector addition. */
    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
    /** Vector subtraction. */
    operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
    /** Scalar multiplication. */
    operator fun times(scalar: Double) = Vector3(x * scalar, y * scalar, z * scalar)
    
    /**
     * Calculates the outer product matrix $\mathbf{M} = \mathbf{u} \mathbf{v}^T$.
     *
     * @param other Target vector $\mathbf{v}$.
     * @return 3x3 outer product matrix.
     */
    fun outerProduct(other: Vector3): Matrix3x3 {
        return Matrix3x3(
            x * other.x, x * other.y, x * other.z,
            y * other.x, y * other.y, y * other.z,
            z * other.x, z * other.y, z * other.z
        )
    }
}

