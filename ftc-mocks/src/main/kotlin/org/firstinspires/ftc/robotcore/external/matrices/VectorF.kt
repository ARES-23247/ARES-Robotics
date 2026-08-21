package org.firstinspires.ftc.robotcore.external.matrices

/** Minimal desktop-compatible FTC SDK float vector. */
class VectorF(vararg values: Float) {
    private val components = values.copyOf()
    operator fun get(index: Int): Float = components[index]
    fun length(): Int = components.size
}
