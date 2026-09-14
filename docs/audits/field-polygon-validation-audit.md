# Canonical field polygon validation audit

Pass 261, 2026-09-14. This follows the malformed authored obstacles found in
[pass 260](ftc-field-resources-audit.md). All work stays on the isolated local audit
branch. No remote publication, release, deployment, or physical robot validation occurred.

## Confirmed issue and repair

`RobotFieldValidator` previously accepted any polygon with three or more finite vertices.
Crossed outlines, nonadjacent touching edges, duplicate vertices, adjacent backtracking,
and collapsed collinear outlines therefore passed canonical field loading. Such outlines
do not define an unambiguous simple obstacle. The original regression run had eight
failures among twelve tests, including the real `ValidatedRobotFieldLoader` boundary.

[RobotFieldPolygonValidation.kt](../../ARESLib-Kotlin/core/src/main/kotlin/com/areslib/state/RobotFieldPolygonValidation.kt)
now checks implicitly closed outlines. It accepts convex and concave polygons in either
winding, including forward collinear intermediate vertices. It rejects crossings,
self-touching, retraced segments, repeated vertices, collapsed outlines, and nonfinite
coordinates, including for nonblocking obstacles. The existing `OBSTACLE_INVALID` code,
message, and element attribution remain unchanged.

Orientation uses a conservative floating-point filter followed by exact arithmetic over
the binary `Double` inputs when the result is uncertain. Overflowing products, tiny
determinants, and nearly collinear inputs take the exact path. The threshold chooses an
arithmetic method; it does not impose a minimum polygon size. Floating-point determinant
signs near zero require special care; the general rationale is described in
[Shewchuk's robust predicates work](https://www.cs.cmu.edu/~quake/robust.html).
This implementation uses JVM `BigDecimal(Double)` and is not a port of that implementation.

The validator copies vertices once to support sequential lists without repeated indexing.
It sorts cached edge bounds and stops scanning when X bounds no longer overlap; disjoint
Y bounds skip orientation calculations. It allocates linear auxiliary storage rather than
materializing all edge pairs. Worst-case time remains quadratic when many bounds overlap.
This is field-loading/editor validation, not periodic robot-loop code. No hardware loop
timing or UI responsiveness benchmark is claimed.

## Evidence

The new library fixture has fifteen test methods, including 432 validity-preserving
transformations across powers-of-two scales, reflections, quarter turns, cyclic starting
vertices, and both windings. Other cases cover overflow, underflow, large offsets,
nonfinite coordinates, and a sequential vertex list without input mutation. The original
eight failing tests pass after the repair. Four field suites, including this
fixture, total 26 passing focused tests; these are included in the full count below.

Each FTC/FRC season and starter fixture now loads a crossed polygon through its real field
contract boundary. Invalid input yields no usable contract; a later valid load clears the
diagnostic. Existing required-tag, league, schema, and pose-conversion cases still pass.

| Validation | Passing tests | Result |
|---|---:|---|
| ARESLib full `test`, `apiCheck`, `publishReleaseValidation` | 2,909 | Passed |
| FTC TeamCode / simulator | 173 / 13 | Passed; descriptor verification and debug APK build passed |
| FRC season | 306 | Passed; descriptor verification passed |
| FTC starter TeamCode / simulator | 16 / 1 | Passed; descriptor verification and debug APK build passed |
| FRC starter | 35 | Passed; descriptor verification passed |
| Studio `:shared:test :gateway:test :app:test` | None claimed | Stopped at release-version alignment before tests |

The total is **3,453 passing tests**, with zero failures, errors, or skips in those completed
suites. All 460 prior library suites have the same test-case identities and counts as the
pass 258 snapshot; the new suite adds fifteen tests. Consumer snapshots preserve all prior
cases and add one per season/starter boundary. The focused runs are not added twice.

Library source commit: `031bf69fa6365fb69fb52fc88092cc4494722e3c`.
Library tree: `f9e7569ea873a08df0477fad1008b6f9ef4575e3`.
Local candidate: `17.0.44-rc.f9e7569ea873`.
Consumer test source commit: `8face9882e2cc12a71c429ca96806e2bd90f5b79`.
All consumers use the same isolated repository and candidate; all 410 candidate files retain
their recorded SHA-256 hashes after robot validation. Canonical source/version identities
were updated under the repository's library-change policy. No final-version artifact was
published and no tracked bundled archive or archive/workflow reference was changed.

Local ignored evidence is under `ARESLib-Kotlin/build/audit-pass261-verified-evidence/`:
`baseline.xml`, `focused-summary.json`, `library-summary.json`, `consumer-summary.json`,
`candidate-manifest.json`, build logs, copied XML, and `summary.json`.

## Remaining boundaries

Studio's normal test command fails at `verifyReleaseVersionAlignment`: the distribution
workflow still carries the previous release reference rather than `ARES_VERSION: 17.0.44`.
The earlier archive/reference migration was rejected by automatic approval review as beyond
the audit's release authorization. Approval remains pending, and its old prepared payload is
stale after passes 259-261. Any approved migration needs a fresh source/archive comparison.
No gate was disabled or bypassed. Studio `:app:compileTestKotlin` passed against the same
candidate. Compilation cannot establish test execution or rendered-window behavior.
Repository policy verified shared guidance and links in 423 current documents (38 historical
records excluded), then stopped because the matching bundled FTC starter archive
`ARES-FTC-Starter-17.0.44.zip` is absent. That is part of the same pending archive migration;
the remaining policy checks did not run and are not claimed as passing.

Static tracing confirms Studio's field editor calls the shared validator and maps canonical
issues to errors. That is not an executed editor interaction. The two new library files
remain reviewed with checkpoint validation pending until the required Studio matrix and
repository release alignment are resolved.

The broader `RobotFieldValidation.kt` entry remains partial: dedicated adversarial coverage
for remaining shape, physics, element-type, and waypoint combinations is still open.
Pass 260's polygon-validation follow-up is fixed in source and verified through the robot
consumers. Simulator treatment of concave polygons as segment walls remains a distinct
collision-model boundary. The Lightbot footprint measurement question from pass 259 also
remains unresolved; this pass changes no measured geometry or hardware configuration.
