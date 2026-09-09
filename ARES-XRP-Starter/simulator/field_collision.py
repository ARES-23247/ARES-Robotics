"""Deterministic XRP tabletop collision constraints from the canonical field document."""

import json
import hashlib
import math


class FieldCollisionConstraint:
    """Constrains a rectangular robot against field bounds and blocking obstacles."""

    def __init__(self, field_path, robot_length, robot_width):
        self.field_path = field_path
        self.robot_length = _positive(robot_length, "robot length")
        self.robot_width = _positive(robot_width, "robot width")
        self.width = 0.0
        self.height = 0.0
        self.obstacles = []
        self._geometry = ()
        self._file_signature = None
        self._load(required=True)

    def constrain(self, previous, proposed):
        self._load(required=False)
        if self.collides(previous) or not all(math.isfinite(value) for value in proposed):
            return previous
        # Process intervals in time order. Only advance over a proven clear sweep,
        # so a clear endpoint cannot skip a thin obstacle or a rotating corner hit.
        safe = previous
        pending = [(proposed, 0)]
        for _ in range(256):
            if not pending:
                return proposed
            end, depth = pending.pop()
            if not self._sweep_collides(safe, end):
                safe = end
                continue
            if depth == 16:
                return safe
            middle = _interpolate_pose(safe, end, 0.5)
            pending.append((end, depth + 1))
            pending.append((middle, depth + 1))
        # Degenerate near-contact geometry cannot consume an unbounded loop tick.
        return safe

    def _sweep_collides(self, start, end):
        first = _rectangle_corners(start[0], start[1], self.robot_length, self.robot_width, start[2])
        last = _rectangle_corners(end[0], end[1], self.robot_length, self.robot_width, end[2])
        angle = (end[2] - start[2] + math.pi) % (2.0 * math.pi) - math.pi
        # Each corner follows linear translation plus rotation. Linear interpolation
        # error is bounded by max|corner''(t)| / 8 = radius * angle^2 / 8.
        # Expanding the endpoint hull by this square encloses that error disk.
        margin = math.hypot(self.robot_length, self.robot_width) * angle * angle / 16.0
        if margin == 0.0 and not self._geometry:
            # A rectangular field is convex: a pure translation stays inside when
            # both endpoint footprints are inside. No hull construction is needed.
            return self._polygon_outside_field(first) or self._polygon_outside_field(last)
        points = first + last
        if margin:
            points = [(x + dx, y + dy) for x, y in points
                      for dx, dy in ((-margin, -margin), (margin, -margin), (margin, margin), (-margin, margin))]
        return self._polygon_collides(_convex_hull(points))

    def collides(self, pose):
        if not all(math.isfinite(value) for value in pose):
            return True
        robot = _rectangle_corners(
            pose[0], pose[1], self.robot_length, self.robot_width, pose[2]
        )
        return self._polygon_collides(robot)

    def _polygon_outside_field(self, polygon):
        half_width = self.width / 2.0
        half_height = self.height / 2.0
        return any(abs(x) > half_width or abs(y) > half_height for x, y in polygon)

    def _polygon_collides(self, robot):
        if self._polygon_outside_field(robot):
            return True
        for shape, geometry in self._geometry:
            if shape == "circle":
                if _rectangle_intersects_circle(robot, geometry):
                    return True
            elif shape == "polygon":
                if _polygons_intersect(robot, geometry):
                    return True
            else:
                if _convex_polygons_intersect(robot, geometry):
                    return True
        return False

    def _load(self, required):
        try:
            stat = self.field_path.stat()
            signature = (stat.st_mtime_ns, stat.st_ctime_ns, stat.st_size, stat.st_ino)
            if signature == self._file_signature:
                return
            payload = self.field_path.read_text(encoding="utf-8")
            self.apply_payload(payload)
            self._file_signature = signature
        except Exception:
            if required or self.width <= 0.0 or self.height <= 0.0:
                raise

    def apply_payload(self, payload):
        """Atomically installs a canonical field payload and returns its receipt counts."""
        document = json.loads(payload)
        if not isinstance(document, dict):
            raise ValueError("field must be an object")
        width = _positive(document.get("widthMeters"), "field width")
        height = _positive(document.get("heightMeters"), "field height")
        for key in ("obstacles", "elements", "apriltags"):
            if not isinstance(document.get(key, []), list):
                raise ValueError(key + " must be a list")
        if any(not isinstance(obstacle, dict) for obstacle in document.get("obstacles", [])):
            raise ValueError("obstacles must be objects")
        obstacles = [
            obstacle
            for obstacle in document.get("obstacles", [])
            if obstacle.get("isBlocking", True)
            and str(obstacle.get("obstacleType", "blocking")).lower() != "ramp"
        ]
        geometry = tuple(_compile_obstacle(obstacle) for obstacle in obstacles)
        receipt = {
            "configId": str(document.get("id", "")),
            "revision": document.get("revision"),
            "sha256": hashlib.sha256(payload.encode("utf-8")).hexdigest(),
            "obstacleCount": len(obstacles),
            "elementCount": len(document.get("elements", [])),
            "aprilTagCount": len(document.get("apriltags", [])),
        }
        # Commit only after both geometry and receipt have been validated/built.
        self.width = width
        self.height = height
        self.obstacles = obstacles
        self._geometry = geometry
        return receipt


