package com.areslib.math.geometry

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.MathContext
import java.util.Random
import kotlin.math.*
import kotlin.test.*

class Geometry3dBoundaryTest {
    @Test fun `translation norm preserves large and tiny finite lengths`() {
        for (scale in doubleArrayOf(1e200, 1e-200, Double.MIN_VALUE)) {
            assertEquals(5.0, Translation3d(3 * scale, 4 * scale, 0.0).norm / scale, 1e-14)
        }
        assertEquals(Double.MAX_VALUE, Translation3d(Double.MAX_VALUE, 0.0, 0.0).norm)
    }

    @Test fun `quaternion normalization preserves every finite nonzero scale`() {
        val h = 0.5
        for (scale in doubleArrayOf(Double.MAX_VALUE, 1e200, 1.0, 1e-200, Double.MIN_VALUE)) {
            val original = Quaternion(scale, -scale, scale, -scale)
            val normalized = original.normalize()
            assertEquals(h, normalized.w, 1e-15, "scale=$scale")
            assertEquals(-h, normalized.x, 1e-15)
            assertEquals(h, normalized.y, 1e-15)
            assertEquals(-h, normalized.z, 1e-15)
            assertEquals(scale, original.w)
        }
    }

    @Test fun `Euler extraction reconstructs orientation at both pitch singularities`() {
        for (pitch in doubleArrayOf(PI / 2, -PI / 2)) {
            for (roll in doubleArrayOf(-2.1, -0.3, 0.0, 0.7, 2.5)) {
                for (yaw in doubleArrayOf(-2.7, -0.2, 0.0, 1.3, 2.9)) {
                    val original = Rotation3d(roll, pitch, yaw)
                    assertSameRotation(original, Rotation3d(original.x, original.y, original.z), 2e-14)
                }
            }
        }
    }

    @Test fun `Euler pitch remains accurate close to singularity`() {
        for (distance in doubleArrayOf(1e-4, 1e-6, 1e-8)) {
            for (sign in doubleArrayOf(-1.0, 1.0)) {
                val pitch = sign * (PI / 2 - distance)
                val rotation = Rotation3d(0.3, pitch, -0.8)
                assertEquals(pitch, rotation.y, 2e-15)
            }
        }
    }

