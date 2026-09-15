# BIOBUZZ Dashboard integration

Goal: select the separate BIOBUZZ robot project, start Local Sim from the existing Dashboard,
and drive/intake/shoot through its normal controls while Field 2D displays the authoritative runtime.
The Field Editor authors the canonical field; it does not own a simulation session.

## Execution

- [x] Trace the existing simulator, Dashboard controls, telemetry, project templates, and field editor.
- [x] Move BIOBUZZ game mechanics to an FTC season interaction adapter using the existing Dyn4j world and robot body. Preserve ball identity, four-ball FIFO inventory, flower retention, mixed-mass tipping, both cells, and spills.
- [x] Add the separate bundled BIOBUZZ example with Robot Builder-generated intake/shooter, canonical controls, and normal robot/controller lifecycle. Keep Lightbot unchanged.
- [x] Publish complete game snapshots over the existing NT4 connection and render them in the existing Field 2D widget. Remove the practice tab, alternate widget, independent drive controls, and UI-owned physics loop.
- [x] Use the same canonical field document in editor, runtime, and renderer, including edited geometry. Bundle assets and example sources through reproducible packaging.
- [x] Run focused runtime/telemetry/template tests, build the app, and perform rendered Dashboard E2E: drive, intake capacity, flower scoring and nectar retention, mixed hive load, both positions, reset/stop, and Field Editor Push. Verify Lightbot packaging and template regression coverage.

## Boundaries

Use the existing `DesktopSimLauncher.launch(..., SimInteractionModel)` extension before adding
shared-library APIs. Physics steps and robot motion remain owned by the existing simulator.
Controls must consume accepted, post-safety robot outputs. Preserve estimator/truth separation,
control leases, and explicit enable. Game snapshots must be versioned and reject malformed data.
No score widget, 3D renderer, web deployment, or multiplayer in this implementation.

## Verification record

The record below is the original feature validation. For integration with the audited ARESLib
19 runtime, the corrected drive profile, current CI checks, and measured pacing limitations,
see the [2026-09-15 readiness checkpoint](../../docs/audits/BIOBUZZ_READINESS_REPORT.md).

Completed 2026-09-13 on `codex/biobuzz-field` in an isolated worktree.

- **103 tests passed:** Studio 63 (field editor, telemetry/replay, example creation, keyboard controls and safety gates); BIOBUZZ simulator 9; generated TeamCode 31. Project verification and the normal Dashboard verification/build pipeline also passed.
- **Packaged application:** `:app:verifyDistributableProjectLoading` passed with `PACKAGED_PROJECT_VALIDATION_OK`. The native executable is under `app/build/compose/binaries/main/app/ARES Robotics Studio/`. Builds used the existing local ARES candidate `17.0.8-rc.biobuzz.20260913.1`; this change does not modify ARESLib.
- **Robot Builder:** created BIOBUZZ through the application's example onboarding. Robot Studio showed the generated drivetrain, BIOBUZZ Intake, BIOBUZZ Shooter, and TeleOp controls as Ready. The mechanisms are generated from `.ares` subsystem documents, and the live simulator reads the generated FTC IO's applied motor outputs.
- **Dashboard E2E:** launched through Local Sim's normal Verify & launch / Start driving controls. Real keyboard input drove the robot and fired four preloaded pollen into a hive containing three nectar. It tipped, spilled, and changed its active cell. Retrieved spilled nectar, scored it into Flower 3, then retrieved all four pollen beneath it. The flower retained nectar when intake was tried again with spare robot capacity. A subsequent pollen shot scored in the hive's opposite cell.
- **Capacity, stop, reset:** a fifth ball remained on the field with four already held. Red and blue human-player release controls worked. Stop prevented motion and intake/shooting under held input; restart restored the initial inventory, flowers, hives, and reserves.
- **Editor integration:** Push to Sim from the existing Field Editor received an applied receipt for BIOBUZZ: 6 obstacles, 56 game pieces, and 0 AprilTags. BIOBUZZ's reviewed tagless layout is accepted; the normal FTC tag requirement remains for other field documents.
- **Lightbot regression:** its regenerated archive retained SHA-256 `42115d6d497bddc159fa8a8ff2cb3480c382b8decfff9974e8986b652baf19e8`, and official-template tests passed. Lightbot was not driven in this E2E run.
- **Ownership/shutdown:** tests used an isolated Studio home, example project, loopback control port, and the normal single simulator process. Native windows were captured, gracefully closed, and their PIDs verified to exit. No physical robot was used.

Local evidence (relative to `ARES-Analytics/`):

- `build/biobuzz-dashboard-tests.log` and `app/build/test-results/test/` contain the Studio test and packaging results.
- `build/biobuzz-dashboard-final-e2e/capture-012.png` through `capture-019.png` show hive tipping, flower contents, intake, opposite-cell scoring, stop/reset, and capacity.
- `build/biobuzz-editor-final-e2e/capture-005.png` shows the successful field receipt; `capture-006.png` shows the live existing Dashboard; `capture-007.png` shows the Robot Builder example.
- `build/biobuzz-dashboard-e2e/verified-*.json` contains read-only NT4 observations for the corresponding UI assertions. Input was delivered through the rendered native window, without staging balls or injecting simulation state.

The 2D simulator uses ballistic shot height and a nominal 0.440-pound tipping load calibrated to the supplied ball counts. It does not claim measured hardware, pivot friction, or flywheel accuracy. See the [example guide](../../ARES-FTC/biobuzz/docs/BIOBUZZ.md) for controls and model assumptions.