def _finite(value, name):
    try:
        number = float(value)
    except (TypeError, ValueError, OverflowError) as error:
        raise ValueError(name + " must be finite") from error
    if not math.isfinite(number):
        raise ValueError(name + " must be finite")
    return number


def _positive(value, name):
    number = _finite(value, name)
    if number <= 0.0:
        raise ValueError(name + " must be positive")
    return number


def _compile_obstacle(obstacle):
    shape = str(obstacle.get("shape", "rectangle")).lower()
    if shape == "polygon":
        source = obstacle.get("points")
        if not isinstance(source, list) or len(source) < 3 or any(not isinstance(point, dict) for point in source):
            raise ValueError("polygon needs at least three points")
        points = tuple((_finite(point.get("x"), "point x"), _finite(point.get("y"), "point y")) for point in source)
        if points[-1] == points[0]:
            points = points[:-1]
        if len(set(points)) != len(points) or len(points) < 3:
            raise ValueError("polygon needs distinct vertices")
        area = sum(points[i][0] * points[(i + 1) % len(points)][1] - points[(i + 1) % len(points)][0] * points[i][1] for i in range(len(points)))
        if not math.isfinite(area) or area == 0.0:
            raise ValueError("polygon must have finite nonzero area")
        for i in range(len(points)):
            for j in range(i + 2, len(points)):
                if i == 0 and j == len(points) - 1:
                    continue
                if _segments_intersect(points[i], points[(i + 1) % len(points)], points[j], points[(j + 1) % len(points)]):
                    raise ValueError("polygon edges must not cross")
        return shape, points
    x = _finite(obstacle.get("x", 0.0), "obstacle x")
    y = _finite(obstacle.get("y", 0.0), "obstacle y")
    width = _positive(obstacle.get("width"), "obstacle width/radius")
    if shape == "circle":
        # Canonical field circle width is its radius, in meters.
        return shape, ((x, y), width)
    if shape != "rectangle":
        raise ValueError("unsupported obstacle shape: " + shape)
    height = _positive(obstacle.get("height"), "obstacle height")
    rotation = math.radians(_finite(obstacle.get("rotation", 0.0), "obstacle rotation"))
    corners = tuple(_rectangle_corners(x, y, width, height, rotation))
    if not all(math.isfinite(value) for point in corners for value in point):
        raise ValueError("obstacle corners must be finite")
    return shape, corners


def _interpolate_pose(start, end, fraction):
    heading_delta = (end[2] - start[2] + math.pi) % (2.0 * math.pi) - math.pi
    return (
        start[0] + (end[0] - start[0]) * fraction,
        start[1] + (end[1] - start[1]) * fraction,
        start[2] + heading_delta * fraction,
    )


