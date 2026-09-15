# Spline heading and lighting timeline audit — pass 206

Scope: shared spline heading interpolation across distance/angle scales; Studio lighting
preview semantics, event ordering, ownership and per-frame replay cost. This follows
pass 205's path initialization and routine compiler work. All changes remain local.

## Confirmed issues and fixes

Generated paths shorter than 1e-6 meters retained their start heading instead of reaching
the requested end heading. Parsed short paths deferred interior rotation until their
endpoint. Both now use normalized positive traveled distance without an absolute cutoff.
Exactly zero generated travel retains the final orientation at every sample, with zero
translational speed. This is a stationary follower goal, not a timed turn profile.

The previous `(1 - cos(pi*t))/2` ease lost small representable heading changes through
subtractive cancellation. Its equivalent squared-sine form retains those increments.
Tests compare scale-equivalent paths, independent midpoint/endpoint expectations and
a leading Taylor term whose omitted relative error is below the assertion tolerance.

Parsed angles could remain enormous until after interpolation, losing the interpolated
change when added to the start angle. Point-towards offsets could similarly erase the
actual target bearing. Endpoints, rotation targets and offsets are now wrapped before
arithmetic. Reduction follows the existing represented Double radian period, not an
arbitrary-precision interpretation of huge degree values. Point-towards offsets are
converted once per zone, and start/delta/distance terms once per rotation-anchor interval.

Lighting preview previously selected the nearest named color and then moved one entry.
Generated robot code uses ordered comparisons, which differs at OFF and between colors.
Preview now follows the generated ordering, including wraparound, duplicate color aliases
and the descriptor's field bounds. Allowed named set values come from shared capability
metadata; numeric strings, nonfinite strings, out-of-range names and the unsupported
indicator RAINBOW value no longer become invented generated targets. Prism choices use
the same named-option/bound contract. The action argument key remains the canonical
`value`; it was checked against the producer and did not need changing.

Duplicate subsystem identities no longer render multiple targets for one runtime ID.
Hand-authored descriptors do not imply generated action semantics. Missing, malformed
and invalid descriptors remain omitted, consistent with the existing optional preview
loader; omission is not a claim that the project is valid or that custom hardware is off.

Lighting playback previously sorted and replayed the full action history, rebuilt action
keys, target maps and color lists, and sorted outputs on each frame. Descriptor loading
now resolves bindings, ordering and supported choices once. Timeline compilation performs
a stable timestamp sort and replays each action once, grouping simultaneous actions.
Snapshots are stored only when the visible result changes. The screen remembers the
compiled timeline until its model or action snapshot changes. Sampling binary-searches
that history and returns an existing snapshot, including when scrubbing backward.

Finite nonnegative event timestamps are required. Events apply at their exact timestamp,
without the old 1e-9-second lookahead. Positive and negative zero share source ordering;
nonfinite playback times return descriptor defaults. Compilation owns the resulting
numeric snapshots, so later input list/map mutation cannot change playback history.

## Validation

The corrected pre-fix spline baseline failed all seven new methods. One initial fixture
had its point-towards offset in the Y-coordinate position; that setup error was corrected
and the unchanged production code was rerun before recording the authoritative baseline.
The final focused run passed 108 tests, including the seven new methods, existing spline
sampling/curvature/constraint cases, pathfinding/alliance lifecycle and zero-GC regressions.
Tested positive lengths include 5e-7, 1e-12 and 1e-240 meters; these are numeric software
boundaries, not claims about physically meaningful robot geometry.

The lighting baseline failed nine of ten methods against the old implementation. Final
focused Studio validation passed 56 tests, including 17 new lighting methods and the
existing descriptor-placement, routine preview and editor checks. Cases cover generated
set/cycle parity, bounds, malformed inputs, timestamp ordering, snapshot ownership,
repeated/reverse seeks, duplicate IDs, hand-authored ownership and unchanged frames.
A guarded action-list fixture rejects any input traversal after compilation; 10,000
queries in one interval return the exact same snapshot object.

