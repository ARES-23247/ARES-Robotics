package org.ares.biobuzz

import com.areslib.state.RobotFieldConfig
import org.dyn4j.dynamics.Body
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.world.World
import kotlin.math.*

/**
 * FTC season mechanics attached to the existing simulator world and chassis.
 * The runner owns world stepping and robot motion. This layer owns only balls and containers.
 * Snapshot allocation happens only at the telemetry boundary.
 *
 * Ball masses come from the supplied product specifications. The nominal detent load is eight
 * pollen (0.440 lb), also exceeded by five nectar (0.455 lb). Damping, restitution, and tip duration
 * remain approximations. Cells use bounded storage rather than a full 3-D granular contact solver.
 */
internal class BiobuzzSimulation(
    private val world: World<Body>,
    internal val robot: Body,
    val field: RobotFieldConfig,
    val tuning: Tuning = Tuning(),
    val robotConfiguration: RobotConfiguration = RobotConfiguration(),
) : AutoCloseable {
    data class RobotConfiguration(val capacity: Int = 4, val intakeDistance: Double = 0.27, val captureRadius: Double = 0.14) {
        init {
            require(capacity in 1..4) { "BIOBUZZ robots may hold at most four balls" }
            require(intakeDistance.isFinite() && intakeDistance in 0.0..1.0)
            require(captureRadius.isFinite() && captureRadius > 0 && captureRadius <= 0.5)
        }
    }
    data class Tuning(val tipLoadKg: Double = 8 * BallKind.POLLEN.massKg, val tipDurationSeconds: Double = 0.9) {
        init {
            require(tipLoadKg.isFinite() && tipLoadKg > 0.12)
            require(tipDurationSeconds.isFinite() && tipDurationSeconds > 0.1)
        }
    }

    private class Ball(val id: String, val kind: BallKind) {
        val body = Body().apply {
            addFixture(Geometry.createCircle(kind.diameter / 2)).apply {
                density = kind.massKg / (Math.PI * kind.diameter * kind.diameter / 4)
                friction = 0.45
                restitution = 0.35
            }
            setMass(MassType.NORMAL)
            linearDamping = 1.2
            angularDamping = 1.5
            isBullet = true
        }
        var location = BallLocation.RESERVE
        var container = -1
        var x = 0.0
        var y = 0.0
        var z = kind.diameter / 2
        var vx = 0.0
        var vy = 0.0
        var vz = 0.0
    }

    private class Flower(val x: Double, val y: Double) {
        val balls = ArrayList<Ball>(8) // Bottom to top; never skip a nectar blocker.
    }

    private class Hive(val x: Double, val y: Double, val red: Boolean) {
        val cells = arrayOf(ArrayList<Ball>(32), ArrayList<Ball>(32))
        var upward = if (red) 0 else 1
        var angle = if (red) -BiobuzzField.STABLE_ANGLE else BiobuzzField.STABLE_ANGLE
        var tipStart = angle
        var progress = 0.0
        var tipping = false
        var dumped = false
        var tips = 0
    }

    private val balls = ArrayList<Ball>(56)
    private val inventory = ArrayList<Ball>(4)
    private val flowers = field.fieldWaypoints.filter { it.id.startsWith("biobuzz-flower-") }
        .sortedBy { it.id }.map { Flower(it.x, it.y) }
    private val hives = arrayOf(true, false).map { red ->
        val p = requireNotNull(field.fieldWaypoints.find { it.id == "biobuzz-hive-${if (red) "red" else "blue"}" })
        Hive(p.x, p.y, red)
    }
    val inventoryCount: Int get() = inventory.size
    private var control = BiobuzzControl()
    private var closed = false
    private var shotWasApplied = false
    private var intakeCooldown = 0.0
    private var commandAge = 0.0

    init {
        require(flowers.size == 4) { "BIOBUZZ needs four flower waypoints" }
        require(field.resolvedWidthMeters > 0 && field.resolvedHeightMeters > 0)
        for (element in field.elements) {
            val kind = BallKind.entries.find { it.typeId == element.elementTypeId } ?: continue
            balls += Ball(element.id, kind)
        }
        reset()
    }

    /** Explicit operator lifecycle reset; retains every ball identity and restores the V1 setup. */
    fun reset() {
        check(!closed)
        control = BiobuzzControl()
        commandAge = 0.0
        shotWasApplied = false
        intakeCooldown = 0.0
        inventory.clear()
        for (flower in flowers) flower.balls.clear()
        for (hive in hives) {
            hive.cells[0].clear(); hive.cells[1].clear()
            hive.upward = if (hive.red) 0 else 1
            hive.angle = if (hive.red) -BiobuzzField.STABLE_ANGLE else BiobuzzField.STABLE_ANGLE
            hive.tipping = false
            hive.dumped = false
            hive.tips = 0
            hive.progress = 0.0
        }
        for (ball in balls) {
            world.removeBody(ball.body)
            ball.location = BallLocation.RESERVE
            ball.container = -1
            ball.x = 0.0; ball.y = 0.0; ball.z = ball.kind.diameter / 2
            ball.vx = 0.0; ball.vy = 0.0; ball.vz = 0.0
        }
        for (ball in balls) {
            val el = field.elements.first { it.id == ball.id }
            when {
                el.id.startsWith("flower-") -> {
                    val index = el.id.split('-')[1].toIntOrNull()
                    if (index != null && index in flowers.indices) storeFlower(ball, index) else floor(ball, el.x, el.y)
                }
                el.id.startsWith("hive-") -> {
                    val index = if (el.id.startsWith("hive-True-")) 0 else 1
                    storeHive(ball, index, hives[index].upward)
                }
                el.id.startsWith("reserve-") -> { ball.x = el.x; ball.y = el.y }
                el.id.startsWith("preload-True-") && el.id.substringAfterLast('-').toIntOrNull() in 0 until robotConfiguration.capacity -> hold(ball)
                else -> floor(ball, el.x, el.y)
            }
        }
    }

    /** Call each operator heartbeat. The receiver neutralizes after 150 ms without a fresh command. */
    fun command(value: BiobuzzControl) {
        if (closed) return
        val valid = value.speed.isFinite() && value.elevation.isFinite()
        control = if (valid) value.copy(speed = value.speed.coerceIn(1.0, 9.0),
            elevation = value.elevation.coerceIn(0.0, Math.PI / 2)) else BiobuzzControl()
        commandAge = 0.0
    }

    /** Fixed 10 ms physics step. Invalid or large deltas never inject motion or skip collisions. */
    fun step(dt: Double = 0.01) {
        require(dt.isFinite() && dt > 0.0 && dt <= 0.02)
        if (closed) return
        commandAge += dt
        if (commandAge > 0.15) { control = BiobuzzControl() }
        for (ball in balls) when (ball.location) {
            BallLocation.FLOOR -> { ball.x = ball.body.transform.translationX; ball.y = ball.body.transform.translationY }
            BallLocation.AIR -> fly(ball, dt)
            else -> Unit
        }
        intakeCooldown = max(0.0, intakeCooldown - dt)
        if (control.enabled && control.intake && inventory.size < robotConfiguration.capacity && intakeCooldown == 0.0) collect()
        val shoot = control.enabled && control.shoot
        if (shoot && !shotWasApplied && inventory.isNotEmpty()) launch()
        shotWasApplied = shoot
        for (i in hives.indices) updateHive(i, dt)
    }

    private fun collect() {
        val a = robot.transform.rotationAngle
        val x = robot.transform.translationX + cos(a) * robotConfiguration.intakeDistance
        val y = robot.transform.translationY + sin(a) * robotConfiguration.intakeDistance
        for (f in flowers.indices) {
            val flower = flowers[f]
            if (flower.balls.isNotEmpty() && hypot(x - flower.x, y - flower.y) < robotConfiguration.captureRadius + 0.02) {
                val bottom = flower.balls[0]
                // Nectar is physically larger than the retrieval aperture. No upper ball can pass it.
                if (bottom.kind != BallKind.POLLEN) continue
                flower.balls.removeAt(0)
                hold(bottom)
                intakeCooldown = 0.18
                return
            }
        }
        for (ball in balls) if (ball.location == BallLocation.FLOOR && hypot(ball.x - x, ball.y - y) < robotConfiguration.captureRadius) {
            hold(ball)
            intakeCooldown = 0.18
            return
        }
    }

    private fun hold(ball: Ball) {
        world.removeBody(ball.body)
        ball.location = BallLocation.ROBOT
        ball.container = -1
        inventory.add(ball)
    }

    private fun launch() {
        val ball = inventory.removeAt(0)
        val a = robot.transform.rotationAngle
        val speed = control.speed * cos(control.elevation)
        airborne(ball, robot.transform.translationX + cos(a) * 0.28,
            robot.transform.translationY + sin(a) * 0.28, 0.36,
            speed * cos(a) + robot.linearVelocity.x, speed * sin(a) + robot.linearVelocity.y,
            control.speed * sin(control.elevation))
    }

    private fun airborne(ball: Ball, x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double) {
        world.removeBody(ball.body)
        ball.location = BallLocation.AIR
        ball.container = -1
        ball.x = x; ball.y = y; ball.z = z
        ball.vx = vx; ball.vy = vy; ball.vz = vz
    }

    private fun floor(ball: Ball, x: Double, y: Double) {
        ball.location = BallLocation.FLOOR
        ball.container = -1
        ball.x = x; ball.y = y; ball.z = ball.kind.diameter / 2
        ball.body.transform.setTranslation(x, y)
        ball.body.setLinearVelocity(ball.vx, ball.vy)
        ball.body.angularVelocity = 0.0
        if (!world.containsBody(ball.body)) world.addBody(ball.body)
    }

    private fun fly(ball: Ball, dt: Double) {
        val x0 = ball.x; val y0 = ball.y; val z0 = ball.z
        ball.x += ball.vx * dt; ball.y += ball.vy * dt
        ball.z += ball.vz * dt - 4.905 * dt * dt
        ball.vz -= 9.81 * dt
        // Swept height crossing prevents fast shots tunnelling through a receiver between frames.
        if (ball.z < z0) {
            val flowerZ = BiobuzzField.FLOWER_MOUTH_HEIGHT + ball.kind.diameter / 2
            if (z0 >= flowerZ && ball.z <= flowerZ) {
                val t = (z0 - flowerZ) / (z0 - ball.z)
                val x = x0 + (ball.x - x0) * t; val y = y0 + (ball.y - y0) * t
                for (i in flowers.indices) {
                    val f = flowers[i]
                    val clearance = BiobuzzField.FLOWER_MOUTH_RADIUS - ball.kind.diameter / 2
                    if (hypot(x - f.x, y - f.y) <= clearance && flowerHeight(f) + ball.kind.diameter <= BiobuzzField.FLOWER_MOUTH_HEIGHT) {
                        storeFlower(ball, i)
                        return
                    }
                }
            }
            for (i in hives.indices) {
                val hive = hives[i]
                if (hive.tipping) continue
                val sign = if (hive.upward == 0) -1 else 1
                val cx = hive.x + sign * BiobuzzField.CELL_OFFSET * cos(hive.angle)
                val mouthZ = 1.46 + ball.kind.diameter / 2
                if (z0 >= mouthZ && ball.z <= mouthZ) {
                    val t = (z0 - mouthZ) / (z0 - ball.z)
                    val x = x0 + (ball.x - x0) * t; val y = y0 + (ball.y - y0) * t
                    if (abs(x - cx) <= (BiobuzzField.CELL_DEPTH - ball.kind.diameter) / 2 &&
                        abs(y - hive.y) <= (BiobuzzField.CELL_WIDTH - ball.kind.diameter) / 2 &&
                        hive.cells[hive.upward].size < 32) {
                        storeHive(ball, i, hive.upward)
                        return
                    }
                }
            }
        }
        val radius = ball.kind.diameter / 2
        val hx = field.resolvedWidthMeters / 2 - radius
        val hy = field.resolvedHeightMeters / 2 - radius
        // Balls above the real 12-inch perimeter escape; retain identity out of play until reset.
        if (abs(ball.x) > hx || abs(ball.y) > hy) {
            if (ball.z - radius > 0.3048) { ball.location = BallLocation.OUT_OF_PLAY; return }
            if (abs(ball.x) > hx) { ball.x = ball.x.coerceIn(-hx, hx); ball.vx *= -0.4 }
            if (abs(ball.y) > hy) { ball.y = ball.y.coerceIn(-hy, hy); ball.vy *= -0.4 }
        }
        if (ball.z <= radius) {
            ball.z = radius
            if (abs(ball.vz) > 0.8) { ball.vz = abs(ball.vz) * 0.35; ball.vx *= 0.8; ball.vy *= 0.8 }
            else floor(ball, ball.x, ball.y)
        }
    }

    private fun flowerHeight(flower: Flower): Double {
        var height = 0.0
        for (ball in flower.balls) height += ball.kind.diameter
        return height
    }

    private fun storeFlower(ball: Ball, index: Int) {
        world.removeBody(ball.body)
        ball.location = BallLocation.FLOWER
        ball.container = index
        flowers[index].balls.add(ball)
    }

    private fun storeHive(ball: Ball, index: Int, cell: Int) {
        world.removeBody(ball.body)
        ball.location = BallLocation.HIVE
        ball.container = index * 2 + cell
        hives[index].cells[cell].add(ball)
    }

    private fun updateHive(index: Int, dt: Double) {
        val hive = hives[index]
        if (!hive.tipping) {
            var mass = 0.0
            for (ball in hive.cells[hive.upward]) mass += ball.kind.massKg
            // A detent releases under accumulated load, not every hit or every rendered frame.
            if (mass + 1e-9 < tuning.tipLoadKg) return
            hive.tipping = true; hive.progress = 0.0; hive.tipStart = hive.angle; hive.dumped = false
        }
        hive.progress = min(1.0, hive.progress + dt / tuning.tipDurationSeconds)
        val t = hive.progress
        val eased = t * t * (3.0 - 2.0 * t)
        hive.angle = hive.tipStart * (1.0 - 2.0 * eased)
        if (t >= 0.5 && !hive.dumped) {
            hive.dumped = true
            val cell = hive.cells[hive.upward]
            val sign = if (hive.upward == 0) -1 else 1
            for (j in cell.indices) {
                val ball = cell[j]
                airborne(ball, hive.x + sign * 0.48, hive.y + (j % 4 - 1.5) * 0.10,
                    0.9 + (j / 4) * 0.06, sign * (0.7 + j % 3 * 0.15), (j % 4 - 1.5) * 0.15, -0.3)
            }
            cell.clear()
        }
        if (t >= 1.0) {
            hive.angle = -hive.tipStart
            hive.upward = 1 - hive.upward
            hive.tipping = false
            hive.tips++ // Damper contact: only a completed transition increments the event counter.
        }
    }

    /** Explicit human-player insertion from the five-ball reserve; no synthetic ball creation. */
    fun releaseNectar(red: Boolean): Boolean {
        if (closed || !control.enabled) return false
        val kind = if (red) BallKind.RED_NECTAR else BallKind.BLUE_NECTAR
        val ball = balls.firstOrNull { it.location == BallLocation.RESERVE && it.kind == kind && it.container == -1 } ?: return false
        ball.vx = 0.0; ball.vy = 0.0
        floor(ball, ball.x, ball.y)
        return true
    }

    fun snapshot(): BiobuzzSnapshot {
        val x = robot.transform.translationX; val y = robot.transform.translationY
        val views = balls.map { ball ->
            when (ball.location) {
                BallLocation.FLOWER -> {
                    val f = flowers[ball.container]
                    var z = 0.0
                    for (b in f.balls) { if (b === ball) break; z += b.kind.diameter }
                    BallView(ball.id, ball.kind, f.x, f.y, z + ball.kind.diameter / 2, ball.location, ball.container)
                }
                BallLocation.ROBOT -> BallView(ball.id, ball.kind, x, y, 0.2, ball.location)
                BallLocation.HIVE -> {
                    val hive = hives[ball.container / 2]
                    val sign = if (ball.container % 2 == 0) -1 else 1
                    BallView(ball.id, ball.kind, hive.x + sign * BiobuzzField.CELL_OFFSET * cos(hive.angle), hive.y,
                        BiobuzzField.HIVE_PIVOT_HEIGHT + sign * BiobuzzField.CELL_OFFSET * sin(hive.angle), ball.location, ball.container)
                }
                else -> BallView(ball.id, ball.kind, ball.x, ball.y, ball.z, ball.location)
            }
        }
        return BiobuzzSnapshot(x, y, robot.transform.rotationAngle, control.enabled && !closed,
            inventory.map { it.kind }, views,
            flowers.map { f -> FlowerView(f.x, f.y, f.balls.map { it.kind }) },
            hives.map { h -> HiveView(h.x, h.y, h.red, h.angle, h.upward, h.tipping, h.tips, h.cells.map { cell -> cell.map { it.kind } }) },
            balls.count { it.location == BallLocation.RESERVE && it.kind == BallKind.RED_NECTAR },
            balls.count { it.location == BallLocation.RESERVE && it.kind == BallKind.BLUE_NECTAR })
    }

    override fun close() {
        if (closed) return
        closed = true
        control = BiobuzzControl()
        for (ball in balls) world.removeBody(ball.body)
    }

    /** Paused scenario staging for deterministic physics verification; never creates or copies a ball. */
    internal fun stageBall(id: String, x: Double, y: Double, z: Double, vz: Double = 0.0) {
        check(!closed && !control.enabled)
        require(x.isFinite() && y.isFinite() && z.isFinite() && vz.isFinite() && z >= 0.0)
        val ball = requireNotNull(balls.find { it.id == id })
        inventory.remove(ball)
        for (flower in flowers) flower.balls.remove(ball)
        for (hive in hives) for (cell in hive.cells) cell.remove(ball)
        airborne(ball, x, y, z, 0.0, 0.0, vz)
    }
}
