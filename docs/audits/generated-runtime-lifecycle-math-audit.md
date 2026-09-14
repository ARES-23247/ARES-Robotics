# Generated runtime isolation, freshness, and math audit

Pass 277 covers 33 related source, test, contract, and documentation files. It follows generated
controls and subsystem lifecycle code into the shared superstructure runtime and robot consumers.
Broader renderers, autonomous hosts, and schema branches retain partial review status.

Library source commit: `17e0c3fd059b64be6949222ae28d97a0f57abbe2`.
Library tree: `e1e0b12c1f6ae7dd4d9c5545dccd2409dcb28c2e`.
Local candidate: `19.0.0-rc.e1e0b12c1f6a`.
The subsequent Studio adapter fix is recorded in `62cca78d`; it leaves the library and robot
consumer source trees unchanged. All changes remain local.

## Findings and fixes

- Generated drive commands shared mutable state across hosts. Each controls factory now owns its
  drive buffer and matching command emitter. Executed generated-code tests reuse one definition
  for two hosts and cover neutral startup, interleaved commands, and cancellation isolation.
- Runtime cancellation stopped at the first failed release. It now attempts every controller port,
  direct-task release, cleanup dispatch, and routine cancellation, preserving the first exception
  and suppressing later failures. Prepared superstructure tasks are also released when later
  construction or initialization fails; cleanup failure selects the fault posture.
- Generated freshness checks could accept overflowed ages or future timestamps, including with an
  unlimited descriptor lease. They now require ordered timestamps and a nonnegative elapsed value.
  Authored guard age limits, previously ignored, tighten the descriptor lease and cannot extend it.
  The Studio preview binding was migrated to the same API and age-limit semantics.
- A target hash collision could suppress a real update. The runtime now compares preset identity
  and exact buffered target values; the hash remains diagnostic. A regression uses the colliding
  strings `Aa` and `BB`.
- Multiple targets for one subsystem could overwrite earlier fields by initializing against the
  same original snapshot. Factories are preflighted and initialization sees preceding target
  updates in a projected state. Only the documented named-subsystem update actions are accepted,
  and dispatch waits until all targets initialize successfully. The unused final projection is
  omitted.
- State-machine elapsed subtraction could overflow, and clock rewinds could trigger debounce or
  postpone deadlines incorrectly. Ordered elapsed time saturates and rewinds rebase the state,
  request, and debounce windows. Boundary regressions cover extreme timestamps.
- LUT interpolation could produce nonfinite results from finite endpoints when subtraction
  overflowed. Scaled input arithmetic and weighted opposite-sign output interpolation preserve
  finite results. STEP interpolation now returns an exact interior knot's value at that knot and
  holds it until the next one. LINEAR, COSINE, and STEP boundary cases pass.
- Generated subsystem close could skip safe output and IO release after a controller failure,
  repeat cleanup, and permit later work. Close is now idempotent, attempts reset/safe/close with
  aggregated failures, and gates subsequent reads and writes. Tests compile the actual generated
  state, IO contract, and lifecycle against explicit controller and IO doubles.

Repeated metadata allocation fell from **1,280,000 bytes to 0 bytes across 10,000 reads** in the
same focused JVM fixture after caching the stable controls source and active-port count. Fallback
rejection text is also cached; healthy and faulted steady-state allocation checks pass. These are
bounded allocation measurements, not robot loop-duration or hardware jitter measurements.

The public controls factory and health-binding contracts changed, so the canonical ARES version
is 19.0.0 and generator format is 10. All 13 public API snapshots were checked; the deliberate
core API change was reviewed. Moving runtime contract DTOs and LUT implementation into dedicated
files preserves their API/schema and keeps the source-size check passing.

## Verification

| Check | Observed result |
|---|---|
| Full ARESLib | 3,030 tests in 472 suites; no failures, errors, or skips |
| APIs and source size | All 13 API checks and the size check passed |
| FTC / FTC starter | 187 / 17 tests, generated-project verification, and APK assembly passed |
| FRC / FRC starter | 306 / 206 tests and generated-project verification passed |
| Studio | Normal tests stopped at release alignment; all three test-source compilations passed after the adapter fix |

The checkpoint contains **3,746 passing library/robot test results**, with prior test identities
retained. Gradle reused unchanged outputs where applicable. All robot consumers resolved the same
explicit local candidate; Studio compilation used it too. The candidate's 410 artifact hashes and
the preceding candidate's 410 hashes were verified unchanged. Detailed commands, XML, hashes,
allocation output, source identities, and summaries are retained locally under
`ARESLib-Kotlin/build/audit-pass277-verified-evidence/`.

Failing baselines precede the runtime isolation, cancellation, freshness, guard-age, collision,
target composition, elapsed-time, LUT, prepared-cleanup, and generated lifecycle fixes. Intermediate
fixture/setup compilation failures are not counted as product defects. The full checkpoint also
caught two obsolete lifecycle characterization hashes; these were refreshed after reviewing the
changed renderer and successful generated-lifecycle execution tests. The superseded candidate was
never published, and its different source was not reused under the final candidate version.

Consumer compilation caught the missed Studio health-binding override. Its signature and age
semantics are fixed; two additional preview boundary tests compile but have **not executed** because
the normal Studio test gate remains blocked. They are excluded from the passing test total and
those files retain pending runtime validation.

## Remaining scope and limits

Studio tests stop at `verifyReleaseVersionAlignment`, which expects `ARES_VERSION: 19.0.0` in the
distribution workflow. Monorepo policy also stops at the missing bundled FTC starter 19.0.0 archive.
Shared guidance and current-document link checks pass. Archive/workflow/template migration remains
unapproved and unchanged; no gate exclusions or bypasses were used.

This pass provides no physical robot, rendered Studio window, whole-loop timing, or hardware IO
evidence. Lightbot dimensions remain unresolved. FTC/FRC controller wrappers and autonomous hosts,
subsystem IO/homing rendering, broader document validation, and remaining superstructure branches
still require targeted review. Candidate follow-ups include integer LUT targets, standalone health
fallback port generation, sequence/sentinel boundaries, and autonomous cleanup/time arithmetic;
these are hypotheses, not additional confirmed defects.
