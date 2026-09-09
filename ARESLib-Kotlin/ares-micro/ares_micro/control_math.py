"""Finite mechanism profile and feedforward math, independent of hardware IO."""
import math

EMPTY = {}


def number(value):
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError("XRP controller values must be finite numbers")
    return value


def sign(value):
    return 1.0 if value > 0 else -1.0 if value < 0 else 0.0


class TrapezoidProfile:
    """Reusable reference with a zero terminal velocity; numeric results still allocate."""
    def __init__(self):
        self.reset()

    def reset(self, position=None, velocity=0.0):
        self.position = 0.0 if position is None else number(position)
        self.velocity = number(velocity)
        self.acceleration = 0.0
        self.initialized = position is not None

    def advance(self, dt, measurement, goal, maximum_velocity, maximum_acceleration, period=None):
        dt, goal, measurement = number(dt), number(goal), number(measurement)
        max_v, max_a = number(maximum_velocity), number(maximum_acceleration)
        if dt <= 0 or max_v <= 0 or max_a <= 0:
            raise ValueError("Profile period and constraints must be positive")
        x = number(self.position) if self.initialized else measurement
        v = number(self.velocity) if self.initialized else 0.0
        old_velocity = v
        if period is not None:
            period = number(period)
            if period <= 0:
                raise ValueError("Profile period must be positive")
            delta = number(goal - x)
            goal = number(x + ((delta + period / 2.0) % period - period / 2.0))
        remaining_time = dt
        if abs(v) > max_v:
            brake_time = number((abs(v) - max_v) / max_a)
            brake_step = min(remaining_time, brake_time)
            next_v = sign(v) * max_v if remaining_time >= brake_time else v - sign(v) * max_a * brake_step
            x = number(x + (v * 0.5 + next_v * 0.5) * brake_step)
            v = number(next_v)
            if remaining_time <= brake_time:
                self._commit(x, v, old_velocity, dt)
                return
            remaining_time -= brake_time

        direction = -1.0 if x > goal else 1.0
        flipped = False
        while True:
            current_x, current_v, goal_x = x * direction, v * direction, goal * direction
            cutoff_begin = number(current_v / max_a)
            distance = number(0.5 * current_v * cutoff_begin + (goal_x - current_x))
            if distance < 0:
                raise ValueError("Profile cannot represent the requested boundary state")
            acceleration_time = number(max_v / max_a)
            cruise_distance = number(distance - acceleration_time * max_v)
            if cruise_distance < 0:
                acceleration_time = number(math.sqrt(distance / max_a))
                cruise_distance = 0.0
            if cutoff_begin <= acceleration_time:
                break
            if flipped:
                raise ValueError("Profile reversal is numerically infeasible")
            direction = -direction
            flipped = True

        end_accel = number(acceleration_time - cutoff_begin)
        end_cruise = number(end_accel + cruise_distance / max_v)
        end_decel = number(end_cruise + acceleration_time)
        if remaining_time < end_accel:
            velocity = current_v + remaining_time * max_a
            position = current_x + (current_v + remaining_time * max_a * 0.5) * remaining_time
        elif remaining_time < end_cruise:
            velocity = max_v
            position = current_x + (current_v + end_accel * max_a * 0.5) * end_accel + max_v * (remaining_time - end_accel)
        elif remaining_time <= end_decel:
            time_left = end_decel - remaining_time
            velocity = time_left * max_a
            position = goal_x - time_left * max_a * 0.5 * time_left
        else:
            self._commit(goal, 0.0, old_velocity, dt)
            return
        self._commit(position * direction, velocity * direction, old_velocity, dt)

    def _commit(self, position, velocity, old_velocity, dt):
        position, velocity = number(position), number(velocity)
        acceleration = number((velocity - old_velocity) / dt)
        self.position = position
        self.velocity = velocity
        self.acceleration = acceleration
        self.initialized = True


def feedforward(loop, state, linkage, default_velocity=0.0, default_acceleration=0.0):
    """Combine declared feedforward in motor volts, before output clamping."""
    model = loop.get("feedforward", EMPTY)
    kind = model.get("kind", "NONE")
    if kind == "NONE":
        return 0.0
    if kind not in ("SIMPLE_MOTOR", "ELEVATOR", "ARM", "TWO_DOF_ARM"):
        raise ValueError("Unsupported XRP feedforward model: " + str(kind))
    ks = number(model.get("kS", 0.0))
    kv = number(model.get("kV", 0.0))
    ka = number(model.get("kA", 0.0))
    kg = number(model.get("kG", 0.0))
    velocity_id, acceleration_id = model.get("velocityFieldId"), model.get("accelerationFieldId")
    velocity = number(default_velocity if velocity_id is None else state[velocity_id])
    acceleration = number(default_acceleration if acceleration_id is None else state[acceleration_id])
    gravity = 0.0
    if kind == "ELEVATOR":
        gravity = kg
    elif kind == "ARM":
        angle = number(state[model["gravityAngleFieldId"]])
        gravity = number(kg * math.cos(angle))
    elif kind == "TWO_DOF_ARM":
        joint = model.get("linkageJoint")
        if not linkage.get("enabled") or type(joint) is not int or joint not in (1, 2):
            raise ValueError("Two-joint feedforward requires an enabled linkage and joint 1 or 2")
        theta1 = number(state[linkage["joint1AngleFieldId"]])
        theta2 = number(state[linkage["joint2AngleFieldId"]])
        l1, l2 = number(linkage.get("link1LengthMeters", 0.35)), number(linkage.get("link2LengthMeters", 0.25))
        m1, m2 = number(linkage.get("link1MassKg", 0.5)), number(linkage.get("link2MassKg", 0.3))
        c1, c2 = number(linkage.get("link1CenterOfMassMeters", l1/2)), number(linkage.get("link2CenterOfMassMeters", l2/2))
        if l1 <= 0 or l2 <= 0 or m1 <= 0 or m2 <= 0 or c1 < 0 or c2 < 0 or c1 > l1 or c2 > l2:
            raise ValueError("Linkage lengths, masses and centers of mass must be physically valid")
        torque = number(m2 * c2 * 9.80665 * math.cos(number(theta1 + theta2)))
        if joint == 1:
            torque = number(torque + (m1*c1 + m2*l1) * 9.80665 * math.cos(theta1))
        gravity = number(kg * torque)
    return number(ks * sign(velocity) + kv * velocity + ka * acceleration + gravity)