Compiled `RoutineLightingTimeline.at(double)` bytecode contains no object construction
or boxing. Its work is primitive comparisons/indexing plus a cached list lookup. This
establishes the sampler's allocation boundary, not that Compose rendering, timeline
compilation or the whole desktop app is allocation-free. Compilation stores a snapshot
per changed timestamp and uses memory proportional to changed frames times displayed
lights. Representative large-project memory and rendered-frame timing remain separate
measurements; the robot loop itself does not execute this desktop preview code.

Full library tests, API checks and isolated candidate publication passed. Source commit:
`2e1a2938af5c97dad4d063166d28b2375aee4512`; exact library tree:
`c6d7742429b063b7fb9bf3b51cb87d8b839fe60d`; candidate:
`17.0.13-rc.c6d7742429b0`. Consumers used the absolute local validation repository after
publication. The candidate manifest hashes 410 publication files.

| Validation scope | Tests | Passed | Skipped |
| --- | ---: | ---: | ---: |
| ARESLib JVM modules | 2,265 | 2,265 | 0 |
| FTC TeamCode + simulator | 156 | 156 | 0 |
| FRC | 305 | 305 | 0 |
| FTC starter TeamCode + simulator | 14 | 14 | 0 |
| FRC starter | 34 | 34 | 0 |
| Studio shared + gateway + app | 2,075 | 2,069 | 6 |
| MicroPython host suite | 130 | 130 | 0 |
| Repository tooling | 101 | 101 | 0 |

The validated suites contain 5,074 passing results and six explicit Studio skips,
with no failures/errors. Gradle reused unchanged or cached task outputs where applicable;
these are suite results, not a claim that every test was re-executed. Focused runs are not
counted again. The skips are three generated-project integrations, native file chooser,
performance baseline and physical dashboard validation. Both FTC products also passed
project verification and debug packaging; both FRC products passed project verification.

The four bundled starter archives were rebuilt under new identities. Exact entry-level
comparison found only `release/ares-versions.properties` changed in each archive, with
no added or removed entries. Workflow version/URL/hash copies and the canonical hash
manifest match the new bytes. Local pins are ARES/FTC/FRC starters 17.0.13, Studio 7.0.13,
and XRP/Lightbot 3.0.12. No public release or remote workflow was performed.

Repository policy and shared guidance passed, including source-tree identity, release
pins and archive hashes. Local Markdown links were verified in 367 current documents;
38 historical records were explicitly skipped.

Evidence: `ARESLib-Kotlin/build/audit-pass206-verified-evidence/`, including baseline and
final logs, copied XML, the sampler bytecode, suite summaries, candidate manifest and
archive content/hash comparisons. No rendered Studio window, robot hardware, target
MicroPython firmware, deployment or physical loop/stop measurement was exercised.

## Coverage boundaries and next work

The lighting model/timeline and both focused test files were read in full. Shared
capability/codegen set and cycle branches were inspected to establish parity, not to
claim a fresh review of every generator branch. Lighting remains a deterministic display
of generated target intent; custom actions, hardware faults/output clamps and physical
lighting are not simulated. The existing single selected Prism output remains unchanged.
The larger screen retains partial review: only model/timeline wiring and its immediate
consumer were checked. Descriptor reload invalidation and full screen behavior remain
separate work.

`SplineMotionProfiler` retains partial status. This pass closes heading scale, easing,
angle normalization and generated stationary-goal questions. Previously recorded limits
remain: singular-adjacent interval certification, chord/arc-length error, long handles,
point-towards refinement and representative construction timing. Parsed coincident
rotation anchors retain their established precedence; they do not define a temporal
rotation sequence. Follow-up should cover those remaining approximation boundaries and
other unreviewed robot/controller files rather than repeatedly rerunning closed scopes.

The final ledger accounts for 2,912 tracked files: 1,099 reviewed, 158 partial and 1,655
pending. There are no stale fingerprints or orphaned records.

File-review accounting is distinct from line/branch coverage and test totals. The full
monorepo audit goal remains active, with generated/vendor/declarative/documentation and
hardware-only files retaining explicit individual accounting and limitations.
