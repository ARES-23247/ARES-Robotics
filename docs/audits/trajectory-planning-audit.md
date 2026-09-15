# Canonical trajectory planning and spatial generation audit

Pass 54, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Findings and changes

Trajectory validation checked wrapped heading, accepting nonfinite raw headings after the shared
zero fallback. Scalar interpolation could overflow while both endpoints and the correct result
were finite. Huge opposite tangents overflowed their subtraction before wrapping. Negative initial
distance was accepted, and conversion to a scalar-speed Path could emit infinity from a finite
two-component velocity. These boundaries now reject invalid raw data or use stable interpolation.

The supposedly immutable trajectory retained caller-owned state, event and force lists. Clearing
those lists after construction bypassed validation and broke sampling. Owned read-only snapshots
now preserve invariants, including random access for binary search. Exact interior samples reuse
stored states; normal immutable empty-force states avoid extra object copies during construction.
Copy/equality/hash/string/destructuring remain available with the same API signatures. Kotlin
reflection's data-class flag changes because this is now an ordinary class; that is a compatibility
limitation for external reflection-based consumers, despite passing API checks.

Provider registration silently replaced duplicate engine entries, and explicit selection queried
supports twice. Registration now rejects duplicates and selection caches each capability result.
Finite enormous coordinates could request billions of samples. Request validation and the public
spatial generator now enforce a 100,000-sample budget before allocating sampled points. Counts
round up, with at least two subdivisions for short rest-to-rest moves. Direct spatial input checks
also cover raw headings and coordinates, including empty/singleton fast paths. Input snapshots,
cached segment counts and pre-sized point storage remove avoidable traversal and growth work.

The fallback ignored all intermediate waypoint headings. It now assigns headings segment by
segment; coincident translations with differing headings return an explicit unsupported diagnostic.
Tiny nonzero tangent directions and curvatures were discarded by absolute cutoffs. This sent tiny
lateral commands along +X and skipped valid centripetal limits. Zero and nonzero geometry are now
distinguished directly; finite representability is checked instead of hiding failures as zero.

Timing used arbitrary speed/time cutoffs. A test with a 5 mm segment integrated to 50 m after the
minimum-time floor changed time without changing velocity. A normalized trapezoidal calculation
now preserves the relation without overflowing the speed sum or rejecting representable tiny
speeds. Angular acceleration was bounded using different intervals than the emitted angular
velocities; an irregular-segment case emitted 0.01001172227 against a 0.01 limit. Scaling now uses
the emitted values and their actual intervals. Roots-before-division prevent avoidable ratio
overflow. Numeric failures return an explicit generation diagnostic; cumulative time must remain
finite and distinct.

## Evidence

All 11 initial regression methods failed. Four additional timing/magnitude methods and two
small-geometry methods also failed before their corrections. Logs and baseline XML are retained
under `ARESLib-Kotlin/build/audit-pass54-before-evidence`, `audit-pass54-time-before-evidence` and
`audit-pass54-small-geometry-before-evidence`.

The final focused gate passed 55 methods: 28 new and 27 existing, with no failures or skips.
An 80-case seeded sweep checks emitted angular acceleration on very unequal segment lengths.
A 2,000-case finite-exponent interpolation oracle compares against BigDecimal arithmetic and
checks convexity/finiteness. Other grids cover each scalar/force member, timeline/event bounds,
all limit fields, boundary speeds, spacing, engine selection, raw headings, buffer ownership,
copy semantics, linked-list traversal, exact-knot identity, marker distance conversion, force
selection, tiny lateral displacement and weak-curvature centripetal limits. The sample-budget
boundary is checked without actually generating an oversized trajectory.

Focused Kover covers TimedTrajectory 82/82 lines and 11/11 methods; TrajectoryPlanner 43/43 lines
and 3/3 methods; JerkLimitedTrajectoryProvider 138/142 lines and 5/5 methods; and the spatial
parameterizer 133/143 lines and 2/2 methods. Branch coverage is incomplete. API checks passed
without changing signature manifests. Full source review includes both production files, the
five new test files and the three existing trajectory test files read for this pass.

## Limits and remaining work

This remains a piecewise-linear spatial seed with checked discrete finite-difference acceleration
and jerk. It is not a proof of continuous jerk, dynamically feasible forces, smooth corner motion,
or physical tracking. Pose/scalar fields in TimedTrajectory.sample are interpolated independently;
force arrays select the nearest state. Interior sampling allocates; exact samples reuse immutable
objects. There is no real-time or zero-GC claim for generation. Request lists must not mutate
during a call. Budget rejection and explicit unrepresentable-profile diagnostics intentionally
replace uncontrolled allocation or invalid numerical output.

The distance adapter still loses timing/acceleration/module-force information. Path and
HolonomicPathFollower remain separate review obligations, including mutable point ownership,
numerical distance sampling and repeated command-name event handling. No physical robot or HIL
test was run.

## Validation checkpoint

Source `f0c20f37` binds candidate `17.0.3-rc.ae90cbde1043` to library tree
`ae90cbde1043faded8550f78a61fe132e4f4042d`. Full library validation passed in 1m44s:
1,442 tests, no failures/errors/skips, API checks, core Kover and isolated publication.
FTC/FRC/FTC starter/FRC starter gates passed with 109/134/14/34 tests, generated-project
verification and FTC assembly. Studio passed in 3m24s: shared, gateway and app test tasks all reran
(1,779 passed tests and six existing opt-in skips), as did dashboard smoke (56 tests) and performance
baseline (one test). Studio coverage, version-alignment and file-size checks passed. Final XML/hash
manifests and Kover are preserved in `ARESLib-Kotlin/build/audit-pass54-verified-evidence`; individual
build tasks reused cache/up-to-date outputs where shown in their logs.
Policy passed for 215 current documents, with 38 historical exclusions, source identity and archive
integrity checks. The inventory has 2,566 tracked files: 321 reviewed, 74 partial, 2,171 pending,
with no stale or orphaned records. Work remains local and the goal remains active.
