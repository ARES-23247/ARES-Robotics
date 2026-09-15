package com.areslib.math

/**
 * Low part of a rounded product using Dekker splitting. Callers scale operands first so
 * multiplying by 2^27+1 and forming partial products cannot overflow. This is not a general
 * replacement for fused multiply-add on arbitrary inputs. No arrays or boxed pairs are created.
 */
internal fun productRoundoff(a: Double, b: Double, product: Double): Double {
    val splitA = 134_217_729.0 * a
    val highA = splitA - (splitA - a)
    val lowA = a - highA
    val splitB = 134_217_729.0 * b
    val highB = splitB - (splitB - b)
    val lowB = b - highB
    return ((highA * highB - product) + highA * lowB + lowA * highB) + lowA * lowB
}
