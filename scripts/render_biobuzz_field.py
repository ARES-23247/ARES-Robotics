"""Create a fixed-geometry field background from the supplied BIOBUZZ STEP assembly.

Run extraction with FreeCAD's Python (FreeCAD and Import on PYTHONPATH), then rasterize
with a Python containing Pillow. Intermediate tessellation can be large; it is not a
shipped resource. Example:

  python render_biobuzz_field.py extract field.step triangles.json
  python render_biobuzz_field.py render triangles.json biobuzz.png

No input image is used. Moving cells and balls belong to the runtime renderer.
"""

import argparse
import json
from pathlib import Path
import shutil
import tempfile


def extract(source, destination):
    import FreeCAD
    import Import

    document = FreeCAD.newDocument("BiobuzzProjection")
    try:
        with tempfile.TemporaryDirectory(prefix="biobuzz-step-") as scratch:
            step = Path(scratch) / "field.step"
            shutil.copyfile(source, step)  # The supplied .txt is a STEP file; Import needs the extension.
            Import.insert(str(step), document.Name)
        triangles = []
        for obj in document.Objects:
            if not hasattr(obj, "Shape") or obj.Shape.isNull() or not obj.Name.startswith("Part__Feature"):
                continue
            label = obj.Label.lower()
            if "pollen" in label or "nectar" in label:
                continue
            bounds = obj.Shape.BoundBox
            if abs(bounds.Center.x) < 800 and abs(bounds.Center.z) < 800 and bounds.YMin > 650:
                continue
            vertices, faces = obj.Shape.tessellate(2.0)
            color = "#858585"
            if "tile" in label:
                color = "#303436"
            if "flower" in label:
                color = "#e9bf52"
            if "tape" in label:
                color = "#ce353b" if "red" in label else "#426bd6" if "blue" in label else "#d4ad41"
            for a, b, c in faces:
                va, vb, vc = vertices[a], vertices[b], vertices[c]
                if (vb - va).cross(vc - va).y <= 0:
                    continue
                triangles.append([
                    round((va.y + vb.y + vc.y) / 3, 3), color,
                    [[round(-v.x / 1000, 6), round(-v.z / 1000, 6)] for v in (va, vb, vc)],
                ])
        triangles.sort(key=lambda row: row[0])
        destination.write_text(json.dumps(triangles), encoding="utf-8")
        print(f"Projected {len(triangles)} triangles")
    finally:
        FreeCAD.closeDocument(document.Name)


def render(source, destination):
    from PIL import Image, ImageDraw

    size = 1440
    image = Image.new("RGB", (size, size), "#25292d")
    draw = ImageDraw.Draw(image)
    for height, color, vertices in json.loads(source.read_text(encoding="utf-8")):
        points = [((0.5 - y / 3.6576) * size, (0.5 - x / 3.6576) * size) for y, x in vertices]
        if all(x < 0 or x > size or y < 0 or y > size for x, y in points):
            continue
        if height < 20 and color == "#858585":
            color = "#303438"
        draw.polygon(points, fill=color)
    for index in range(7):
        coordinate = index * size / 6
        draw.line([(coordinate, 0), (coordinate, size)], fill="#52565a", width=2)
        draw.line([(0, coordinate), (size, coordinate)], fill="#52565a", width=2)
    image.save(destination)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("extract", "render"))
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    arguments = parser.parse_args()
    if arguments.source.resolve() == arguments.destination.resolve():
        parser.error("Source and destination must differ")
    {"extract": extract, "render": render}[arguments.operation](arguments.source, arguments.destination)
