package com.areslib.math.estimation

import com.areslib.math.geometry.Matrix3x3

/** Covariance acceptance policy used before the vision update and replay commit. */
internal object VisionCovarianceValidation {
    /** Correlation normalization checks each axis independently, including exact zero-variance axes. */
    fun isValid(p: Matrix3x3): Boolean {
        if (!p.m00.isFinite() || !p.m01.isFinite() || !p.m02.isFinite() ||
            !p.m10.isFinite() || !p.m11.isFinite() || !p.m12.isFinite() ||
            !p.m20.isFinite() || !p.m21.isFinite() || !p.m22.isFinite() ||
            p.m00 < 0.0 || p.m11 < 0.0 || p.m22 < 0.0) return false
        if (p.m00 == 0.0 && (p.m01 != 0.0 || p.m10 != 0.0 || p.m02 != 0.0 || p.m20 != 0.0)) return false
        if (p.m11 == 0.0 && (p.m01 != 0.0 || p.m10 != 0.0 || p.m12 != 0.0 || p.m21 != 0.0)) return false
        if (p.m22 == 0.0 && (p.m02 != 0.0 || p.m20 != 0.0 || p.m12 != 0.0 || p.m21 != 0.0)) return false
        val x = kotlin.math.sqrt(p.m00)
        val y = kotlin.math.sqrt(p.m11)
        val z = kotlin.math.sqrt(p.m22)
        val xy = if (x == 0.0 || y == 0.0) 0.0 else (p.m01 / x) / y
        val xz = if (x == 0.0 || z == 0.0) 0.0 else (p.m02 / x) / z
        val yz = if (y == 0.0 || z == 0.0) 0.0 else (p.m12 / y) / z
        if (!xy.isFinite() || !xz.isFinite() || !yz.isFinite() ||
            kotlin.math.abs(xy) > 1.0 + 1e-12 || kotlin.math.abs(xz) > 1.0 + 1e-12 ||
            kotlin.math.abs(yz) > 1.0 + 1e-12) return false
        if (x != 0.0 && y != 0.0 && kotlin.math.abs(xy - (p.m10 / x) / y) > 1e-12) return false
        if (x != 0.0 && z != 0.0 && kotlin.math.abs(xz - (p.m20 / x) / z) > 1e-12) return false
        if (y != 0.0 && z != 0.0 && kotlin.math.abs(yz - (p.m21 / y) / z) > 1e-12) return false
        val determinant = 1.0 + 2.0 * xy * xz * yz - xy * xy - xz * xz - yz * yz
        return determinant.isFinite() && determinant >= -1e-12
    }
}
