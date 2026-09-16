"""Offline reference generator; SciPy is not a robot or CI runtime dependency.

Uses rotation vectors (Rodrigues) to synthesize rigid targets, independently of the
Kotlin tracker's staged Euler rotations. Checked-in results run in ordinary JVM CI.
"""
from pathlib import Path
import math
import numpy as np
from scipy.spatial.transform import Rotation

offsets = np.array([[-.2, -.18, .14], [-.07, -.18, .14], [.07, -.18, .14], [.2, -.18, .14]])
rows = ["case,tag,tx,ty,tz,roll,pitch,yaw,expected_x,expected_y,expected_z"]
for case in range(24):
    phase = case * math.pi / 12
    rotation = Rotation.from_rotvec([.55 * math.sin(phase), .65 * math.cos(phase), phase - math.pi])
    angles = rotation.as_euler("xyz")
    point = np.array([.5 * math.sin(phase), -.3 * math.cos(phase), .8 + case * .2])
    for tag, offset in enumerate(offsets):
        translation = point - rotation.apply(offset)
        values = [case, tag + 30, *translation, *angles, *point]
        rows.append(",".join(str(v) for v in values))
path = Path(__file__).resolve().parents[1] / "ARESLib-Kotlin/core/src/test/resources/vision/cluster-reference.csv"
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text("\n".join(rows) + "\n", encoding="utf-8")
