# Desktop UI regression capture

The desktop regression set covers the two supported working sizes and the narrow Dashboard card
that has historically clipped controls:

- a maximized app on a 1920 x 1080 display;
- the default 1440 x 900 window;
- the Dashboard's narrow Gamepad Monitor after vertical scrolling.

Compile and launch the app first, then run:

```powershell
.\scripts\capture-ui-regression.ps1
```

Captures are written under `build/diagnostics/ui-regression/`. The script requires the shared
Compose desktop tester at the workspace root, verifies the exact visible ARES window before each
standard-size capture, and never treats a full-desktop screenshot as app-window evidence.

Open and inspect all three PNG files. A process ID, a nonzero HWND, correct dimensions, or an image
file with a black/blank client area is not a pass. Confirm that text is legible, controls do not
overlap or escape their cards, the field and chart remain usable, and the Gamepad Monitor header
keeps `Configure controls`, the input source, and `Arm control` on one coherent row.
