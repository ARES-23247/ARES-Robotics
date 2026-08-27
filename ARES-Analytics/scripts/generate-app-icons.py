#!/usr/bin/env python3
"""Build the checked-in desktop icon family from one square PNG master.

The optional ``--extract-rounded-source`` mode is intentionally narrow: it converts the
reviewed ImageGen draft used for the 2026 ARES Robotics Studio rebrand into a transparent
rounded-square master. Normal regeneration should use the checked-in master without that flag.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw


MASTER_SIZE = 1024
WINDOW_SIZE = 256
LINUX_SIZE = 512
ICO_SIZES = (16, 20, 24, 32, 40, 48, 64, 128, 256)


def lanczos_square(image: Image.Image, size: int) -> Image.Image:
    return image.resize((size, size), Image.Resampling.LANCZOS)


def extract_reviewed_draft(image: Image.Image) -> Image.Image:
    """Crop the reviewed rounded-square draft and replace its exterior with alpha."""

    width, height = image.size
    # Crop a few pixels inside the generated rounded square. The draft preview included a
    # checkerboard-like matte outside the icon; staying inside its antialiased edge prevents a
    # light fringe after the high-quality downscale.
    inset = round(min(width, height) * 0.032)
    square = image.crop((inset, inset, width - inset, height - inset))
    square = lanczos_square(square.convert("RGBA"), MASTER_SIZE)

    # Image generation previews can flatten transparency onto a neutral checkerboard. Recover
    # the matte only outside the dark icon: the reviewed mark intentionally contains no light
    # neutral artwork, so this cannot erase the red helmet or cyan circuit accent.
    recovered_pixels: list[tuple[int, int, int, int]] = []
    matte_alpha = Image.new("L", square.size, 255)
    matte_values: list[int] = []
    for red, green, blue, _ in square.get_flattened_data():
        if min(red, green, blue) > 80 and max(red, green, blue) - min(red, green, blue) < 28:
            neutral = round((red + green + blue) / 3)
            recovered_pixels.append((7, 7, 8, 255))
            matte_values.append(max(0, 255 - neutral))
        else:
            recovered_pixels.append((red, green, blue, 255))
            matte_values.append(255)
    square.putdata(recovered_pixels)
    matte_alpha.putdata(matte_values)

    # Supersample the alpha edge so the taskbar icon remains clean on high-DPI displays.
    scale = 4
    mask = Image.new("L", (MASTER_SIZE * scale, MASTER_SIZE * scale), 0)
    draw = ImageDraw.Draw(mask)
    radius = round(MASTER_SIZE * 0.145) * scale
    draw.rounded_rectangle(
        (0, 0, MASTER_SIZE * scale - 1, MASTER_SIZE * scale - 1),
        radius=radius,
        fill=255,
    )
    rounded_alpha = lanczos_square(mask, MASTER_SIZE)
    square.putalpha(ImageChops.multiply(matte_alpha, rounded_alpha))
    return square


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--extract-rounded-source", action="store_true")
    args = parser.parse_args()

    source = Image.open(args.source)
    if args.extract_rounded_source:
        master = extract_reviewed_draft(source)
    else:
        master = lanczos_square(source.convert("RGBA"), MASTER_SIZE)

    output = args.output_dir
    output.mkdir(parents=True, exist_ok=True)

    master_path = output / "ares-studio-master.png"
    master.save(master_path, format="PNG", optimize=True)
    lanczos_square(master, WINDOW_SIZE).save(output / "ares-studio-app.png", format="PNG", optimize=True)
    lanczos_square(master, LINUX_SIZE).save(output / "ares-studio.png", format="PNG", optimize=True)
    master.save(output / "ares-studio.ico", format="ICO", sizes=[(size, size) for size in ICO_SIZES])
    master.save(output / "ares-studio.icns", format="ICNS")

    print(f"Generated ARES Robotics Studio icons from {args.source}")


if __name__ == "__main__":
    main()