    @Test fun `invalid normalization retains the documented identity fallback`() {
        assertEquals(Quaternion(), Quaternion(0.0, 0.0, 0.0, 0.0).normalize())
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(Quaternion(), Quaternion(bad, 1.0, 2.0, 3.0).normalize())
            assertEquals(Quaternion(), Quaternion(1.0, bad, 2.0, 3.0).normalize())
            assertEquals(Quaternion(), Quaternion(1.0, 2.0, bad, 3.0).normalize())
            assertEquals(Quaternion(), Quaternion(1.0, 2.0, 3.0, bad).normalize())
        }
    }

    @Test fun `norm matches independent decimal square root across double exponents`() {
        val random = Random(6901)
        val precision = MathContext(80)
        repeat(1_000) {
            val v = DoubleArray(3) { Math.scalb(random.nextDouble() - 0.5, random.nextInt(2098) - 1074) }
            val sum = v.fold(BigDecimal.ZERO) { total, x -> total + BigDecimal(x).pow(2) }
            val expected = sum.sqrt(precision).toDouble()
            assertEquals(expected, Translation3d(v[0], v[1], v[2]).norm, 3 * Math.ulp(expected))
        }
    }

    @Test fun `normalization matches decimal oracle without changing input or component signs`() {
        val random = Random(6902)
        val precision = MathContext(80)
        repeat(1_000) {
            val v = DoubleArray(4) { Math.scalb(random.nextDouble() - 0.5, random.nextInt(2098) - 1074) }
            val length = v.fold(BigDecimal.ZERO) { total, x -> total + BigDecimal(x).pow(2) }.sqrt(precision)
            val q = Quaternion(v[0], v[1], v[2], v[3]).normalize()
            val actual = doubleArrayOf(q.w, q.x, q.y, q.z)
            for (i in v.indices) {
                val expected = BigDecimal(v[i]).divide(length, precision).toDouble()
                assertEquals(expected, actual[i], max(4 * Math.ulp(expected), Double.MIN_VALUE))
            }
        }
    }

    @Test fun `all unit axis products follow right handed Hamilton order`() {
        val i = Quaternion(0.0, 1.0, 0.0, 0.0)
        val j = Quaternion(0.0, 0.0, 1.0, 0.0)
        val k = Quaternion(0.0, 0.0, 0.0, 1.0)
        assertEquals(k, i * j)
        assertEquals(i, j * k)
        assertEquals(j, k * i)
        assertEquals(Quaternion(0.0, 0.0, 0.0, -1.0), j * i)
        assertEquals(Quaternion(-1.0, 0.0, 0.0, 0.0), i * i)
        assertEquals(Quaternion(), i * i.inverse())
        // inverse is explicitly a conjugate, not division by squared norm for arbitrary quaternions.
        assertEquals(Quaternion(2.0, -3.0, -4.0, -5.0), Quaternion(2.0, 3.0, 4.0, 5.0).inverse())
    }

    @Test fun `rigid transforms match independent Euler matrices and invert in full 3D`() {
        val random = Random(6903)
        repeat(1_000) {
            val a = DoubleArray(3) { random.nextDouble() * 6 - 3 }
            val b = DoubleArray(3) { random.nextDouble() * 6 - 3 }
            val t = DoubleArray(3) { random.nextDouble() * 200 - 100 }
            val v = DoubleArray(3) { random.nextDouble() * 200 - 100 }
            val pose = Pose3d(Translation3d(t[0], t[1], t[2]), Rotation3d(a[0], a[1], a[2]))
            val transform = Transform3d(Translation3d(v[0], v[1], v[2]), Rotation3d(b[0], b[1], b[2]))
            val actual = pose.transformBy(transform)
            val expected = matrixVector(eulerMatrix(a), v)
            assertVector(doubleArrayOf(t[0] + expected[0], t[1] + expected[1], t[2] + expected[2]), actual.translation)
            val product = multiply(eulerMatrix(a), eulerMatrix(b))
            val rotatedBasis = Pose3d(rotation = actual.rotation).transformBy(Transform3d(Translation3d(0.0, 1.0, 0.0)))
            assertVector(doubleArrayOf(product[1], product[4], product[7]), rotatedBasis.translation)
            val recovered = actual.transformBy(transform.inverse())
            assertVector(t, recovered.translation)
            assertSameRotation(pose.rotation, recovered.rotation, 2e-14)
            val relative = actual.relativeTo(pose)
            assertVector(v, relative.translation)
            assertSameRotation(transform.rotation, relative.rotation, 2e-14)
            val twice = transform.inverse().inverse()
            assertVector(v, twice.translation)
            assertSameRotation(transform.rotation, twice.rotation, 2e-14)
        }
    }

    @Test fun `Euler getters and setters preserve rotations and reuse the supplied quaternion`() {
        val random = Random(6904)
        val rotation = Rotation3d()
        val storage = rotation.q
        repeat(2_000) { i ->
            val roll = random.nextDouble() * 6 - 3
            val yaw = random.nextDouble() * 6 - 3
            val pitch = when (i % 3) { 0 -> PI / 2; 1 -> -PI / 2; else -> random.nextDouble() * 2.8 - 1.4 }
            rotation.setEulerAngles(roll, pitch, yaw)
            assertSame(storage, rotation.q)
            assertEquals(Rotation3d(roll, pitch, yaw), rotation)
            assertSameRotation(rotation, Rotation3d(rotation.x, rotation.y, rotation.z), 2e-14)
            assertSameRotation(rotation, Rotation3d(Quaternion(-storage.w, -storage.x, -storage.y, -storage.z)), 0.0)
        }
    }

    @Test fun `pose copies and transform outputs own every nested mutable value`() {
        val pose = Pose3d(Translation3d(1.0, 2.0, 3.0), Rotation3d(0.2, -0.3, 0.7))
        val snapshot = pose.deepCopy()
        val transformed = pose.transformBy(Transform3d())
        val relative = pose.relativeTo(Pose3d())
        val inverse = Transform3d(pose.translation, pose.rotation).inverse()
        assertEquals(pose, snapshot)
        for (t in listOf(snapshot.translation, transformed.translation, relative.translation, inverse.translation)) assertNotSame(pose.translation, t)
        for (r in listOf(snapshot.rotation, transformed.rotation, relative.rotation, inverse.rotation)) {
            assertNotSame(pose.rotation, r); assertNotSame(pose.rotation.q, r.q)
        }
        pose.translation.x = 99.0; pose.rotation.q.w = 0.0
        assertEquals(1.0, snapshot.x); assertEquals(2.0, snapshot.y); assertEquals(3.0, snapshot.z)
        assertEquals(1.0, transformed.x); assertEquals(1.0, relative.translation.x)
        val planar = snapshot.toPose2d()
        assertEquals(1.0, planar.x); assertEquals(2.0, planar.y); assertEquals(0.7, planar.heading.radians, 1e-14)
    }

    @Test fun `vector operators and nonfinite propagation retain their explicit contracts`() {
        val a = Translation3d(1.0, -2.0, 3.0); val b = Translation3d(4.0, 5.0, -6.0)
        assertEquals(Translation3d(5.0, 3.0, -3.0), a + b)
        assertEquals(Translation3d(-3.0, -7.0, 9.0), a - b)
        assertNotSame(a, a + Translation3d())
        assertEquals(0.0, Translation3d().norm)
        assertEquals(Double.POSITIVE_INFINITY, Translation3d(Double.MAX_VALUE, Double.MAX_VALUE, 0.0).norm)
        for (axis in 0..2) {
            val v = DoubleArray(3); v[axis] = Double.NaN
            assertTrue(Translation3d(v[0], v[1], v[2]).norm.isNaN())
            v[axis] = Double.POSITIVE_INFINITY
            assertEquals(Double.POSITIVE_INFINITY, Translation3d(v[0], v[1], v[2]).norm)
            v[(axis + 1) % 3] = Double.NaN
            assertTrue(Translation3d(v[0], v[1], v[2]).norm.isNaN())
            val angles = DoubleArray(3); angles[axis] = Double.NaN
            val r = Rotation3d(angles[0], angles[1], angles[2])
            assertTrue(r.x.isNaN()); assertTrue(r.y.isNaN()); assertTrue(r.z.isNaN())
        }
    }

    private fun assertVector(expected: DoubleArray, actual: Translation3d) {
        assertEquals(expected[0], actual.x, 3e-12)
        assertEquals(expected[1], actual.y, 3e-12)
        assertEquals(expected[2], actual.z, 3e-12)
    }

    // Independently multiply fixed-axis matrices; no quaternion or production transform oracle.
    private fun eulerMatrix(a: DoubleArray): DoubleArray {
        val cr = cos(a[0]); val sr = sin(a[0]); val cp = cos(a[1]); val sp = sin(a[1]); val cy = cos(a[2]); val sy = sin(a[2])
        val rx = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cr, -sr, 0.0, sr, cr)
        val ry = doubleArrayOf(cp, 0.0, sp, 0.0, 1.0, 0.0, -sp, 0.0, cp)
        val rz = doubleArrayOf(cy, -sy, 0.0, sy, cy, 0.0, 0.0, 0.0, 1.0)
        return multiply(multiply(rz, ry), rx)
    }

    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { index ->
        val row = index / 3; val col = index % 3
        (0..2).sumOf { k -> a[row * 3 + k] * b[k * 3 + col] }
    }

    private fun matrixVector(m: DoubleArray, v: DoubleArray): DoubleArray = DoubleArray(3) { row ->
        (0..2).sumOf { k -> m[row * 3 + k] * v[k] }
    }

    private fun assertSameRotation(expected: Rotation3d, actual: Rotation3d, tolerance: Double) {
        val a = expected.q; val b = actual.q
        val sign = if (a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z < 0.0) -1.0 else 1.0
        assertEquals(a.w, sign * b.w, tolerance)
        assertEquals(a.x, sign * b.x, tolerance)
        assertEquals(a.y, sign * b.y, tolerance)
        assertEquals(a.z, sign * b.z, tolerance)
    }
}
