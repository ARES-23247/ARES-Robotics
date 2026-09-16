package org.firstinspires.ftc.robotcore.external.matrices

/**
 * Minimal desktop-compatible FTC SDK float vector.
 *
 * Encapsulates an ordered sequence of single-precision floating-point components
 * representing translations, velocities, or coordinates in 2D or 3D robot coordinate frames.
 *
 * This class mirrors the FTC SDK `VectorF` matrix/vector mathematics API to enable
 * vision pipelines, navigation solvers, and kinematics calculators to compile and run
 * identically on both physical robot controllers and desktop simulators.
 *
 * @param values Initial array or vararg elements defining the vector components.
 */
class VectorF(vararg values: Float) {
    /** Defensive copy of the vector elements to guarantee internal immutability. */
    private val components = values.copyOf()

    /**
     * Retrieves the vector component at the specified zero-based [index].
     *
     * @param index The zero-based index of the component to access.
     * @return The floating-point value at [index].
     */
    operator fun get(index: Int): Float = components[index]

    /**
     * Returns the number of elements or spatial dimension of this vector.
     *
     * @return Component count of this vector instance.
     */
    fun length(): Int = components.size
}
