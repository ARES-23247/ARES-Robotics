# Autonomous asset assembly and named-command lifecycle audit

Pass 227 reviews AutoBuilder, DynamicPathLoader, PathPlannerAutoParser and NamedCommands.
Source commit `1bdebc611e52694527140d404e9e49f476463e0e` was validated as local
candidate `17.0.34-rc.e00869558843`, library tree
`e00869558843acf074d1ef7251884e44494b7750`. All changes and candidate artifacts remain local.

## Findings and fixes

- **The auto alliance argument was ignored.** A red auto initialized with blue robot state
  followed blue geometry, while a blue auto initialized with red state was mirrored. Paths
  now apply the requested alliance during construction and disable the downstream transform,
  matching AutoBuilder.buildPath. Tests cover all four requested/live alliance combinations.
  The existing center-origin mirrored convention is preserved: X stays unchanged and Y flips.
- **Deferred named tasks lost preemption behavior.** Pause/resume actions never reached their
  child, and executor suspension left child watchdogs running. The wrapper now forwards the
  lifecycle calls and participates in the existing recursive child-timeout suspension contract.
- **Child failure could become parent success.** Failures during initialization, completion
  checks or end were not consistently propagated. The wrapper uses the existing completion
  deadline gate, propagates failure/cancellation before and after child work, and prevents a
  cancelled child from executing. Parent completion callbacks cannot win over child failure.
- **Exceptional teardown retained parent state.** A throwing child end left the wrapper
  running; throwing metadata release retained the child reference and skipped parent cleanup.
  End now finalizes both statuses and preserves the original exception. Metadata release
  detaches the child before invoking it and always clears the wrapper's runtime registrations.
- **Child failure diagnostics were omitted.** Interrupted child cleanup bypassed the child's
  failure callback. One-shot failure notification now runs before physical cleanup. As with
  existing routine wrappers, callback exceptions are logged so cleanup actions can still be
  returned. They do not convert failed status into success.
- **Malformed auto values were accepted.** String wait durations and pose coordinates could
  be coerced; missing child arrays silently produced empty groups; incomplete/nonfinite poses
  could be returned. Typed readers require numeric finite values and explicit child arrays.
  Empty sequential groups remain supported; a deadline group requires its deadline child.
  Missing/null starting poses remain optional, and omitted rotation retains the zero default.
- **Input and tree work were unbounded.** Loading now stops above 4,194,304 decoded characters.
  Command traversal is limited to 64 nesting levels and 4,096 visited nodes; wait conversion
  rejects values at or above the first unrepresentable Long millisecond boundary. These are
  setup-time resource bounds, not a measured robot loop-time improvement.

The four duplicated group parsers share one pre-sized child-list construction path. Path and
auto filesystem/classpath loading share one implementation; auto search directories are
computed once, and the redundant exists/isFile double check is removed. Canonical containment,
UTF-8 decoding, filesystem priority, classpath fallback and stream closure are retained.
Paths remain freshly loaded because their data is mutable and on-disk edits must be visible.

## Validation

The initial baseline ran 18 cases against the original production files: 13 failed.
A later one-case baseline reproduced the unchanged child failure-callback omission before its
fix. These 19 cases contain 14 reproduced failures; XML and logs are retained separately.
An initial test-compilation mistake used a nonexistent executor.clear method; the fixture was
corrected to cancelAll before the baseline. It is not counted as a production finding.

The focused run passes 58 cases, including 25 new methods across AutoAssemblyAuditTest,
NamedCommandLifecycleAuditTest and DynamicAssetLoadingAuditTest. Existing parser, DSL and
path-loop tests pass unchanged. New coverage includes filesystem UTF-8/edit visibility,
missing-file diagnostics, size bounds, nested groups, input types, geometry, deferred resource
replacement, callbacks, watchdog timing and exception identity.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,731 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,043 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |

The suites account for 5,563 passing results, zero failures/errors and six unchanged Studio
opt-in skips. These cover three starter integration scenarios, native file chooser, dashboard
performance baseline and physical dashboard validation. Gradle results may be executed,
up-to-date or restored from cache; focused results are not counted twice.
FTC/starter generated-project checks and APK assembly passed; FRC/starter generated-project
checks passed. All 410 candidate file hashes were reverified after consumers finished.
Monorepo policy passed, including source/version/archive identity, shared guidance and links
in 389 current documents, with 38 explicitly historical records skipped. Four normalized
starter archive comparisons differ only in release version properties.

## Coverage and limits

The ledger accounts for 2,998 tracked files: 1,285 fully reviewed, 173 partially reviewed
and 1,540 pending, with zero stale or orphaned records. This is file review and appropriate
validation accounting, not universal executable coverage.

This pass fully reviews the four production files, all three new test files and the previously
pending PathPlannerAutoParserTest and RobotSequenceDslTest. Previously reviewed parser and
path-loop test records retain their earlier scope.

The parser supports its documented command subset; this is not a claim of full compatibility
with every PathPlanner release, Choreo, or every editor metadata flag. Starting-pose extraction
does not itself reset odometry. Named-command factories remain responsible for returning
fresh, inactive task instances; resource declarations are checked, but arbitrary custom child
graphs are not introspected. Registry catalog operations are synchronized; task lifecycle
calls still belong to the executor's single loop. The legacy parser test mocks assert group
control flow; the new lifecycle probes call default Task methods to verify real status/deadlines.

Filesystem tests cover local assets and classpath fallback on this host. Symlink race freedom,
Android storage permissions, RoboRIO deployment, rendered Studio behavior, physical actuator
neutralization and on-robot loop timing were not observed. No remote push, merge, release,
deployment or hardware run occurred.
