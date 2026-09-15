# FTC field assets and runtime resources audit

Pass 260 starts at `9206a0626d5f2d5d7028b57739ea3477874a2cb3`. It covers the canonical FTC
field/image/AprilTag assets, obsolete runtime snapshots, TeamCode Android resources, package
guidance and the field-loader regression fixture. The library candidate remains
`17.0.43-rc.3973a4d0eeb4`; all 410 candidate hashes were verified unchanged.

## Corrected polygon geometry

Both blocking polygons in `TeamCode/src/main/assets/paths/field.json` had nonadjacent edges 0
and 4 crossing. That makes inside/outside classification ambiguous and supplies invalid polygon
topology to collision consumers. The new asset regression detected the original crossing.
Swapping the first and last vertex in each six-point polygon removes the crossing. Every
coordinate, obstacle property, tag, game-piece placement, waypoint and image setting is preserved;
the field revision advances from 125 to 126. This is an ordering repair, not new field measurements.

The canonical polygon test calls the season collision helper to check every nonadjacent edge
pair. Before the repair, one of four asset tests failed on edges 0/4; afterward all four pass.
The existing AprilTag projection test now calculates the complete `Rz(yaw) * Ry(pitch) * Rx(roll)`
matrix from canonical angles instead of repeating the saved matrix constants. It also checks
millimeter tag size against meter size, all three translation coordinates and the homogeneous
bottom row. The 2e-7 rotation tolerance accommodates the stored matrix's rounding precision.

The simulator's existing concave-polygon fallback constructs segment walls. This pass repairs
the authored outline but does not claim that fallback is a solid polygon or establish full
robot/simulator collision parity for every concave shape.

## Removed redundant files

Removed five tracked source-tree remnants after tracing their consumers:

- `TeamCode/ares_tuning.json`: an unused pre-typed-tuning snapshot. Its heading gains and Pinpoint
  offsets conflicted with the current canonical profile. Generated tuning initializes the robot;
  accepted runtime experiments are saved under `.ares/local/tuning/runtime.arestuning`.
- `TeamCode/ares_run_summary.json`: an old run result with no source/candidate/run provenance.
  Current Gradle simulation/verification tasks direct summaries under `TeamCode/build`.
- `TeamCode/networktables.json` and `simulator/networktables.json`: empty legacy persistence files.
  The current FTC path uses the custom ARES NT4 server, which does not load these files.
- `TeamCode/src/main/assets/field_image_config.json`: unused duplicate image metadata. The
  canonical field document owns rotation, crop and coordinate-system settings.

Scoped ignore entries keep legacy local snapshots out of version control while canonical
`.ares/tuning` files remain visible. Existing machine-local files outside this isolated worktree
were not touched. The workspace guide now names the actual tuning source, and the TeamCode
package guide distinguishes generated subsystem/hardware code from explicit team extensions.

## Resource review and validation

The entire field document was reviewed: dimensions/axes/alliance conventions, both polygon
outlines and physics properties, two tag poses/units, the movable ball type and 24 authored
placements, waypoint and image metadata. It is an authored practice field; these checks do not
attest exact official field measurements, physical placement, friction or restitution.

The 1080×1080 PNG was visually inspected and its chunk CRCs verified. Its full-square crop and
270-degree rotation match the canonical image settings. The fmap's two transforms agree with
canonical positions/orientations and millimeter size, with the numerical tolerance above. No
image pixels or tag coordinates were changed, and no live Studio rendering claim is made.

TeamCode's manifest is an empty application merge contribution; strings contain no overrides.
The webcam calibration resource has no active camera calibration entries: its examples are
comments and cannot be treated as calibration evidence. All three XML documents parse, the
merged Android manifest contains the application, and the debug APK builds. The raw-resource
README and package guide were reviewed with their actual destinations. All four methods of
`FtcFieldContractLoaderTest` were read and run: valid pose/units, non-FTC rejection, duplicate/
invalid tags and undecodable bytes.

Full validation passed: **172 TeamCode tests + 13 simulator tests = 185**, no failures/errors/
skips, generated-project verification and debug APK assembly. Direct ZIP inspection confirmed
the APK contains byte-identical corrected `field.json`, unchanged fmap and PNG, and no retired
image-config file. Consumer builds for unchanged products were not repeated. No hardware,
deployment, push, merge or release was performed.

## Remaining work

The shared `RobotFieldValidation.kt` currently checks polygon count and finite coordinates,
but does not reject intersecting edges. Its ledger scope remains partial, with this concrete
gap recorded for the next shared-library regression/fix and candidate validation cycle. The
new season asset regression protects the checked-in field; it does not replace input validation
for subsequently authored fields.

Pass 258's archive/reference approval and Studio execution remain pending. The archive proposal
must be refreshed against current FTC/starter sources before any approved application. Pass
259's robot-footprint/wheelbase discrepancy still awaits measurements. Those gates were not
bypassed and are not represented as passing here.

Local evidence: `ARESLib-Kotlin/build/audit-pass260-verified-evidence/` contains baseline/final
XML, build log, source/deletion hashes, PNG/XML/APK checks, guidance/link output, summary and
final inventory. The per-file ledger distinguishes completed resource review from open code,
physical and release-alignment work.
