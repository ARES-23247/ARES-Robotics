package com.areslib.math.coordinate

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.pathing.Path
import com.areslib.pathing.PathPoint
import com.areslib.pathing.PathEvent
import com.areslib.state.Alliance
import java.util.LinkedList
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class CoordinateBoundaryTest {
    @Test fun `large path tangent receives the same transform as its pose heading`() {
        for (origin in FieldOrigin.entries) for (symmetry in FieldSymmetry.entries) {
            for (angle in listOf(Double.MAX_VALUE, -Double.MAX_VALUE, 1e20, -1e20)) {
                val point = PathPoint(Pose2d(1.0, 2.0, Rotation2d(angle)), 3.0, 0.0, 0.4, angle)
                val mirrored = AllianceMirroring.mirror(Path(listOf(point)), Alliance.RED, symmetry, 10.0, 8.0, origin).points.single()
                assertEquals(mirrored.pose.heading.radians, mirrored.tangentRadians, 1e-14)
            }
        }
    }

    private class CountedPoints : LinkedList<PathPoint>() {
        var indexedReads = 0
        override fun get(index: Int): PathPoint { indexedReads++; return super.get(index) }
    }

    @Test fun `linked path mirroring uses linear traversal`() {
        val points = CountedPoints()
        repeat(1000) { points.add(PathPoint(Pose2d(it.toDouble(), 0.0), 1.0, it.toDouble())) }
        val output = AllianceMirroring.mirror(Path(points), Alliance.RED, FieldSymmetry.ROTATIONAL)
        println("[Coordinate audit] 1000 linked points required ${points.indexedReads} indexed reads")
        assertEquals(0, points.indexedReads)
        assertEquals(1000, output.points.size)
        assertEquals(-999.0, output.points.last().pose.x)
    }

    @Test fun `red transforms reject raw nonfinite headings instead of normalizing them to valid poses`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val pose = Pose2d(1.0, 2.0, Rotation2d(bad))
            for (origin in FieldOrigin.entries) for (symmetry in FieldSymmetry.entries) {
                assertFailsWith<IllegalArgumentException> { AllianceMirroring.mirror(pose, Alliance.RED, symmetry, fieldOrigin = origin) }
            }
            assertFailsWith<IllegalArgumentException> { CoordinateTransformers.flipPoseRotational(pose, Alliance.RED) }
            assertFailsWith<IllegalArgumentException> { CoordinateTransformers.flipCornerPoseRotational(pose, Alliance.RED) }
            assertFailsWith<IllegalArgumentException> { CoordinateTransformers.mirrorPoseReflectionalX(pose, Alliance.RED) }
        }
    }

    @Test fun `used field extents and unrepresentable output positions are rejected`() {
        val pose = Pose2d(1.0, 2.0)
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { CoordinateTransformers.centerToCorner(pose, bad, 2.0) }
            assertFailsWith<IllegalArgumentException> { CoordinateTransformers.cornerToCenter(pose, 2.0, bad) }
            assertFailsWith<IllegalArgumentException> { CoordinateTransformers.mirrorPoseReflectionalX(pose, Alliance.RED, bad) }
            assertFailsWith<IllegalArgumentException> { AllianceMirroring.mirror(Translation2d(), Alliance.RED, FieldSymmetry.ROTATIONAL, 2.0, bad, FieldOrigin.CORNER) }
        }
        assertFailsWith<IllegalArgumentException> {
            CoordinateTransformers.centerToCorner(Pose2d(Double.MAX_VALUE, 0.0), Double.MAX_VALUE, 1.0)
        }
    }

    @Test fun `red path rejects invalid scalar fields and unordered distance`() {
        val good = PathPoint(Pose2d(), 1.0)
        for (point in listOf(good.copy(velocityMps = Double.NaN), good.copy(curvature = Double.POSITIVE_INFINITY),
            good.copy(tangentRadians = Double.NaN), good.copy(distanceMeters = -1.0), good.copy(distanceMeters = Double.NaN))) {
            assertFailsWith<IllegalArgumentException> { AllianceMirroring.mirror(Path(listOf(point)), Alliance.RED, FieldSymmetry.MIRRORED) }
        }
        assertFailsWith<IllegalArgumentException> {
            AllianceMirroring.mirror(Path(listOf(good.copy(distanceMeters = 2.0), good)), Alliance.RED, FieldSymmetry.ROTATIONAL)
        }
    }

    @Test fun `all symmetry matrices transform headings and points coherently and are involutions`() {
        val random = java.util.Random(6801)
        for (origin in FieldOrigin.entries) for (symmetry in FieldSymmetry.entries) repeat(200) {
            val x = random.nextDouble() * 6 - 3; val y = random.nextDouble() * 4 - 2
            val h = random.nextDouble() * 6 - 3
            val pose = Pose2d(x, y, Rotation2d(h))
            val output = AllianceMirroring.mirror(pose, Alliance.RED, symmetry, 10.0, 8.0, origin)
            val signX = if (symmetry == FieldSymmetry.ROTATIONAL || origin == FieldOrigin.CORNER) -1 else 1
            val signY = if (symmetry == FieldSymmetry.ROTATIONAL || origin == FieldOrigin.CENTER) -1 else 1
            assertEquals(signX * cos(h), cos(output.heading.radians), 1e-14)
            assertEquals(signY * sin(h), sin(output.heading.radians), 1e-14)
            val restored = AllianceMirroring.mirror(output, Alliance.RED, symmetry, 10.0, 8.0, origin)
            assertEquals(x, restored.x, 2e-15); assertEquals(y, restored.y, 2e-15)
            assertEquals(cos(h), cos(restored.heading.radians), 1e-14)
        }
    }

    @Test fun `blue passthrough preserves ownership and does not claim validation`() {
        val pose = Pose2d(Double.NaN, 0.0, Rotation2d(Double.NaN)); val point = Translation2d(Double.NaN, 0.0)
        val path = Path(listOf(PathPoint(pose, Double.NaN)))
        assertSame(pose, AllianceMirroring.mirror(pose, Alliance.BLUE, FieldSymmetry.MIRRORED))
        assertSame(point, AllianceMirroring.mirror(point, Alliance.BLUE, FieldSymmetry.MIRRORED))
        assertSame(path, AllianceMirroring.mirror(path, Alliance.BLUE, FieldSymmetry.MIRRORED))
        assertSame(pose, CoordinateTransformers.flipPoseRotational(pose, Alliance.BLUE))
        assertSame(pose, CoordinateTransformers.flipCornerPoseRotational(pose, Alliance.BLUE))
        assertSame(pose, CoordinateTransformers.mirrorPoseReflectionalX(pose, Alliance.BLUE))
        assertSame(point, CoordinateTransformers.flipTranslationRotational(point, Alliance.BLUE))
        assertSame(point, CoordinateTransformers.mirrorTranslationReflectionalX(point, Alliance.BLUE))
    }

    @Test fun `red translation matrices match pose positions in every frame`() {
        for (origin in FieldOrigin.entries) for (symmetry in FieldSymmetry.entries) {
            val point = Translation2d(-0.5, 1.5)
            val output = AllianceMirroring.mirror(point, Alliance.RED, symmetry, 10.0, 8.0, origin)
            val pose = AllianceMirroring.mirror(Pose2d(point.x, point.y), Alliance.RED, symmetry, 10.0, 8.0, origin)
            assertEquals(pose.x, output.x); assertEquals(pose.y, output.y)
        }
        for (bad in listOf(Translation2d(Double.NaN, 0.0), Translation2d(0.0, Double.POSITIVE_INFINITY))) {
            assertFailsWith<IllegalArgumentException> { CoordinateTransformers.flipTranslationRotational(bad, Alliance.RED) }
        }
    }

    @Test fun `path copies points independently and retains scalar distances and event identity`() {
        val point = PathPoint(Pose2d(1.0, 2.0, Rotation2d(0.3)), -2.0, 3.0, 0.4, -0.5)
        val events = mutableListOf(PathEvent("marker", 2.5))
        val path = Path(listOf(point, point.copy(distanceMeters = 3.0)), events)
        val result = AllianceMirroring.mirror(path, Alliance.RED, FieldSymmetry.MIRRORED, fieldOrigin = FieldOrigin.CORNER)
        assertSame(events, result.events)
        assertNotSame(point, result.points.first()); assertNotSame(point.pose, result.points.first().pose)
        assertEquals(-2.0, result.points.first().velocityMps)
        assertEquals(3.0, result.points.first().distanceMeters)
        assertEquals(-0.4, result.points.first().curvature)
        result.points.first().velocityMps = 99.0
        assertEquals(-2.0, point.velocityMps)
        assertEquals(0, AllianceMirroring.mirror(Path(emptyList()), Alliance.RED, FieldSymmetry.MIRRORED).points.size)
        assertFailsWith<IllegalArgumentException> {
            AllianceMirroring.mirror(Path(emptyList()), Alliance.RED, FieldSymmetry.MIRRORED, Double.NaN, 8.0, FieldOrigin.CORNER)
        }
    }

    @Test fun `origin translation preserves raw headings and validates only used extents`() {
        val pose = Pose2d(0.25, -0.5, Rotation2d(Double.MAX_VALUE))
        val output = CoordinateTransformers.centerToCorner(pose)
        val back = CoordinateTransformers.cornerToCenter(output)
        assertEquals(pose.x, back.x, 1e-15); assertEquals(pose.y, back.y, 1e-15)
        assertEquals(Double.MAX_VALUE, output.heading.rawRadians)
        assertEquals(Double.MAX_VALUE, back.heading.rawRadians)
        AllianceMirroring.mirror(pose, Alliance.RED, FieldSymmetry.MIRRORED, Double.NaN, Double.NaN, FieldOrigin.CENTER)
        AllianceMirroring.mirror(pose, Alliance.RED, FieldSymmetry.MIRRORED, 10.0, Double.NaN, FieldOrigin.CORNER)
        assertFailsWith<IllegalArgumentException> { CoordinateTransformers.centerToCorner(Pose2d(Double.NaN, 0.0)) }
        assertFailsWith<IllegalArgumentException> { CoordinateTransformers.cornerToCenter(Pose2d(0.0, Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { CoordinateTransformers.centerToCorner(Pose2d(0.0, 0.0, Rotation2d(Double.NaN))) }
    }
}
