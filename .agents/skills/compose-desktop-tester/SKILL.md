---
name: compose-desktop-tester
description: Launch, inspect, interact with, and verify shutdown of ARES Robotics Studio on Windows using rendered-window evidence.
---

# Compose Desktop Visual Tester

Verify the requested behavior in a rendered **ARES Robotics Studio** window. The source product
is `ARES-Analytics/`. Compilation, a live JVM, or service logs alone do not prove usable UI.

## Select the operation

- For launching and capturing a window, use [launch-capture.md](references/launch-capture.md).
- For clicks, text, native choosers, or loopback test control, use [interaction.md](references/interaction.md).
- For closing and checking disposal, use [shutdown.md](references/shutdown.md).
- For no/blank/intermittent windows, locks, orphaned processes, or startup/shutdown fixes, use
  [startup-recovery.md](references/startup-recovery.md). Also read it when changing `Main.kt`,
  `ServiceRegistry`, Compose/coroutines dependencies, or Skiko settings.

## Process and evidence requirements

Normal `:app:run` invokes `killExisting` across matching ARES JVMs, including other checkouts.
Verify ownership before launch. Preserve other tasks' instances; a separate test launch uses
`-PskipKill` and a dedicated `-ParesIsolatedDesktopHome=...`. Keep dependency properties consistent
across compile and run, preserve isolated runtime snapshots and instance locks, and serialize
compilers writing the same module. `clean` must remain independent of `killExisting`.

Capture and inspect the exact owned window and task-relevant UI. Title-based helpers lack PID
filters: with multiple matching windows, use this instance's loopback port or same-process capture.
Visible E2E tests use an isolated home and explicit test workspace. Close the owned app gracefully
and verify its PID exits; if cleanup fails, report it and target only that verified owned PID.

For startup changes, require the settled-state diagnostic and two launch/capture/graceful-close
cycles to catch orphan-process and one-launch-only regressions. An exact-window image is required;
a full-screen screenshot is insufficient. Read named AWT crash logs even if services survive.
Offline NT4 or Google Drive errors do not invalidate an otherwise rendered offline window.

Continue through the requested interaction and cleanup, fixing failures caused by the change.
Report observed results and any limitation preventing rendered-window verification.
