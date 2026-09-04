"""Deterministic XRP tabletop collision constraints from the canonical field document."""

import json
import hashlib
import math


class FieldCollisionConstraint:
    """Constrains a rectangular robot against field bounds and blocking obstacles."""

    def __init__(self, field_path, robot_length, robot_width):
        self.field_path = field_path
        self.robot_length = float(robot_length)
        self.robot_width = float(robot_width)
        self.width = 0.0
        self.height = 0.0
        self.obstacles = []
        self._modified_ns = None
        self._load(required=True)

    def constrain(self, previous, proposed):
        self._load(required=False)
        if not self.collides(proposed):
            return proposed
        if self.collides(previous):
            return previous
        low = 0.0
        high = 1.0
        for _ in range(16):
            fraction = (low + high) / 2.0
            candidate = _interpolate_pose(previous, proposed, fraction)
            if self.collides(candidate):
                high = fraction
            else:
                low = fraction
        return _interpolate_pose(previous, proposed, low)

    def collides(self, pose):
        robot = _rectangle_corners(
            pose[0], pose[1], self.robot_length, self.robot_width, pose[2]
        )
        half_width = self.width / 2.0
        half_height = self.height / 2.0
        if any(abs(x) > half_width or abs(y) > half_height for x, y in robot):
            return True
        for obstacle in self.obstacles:
            shape = str(obstacle.get("shape", "rectangle")).lower()
            if shape == "circle":
                if _rectangle_intersects_circle(robot, obstacle):
                    return True
            elif shape == "polygon":
                points = [
                    (float(point["x"]), float(point["y"]))
                    for point in obstacle.get("points", [])
                ]
                if len(points) >= 3 and _polygons_intersect(robot, points):
                    return True
            else:
                obstacle_rectangle = _rectangle_corners(
                    float(obstacle.get("x", 0.0)),
                    float(obstacle.get("y", 0.0)),
                    float(obstacle.get("width", 0.0)),
                    float(obstacle.get("height", 0.0)),
                    math.radians(float(obstacle.get("rotation", 0.0))),
                )
                if _convex_polygons_intersect(robot, obstacle_rectangle):
                    return True
        return False

    def _load(self, required):
        try:
            modified_ns = self.field_path.stat().st_mtime_ns
            if modified_ns == self._modified_ns:
                return
            payload = self.field_path.read_text(encoding="utf-8")
            self.apply_payload(payload)
            self._modified_ns = modified_ns
        except Exception:
            if required or self.width <= 0.0 or self.height <= 0.0:
                raise

    def apply_payload(self, payload):
        """Atomically installs a canonical field payload and returns its receipt counts."""
        document = json.loads(payload)
        width = float(document["widthMeters"])
        height = float(document["heightMeters"])
        if width <= 0.0 or height <= 0.0:
            raise ValueError("field dimensions must be positive")
        obstacles = [
            obstacle
            for obstacle in document.get("obstacles", [])
            if obstacle.get("isBlocking", True)
            and str(obstacle.get("obstacleType", "blocking")).lower() != "ramp"
        ]
        self.width = width
        self.height = height
        self.obstacles = obstacles
        return {
            "configId": str(document.get("id", "")),
            "revision": document.get("revision"),
            "sha256": hashlib.sha256(payload.encode("utf-8")).hexdigest(),
            "obstacleCount": len(obstacles),
            "elementCount": len(document.get("elements", [])),
            "aprilTagCount": len(document.get("apriltags", [])),
        }


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
    center = (float(circle.get("x", 0.0)), float(circle.get("y", 0.0)))
    radius = float(circle.get("width", 0.0))
    return _point_in_polygon(center, rectangle) or any(
        _distance_to_segment(center, rectangle[index], rectangle[(index + 1) % 4]) < radius
        for index in range(4)
    )


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
