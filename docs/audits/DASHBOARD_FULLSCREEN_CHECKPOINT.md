# Dashboard fullscreen checkpoint — 2026-09-15

Adds an expand/restore button to the Field 2D and Live Telemetry card headers.
The selected card fills the available dashboard workspace inside Studio. Global
connection, simulator, Stop, navigation, and timeline controls remain accessible.
Restoring returns the card to its previous grid position and scroll offset.

Expansion is local presentation state; it does not save a different dashboard
layout or start a second window. Widget compositions remain in place so live
subscriptions, field settings, selected telemetry channels, signal-tree expansion,
and chart history survive expansion. Layout editing returns to the normal grid.

## Validation

- Rendered Compose regression exercises repeated field/chart expansion at 600 and
  1200 pixel widths from a scrolled dashboard. It checks viewport bounds, incoming
  updates, retained widget instances, exact restored bounds and scroll, no layout
  writes, and cleanup on scene close.
- Focused Studio suite: 16 tests passed, zero failures, errors, or skips. Includes
  the new render test and existing grid bytecode, layout engine, widget catalog,
  dashboard bytecode, and BioBuzz dashboard telemetry checks.
- Native Studio window verified at HWND 2035172, app PID 49912, client 1424 x 861.
  Its normal Verify & launch flow built the BioBuzz 1.0.2 example and started
  simulator PID 50356. Short Space+W slow-drive commands worked while the field
  and telemetry were expanded. The field retained its chosen rotation after
  restoration. Live Odom_Y showed both movements, and its channel selection,
  60-second window, expanded Drive tree, and history survived restoration and
  re-expansion. Stop from the expanded chart returned to Waiting for TeleOp.
- Normal app close completed with Gradle exit 0. Both owned PIDs exited and ports
  5810 and 49625 were released. The previously documented simulator snapshot
  cleanup defect recurred; only this run's verified, inactive 114-file temporary
  snapshot was removed. No process was forcibly terminated.
- Existing CI routes `ARES-Analytics/app/` changes to the Studio `:app:test` scope,
  which discovers the new test. No CI changes or library changes are required.

The first render-test run exposed an observation mistake in the test fixture
(reading Compose state only in SideEffect). Reading the sample in composition
corrected the fixture; the second run passed. This was not a production defect.

## Reproduction and evidence

Worktree: `.codex-validation/biobuzz-readiness`, branch `codex/biobuzz-readiness`,
based on the preserved BioBuzz readiness commit `71d8b21f`.

From `ARES-Analytics`, run `gradlew.bat :app:test` with these test filters:

```text
--tests *DashboardFullscreenRenderTest*
--tests *DashboardWidgetGridBytecodeTest*
--tests *DashboardLayoutEngineTest*
--tests *DashboardWidgetCatalogTest*
--tests *DashboardScreenBytecodeTest*
--tests *BiobuzzDashboardTelemetryTest*
```

Use the same immutable local library candidate as the BioBuzz checkpoint:
`-ParesVersion=19.0.1-rc.6d49e143e3a9` and `-ParesRepository=file:///` followed by
the absolute path to `.codex-validation/audit-pass191/ARESLib-Kotlin/build/release-repository`.
Run compilers serially (`--no-parallel --max-workers=1`). Native validation used
`:app:run -PskipKill -ParesIsolatedDesktopHome=build/biobuzz-readiness/home` and
the same candidate properties, with test-control port 49625.

Local evidence is under `ARES-Analytics/build/dashboard-fullscreen/`: final test
XML in `final-test-results/`, `focused-tests-2.log`, `native-window.txt`,
`native-live-telemetry.png`, `captures/capture-009.png` through `capture-017.png`,
`launch.log`, `shutdown.json`, and `snapshot-cleanup.json`. The evidence manifest
there records SHA-256 hashes. Build outputs are local and untracked.

This is desktop UI and simulator validation. No physical-controller or new
performance claims are made. The earlier BioBuzz readiness evidence remains
bound to its original commit; this UI change does not invalidate that checkpoint.
