# Read ARES comfortably

ARES Robotics Studio provides readability options because pit lighting, display quality, visual acuity, and color perception vary between students. These settings change presentation only; they do not change robot behavior or telemetry values.

## Turn on accessibility options

1. Open **Settings** in the lower-left navigation rail.
2. Find **Accessibility & Usability Options**.
3. Enable the options that help you:
   - **Colorblind-Friendly Palette** changes success/failure accents to blue and orange. Status words, icons, and borders remain present so color is never the only signal.
   - **Enhanced High Contrast** brightens secondary text and borders.
   - **Larger Text** increases app text while preserving your operating-system text scale.
   - **Touch Target Optimization** enlarges interactive targets.
4. Select **Save Profile & Settings**.

These preferences belong to the current workspace profile. Recheck them after switching workspaces or using a different computer.

## How to interpret the interface

- Read labels such as **CONNECTED**, **OFFLINE**, **OBSERVED**, **WAITING**, **WARNING**, and **FAILED**. Never infer status from hue alone.
- Filled action buttons use dark text and icons on bright accent backgrounds. Outlined actions use light text on the dark surface.
- Selected tabs and filters retain their text label, border, and shape in addition to their accent fill.
- Charts use a legend and topic names. Do not identify a telemetry series by color alone when discussing it with a teammate.

## If something is still difficult to read

Record the screen name, the exact label, and whether **Colorblind-Friendly Palette**, **Enhanced High Contrast**, or **Larger Text** is enabled. A screenshot is useful, but include the text because screenshots can reproduce display colors inaccurately.

ARES button and status color pairs are checked automatically against WCAG contrast thresholds in the application test suite. Reporting a hard-to-read combination is still valuable: contrast ratios do not catch font size, glare, crowding, or every form of color-vision difference.

## Why the app and website use color differently

ARES Robotics Studio and [aresfirst.org](https://aresfirst.org/) share the same team palette. The
Studio's simplified Spartan/circuit app icon is derived from the detailed team mark but remains
readable at taskbar size.
The website uses ARES red for expressive public calls to action. Analytics uses technical cyan for
ordinary actions and selection so brand identity is not confused with an error, fault, or emergency
stop. See the [ARES product design system](../DESIGN_SYSTEM.md) for the complete mapping.
