# AprilTag import frames and editor transactions

Pass 72, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Findings and changes

- Studio passed WPILib positions/orientations unchanged into centered FTC/XRP fields and
  exported them unchanged. Field-aware conversion now shifts origins, rotates orientation,
  and swaps rectangular extents when axes rotate by a quarter turn. Raw codec APIs remain.
- ARES imports discarded source frame metadata before applying tags in the destination.
  Conversion now uses both source and destination frames. The defined alignment is between
  their blue-wall frames; this is not an inferred physical calibration.
- FRC fmap preview shifted tags by the current target dimensions, then replacement could
  adopt different source dimensions. A centered tag from a 20x10 source now becomes `(10,5)`
  consistently, instead of retaining the default field's `(8.2705,4.1055)` offset. Each known
  source axis wins independently; omitted axes use target dimensions. Replacement adopts
  known dimensions, while merge retains the current dimensions and existing tag IDs.
- Edits and undo/redo left import previews applicable to stale dimensions/conflicts. They now
  invalidate previews. Applying an unchanged import consumes its preview without adding an
  undo entry or revision. Preview input can no longer silently change the loaded project/league.
- JSON import tried up to three parsers and replaced useful recognized-format errors with
  unrelated fallback errors. One strict text parse now selects one unambiguous format. Export
  and import transformations remain outside periodic robot loops.

## Coordinate reference and derivation

[WPILib's AprilTagFieldLayout contract](https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/apriltag/AprilTagFieldLayout.html)
defines a blue-wall corner origin, inward +X, left +Y and tag-center poses. ARES centered-field
driver-wall directions are established by `RobotFieldConfig.mapJoystickIntents`, independent
of rendering-axis settings. The [FTC coordinate definition](https://ftc-docs.firstinspires.org/en/latest/game_specific_resources/field_coordinate_system/field-coordinate-system.html)
also distinguishes a centered reference from the alliance-wall perspective.

For WPILib extents L/W, a centered destination receives `R(theta) * (p - (L/2,W/2))`.
Destination blue wall WEST/SOUTH/EAST/NORTH selects theta 0/+90/180/-90 degrees. Orientation
is `Rz(theta) * Rtag`; right-handed roll/pitch remain and yaw rotates. Inverse export reverses
the rotation and adds the corner offset. Same-frame ARES imports skip offset arithmetic to
avoid losing tiny positions next to huge dimensions. Finite but unrepresentable results reject.

Limelight field-aware decoding retains the existing centered canonical-axis contract; source
dimensions correct its FRC offset. It does not infer camera mounting or rotate arbitrary
custom fmap coordinate bases. Those require known calibration before use.

## Evidence checkpoint

All ten initial Studio regression methods failed against pass71 candidate
`17.0.3-rc.7e9a7fb40abd`; log and copied XML are `ARESLib-Kotlin/build/audit-pass72-before.*`.
They cover independent corner references, export orientation/extents, source dimensions,
partial dimensions, merge, ARES source frames, stale previews, no-op apply and diagnostics.

Focused core checks passed 31 methods, including 200 independent matrix comparisons across
all four wall rotations, metadata preservation, same-frame tiny coordinates, source-axis
fallback, malformed/ambiguous formats, overflow rejection and very large finite yaw.
The API snapshot adds only `decodeForField` and `encodeWpilibForField`.

Studio focused validation passed 35 methods: all 11 new import contracts and 24 nearby
editor/catalog methods, plus the production file-size check. It used explicit sibling-source
substitution for development. Singular pitch reference cases subsequently added to the core
matrix fixture also passed in final validation.

Source `d99cefb3` binds library tree `ed23a4cf86cc59b47200d8aaf6a0e5b7a6220ebe` to local
candidate `17.0.3-rc.ed23a4cf86cc`. Full library tests/API/Kover/local publication passed in
2m 49s: 1,707 methods, no failures/errors/skips. Serial consumers passed FTC 111, FRC 134,
FTC starter 14 and FRC starter 34 methods, with generated-project checks and FTC assembly.
Studio reran shared/gateway/app tests: 1,790 passed and six existing opt-in skips; 56 dashboard
methods, one performance baseline, coverage, version alignment and file-size gates passed.
Studio took 8m 6s; no physical performance conclusion follows from this host build duration.

Core Kover covers codec 238/239 lines, 211/218 branches and 28/28 methods; frame helper
46/46 lines, 62/68 branches and 6/6 methods. Counts supplement the independent reference
tests; they do not prove every possible input or custom frame convention. Logs, copied XML,
hash manifests and summary are under `ARESLib-Kotlin/build/audit-pass72-verified-evidence`.
Policy passed with 233 current Markdown documents, 38 historical exclusions, canonical source
identity and archive checks. Appropriate unchanged-input Gradle cache/up-to-date reuse is
explicit in `audit-pass72-{library,ftc,frc,ftc-starter,frc-starter,studio}.log` in the build folder.

## Scope limits

The frame helper, transfer helper and new regression fixtures are reviewed in full. Codec
parser/math review builds on pass70/71; editor view-model changes cover preview/apply,
edit/undo invalidation and their document mapping, not every remaining view-model lifecycle.
Physical field/camera calibration, custom frame registration and real-time robot measurements
are not established by these host tests. Rendering and persistence retain their independent
review scopes. No push, merge, remote publication or device action is authorized by this batch.