def _rectangle_corners(center_x, center_y, length, width, heading):
    cosine = math.cos(heading)
    sine = math.sin(heading)
    corners = []
    for local_x, local_y in (
        (-length / 2.0, -width / 2.0),
        (length / 2.0, -width / 2.0),
        (length / 2.0, width / 2.0),
        (-length / 2.0, width / 2.0),
    ):
        corners.append((
            center_x + local_x * cosine - local_y * sine,
            center_y + local_x * sine + local_y * cosine,
        ))
    return corners


def _convex_polygons_intersect(first, second):
    for polygon in (first, second):
        for index, point in enumerate(polygon):
            following = polygon[(index + 1) % len(polygon)]
            axis = (-(following[1] - point[1]), following[0] - point[0])
            first_projection = [_dot(vertex, axis) for vertex in first]
            second_projection = [_dot(vertex, axis) for vertex in second]
            if max(first_projection) <= min(second_projection) or max(second_projection) <= min(first_projection):
                return False
    return True


def _polygons_intersect(first, second):
    for index, point in enumerate(first):
        next_point = first[(index + 1) % len(first)]
        for other_index, other_point in enumerate(second):
            other_next = second[(other_index + 1) % len(second)]
            if _segments_intersect(point, next_point, other_point, other_next):
                return True
    return _point_in_polygon(first[0], second) or _point_in_polygon(second[0], first)


def _rectangle_intersects_circle(rectangle, circle):
    center, radius = circle
    return _point_in_polygon(center, rectangle) or any(
        _distance_to_segment(center, rectangle[index], rectangle[(index + 1) % len(rectangle)]) < radius
        for index in range(len(rectangle))
    )


def _convex_hull(points):
    ordered = sorted(set(points))
    lower = []
    for point in ordered:
        while len(lower) >= 2 and _orientation(lower[-2], lower[-1], point) <= 0.0:
            lower.pop()
        lower.append(point)
    upper = []
    for point in reversed(ordered):
        while len(upper) >= 2 and _orientation(upper[-2], upper[-1], point) <= 0.0:
            upper.pop()
        upper.append(point)
    return lower[:-1] + upper[:-1]


def _point_in_polygon(point, polygon):
    inside = False
    previous = polygon[-1]
    for current in polygon:
        crosses = (current[1] > point[1]) != (previous[1] > point[1])
        if crosses:
            edge_x = (previous[0] - current[0]) * (point[1] - current[1]) / (previous[1] - current[1]) + current[0]
            if point[0] < edge_x:
                inside = not inside
        previous = current
    return inside


def _segments_intersect(a, b, c, d):
    first = _orientation(a, b, c)
    second = _orientation(a, b, d)
    third = _orientation(c, d, a)
    fourth = _orientation(c, d, b)
    if ((first > 0.0 and second < 0.0) or (first < 0.0 and second > 0.0)) and (
        (third > 0.0 and fourth < 0.0) or (third < 0.0 and fourth > 0.0)
    ):
        return True
    epsilon = 1e-12
    return (
        (abs(first) <= epsilon and _on_segment(a, b, c))
        or (abs(second) <= epsilon and _on_segment(a, b, d))
        or (abs(third) <= epsilon and _on_segment(c, d, a))
        or (abs(fourth) <= epsilon and _on_segment(c, d, b))
    )


def _orientation(a, b, c):
    return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])


def _on_segment(start, end, point):
    epsilon = 1e-12
    return (
        min(start[0], end[0]) - epsilon <= point[0] <= max(start[0], end[0]) + epsilon
        and min(start[1], end[1]) - epsilon <= point[1] <= max(start[1], end[1]) + epsilon
    )


def _distance_to_segment(point, start, end):
    dx = end[0] - start[0]
    dy = end[1] - start[1]
    length_squared = dx * dx + dy * dy
    if length_squared == 0.0:
        return math.hypot(point[0] - start[0], point[1] - start[1])
    fraction = max(0.0, min(1.0, ((point[0] - start[0]) * dx + (point[1] - start[1]) * dy) / length_squared))
    closest = (start[0] + fraction * dx, start[1] + fraction * dy)
    return math.hypot(point[0] - closest[0], point[1] - closest[1])


def _dot(point, axis):
    return point[0] * axis[0] + point[1] * axis[1]
