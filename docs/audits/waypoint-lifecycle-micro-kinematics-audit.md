# Waypoint lifecycle and MicroPython kinematics audit — pass 204

Scope: MicroPython differential/mecanum arithmetic and angle reduction; named waypoint
loading/caching; the FTC mecanum request wrapper; delegated path-task terminal cleanup.
This pass reviews new runtime areas after the Studio identity passes. Changes stay local.

## Confirmed issues and fixes

The old MicroPython angle wrapper shifted by pi before reduction, losing tiny valid
angles and changing remainders for large finite inputs. It now preserves normalized
values and signed endpoints, then applies `math.fmod` before the final range adjustment.
The result is modulo the represented floating-point period, not mathematical pi at
arbitrary precision. Nonfinite angles are rejected, including arc-to-chord inputs.
The required primitive is documented in the [MicroPython math API](https://docs.micropython.org/en/latest/library/math.html).

Differential wheel sums/differences and mecanum averages could overflow before a
representable result was calculated. Scaling now occurs before summation only when
needed. Tiny differential half-tracks could become zero or round by an entire subnormal
unit; the wheel transform uses a different operation order when halving lost information.
Mecanum geometry no longer underflows merely from halving both dimensions separately,
and its angular transform no longer forms an overflowing/underflowing `1/(4*k)` factor.
Inverse mecanum kinematics reuse the rotational and lateral combinations across wheels.
These changes preserve specified finite cases, not every possible correctly rounded
sum or physical meaning for extreme geometry. True unrepresentable outputs remain
subject to the drivetrain/robot's existing nonfinite-output checks and neutralization.

The waypoint loader previously rescanned disk/classpath sources and logged on every
lookup after a missing or malformed file. Such outcomes now share the same explicit
cache/reload lifecycle as successful loads. A synchronized cold load publishes one
immutable snapshot; hot reads use that snapshot. Parsing requires explicit finite
coordinates/headings, nonblank unique IDs/names, and an optional boolean `locked`.
Missing numeric fields no longer silently become zero; duplicate names no longer select
the last motion target. One invalid record rejects the entire file. Operators must call
`clearCache()` after installing or correcting a file, and should preload before motion.

The FTC wrapper now skips waypoint resolution while inactive, reuses a converted pose
for the same immutable record, and reuses missing-waypoint error text. A reload refreshes
the cached pose; an active path still keeps its target until the request is released.
The previous zero-GC guarantee for the entire follower was inaccurate and was replaced
with the actual boundary: cached lookup/idle overhead is allocation-free, while moving
path following and immutable Redux actions can allocate.

Failed path tasks previously stayed active because `isCompleted` intentionally returns
false for a failure. The wrapper now observes terminal status before and after lifecycle
calls, attempts neutral drive intent, releases runtime metadata, and retains the request
latch until release. Initialization exceptions latch before dispatch and preserve the
original exception through cleanup. Clock reversal and overflowing elapsed intervals
fail and finish in the same frame. Cancellation clears ownership even when stopping throws.
The core path wrapper also releases its delegated task metadata and finalizes its own
status/deadline after a delegated cleanup exception instead of skipping its own end hook.

## Validation

Before changes, all five new FTC lifecycle tests failed against the original code.
The initial seven-method MicroPython regression file produced eight assertion failures
across six failing methods; the ordinary physical-basis/mixed-motion case passed. A
further test reproduced odd-subnormal half-track rounding after the initial correction.

The final focused Kotlin run passed 27 tests, including the existing zero-GC regression
suite, waypoint cache/schema/concurrency checks, FTC request cleanup and overflow tests,
and delegated cleanup exception handling. Two measured 10,000-lookup windows per cache
state reported zero allocated bytes after warming the same measurement loop; FTC held
terminal/missing waypoint windows also reported zero bytes. An earlier measurement
reported 480 startup bytes; the test now warms the exact measured loop and counter API,
without weakening the zero-byte assertion. These are host JVM allocation checks, not
physical robot latency, memory-pressure, or hardware stopping measurements.

All 130 MicroPython host tests passed under CPython. Decimal arithmetic independently
checks selected extreme averages and angle remainders; physical basis vectors check wheel
order/signs. Target MicroPython builds may use a different floating-point precision.
No Pico/XRP firmware execution or hardware timing measurement was performed.

Full ARESLib validation passed 2,248 tests with no failures, errors or skips, plus API
checks and isolated candidate publication. Exact library tree:
`886d799fea29f93368b6f21e95b516ec61a79793`; candidate:
`17.0.11-rc.886d799fea29`. Local source commit: `b6fb3761`.

All consumers used the exact candidate and absolute local repository, in dependency order:

| Validation scope | Tests | Passed | Skipped |
| --- | ---: | ---: | ---: |
| ARESLib JVM modules | 2,248 | 2,248 | 0 |
| FTC TeamCode + simulator | 156 | 156 | 0 |
| FRC | 305 | 305 | 0 |
| FTC starter TeamCode + simulator | 14 | 14 | 0 |
| FRC starter | 34 | 34 | 0 |
| Studio shared + gateway + app | 2,039 | 2,033 | 6 |
| MicroPython host suite | 130 | 130 | 0 |
| Repository tooling | 101 | 101 | 0 |

This totals 5,021 successful test executions and six opt-in/environment skips, with no
failures/errors. Focused runs are not added again to these full-suite totals. Studio skips
are three generated-project integrations, native file chooser, performance baseline and
physical dashboard validation. Generated-project checks and debug packaging also passed
for both FTC products; generated-project checks passed for both FRC products.

Studio's first preflight identified stale packaging workflow version/hash copies. Those
pins were synchronized with the canonical manifests and rebuilt archive hashes before the
successful final Studio run. An initial sandboxed tooling run could not access its temporary
fixtures; the authorized run passed all 101 tests. Repository policy and shared guidance
passed; local links were checked in 365 current documents, with 38 historical records
explicitly skipped. The candidate artifact manifest records hashes of 410 publication files.
No visible-window, physical hardware, deployment or remote release validation was exercised.

Four local template archives were rebuilt with new version identities and hashes.
Entry-level comparison showed only the standalone dependency manifest changed in FTC,
FRC and Lightbot; XRP additionally contains the corrected `lib/ares_micro/kinematics.py`.
No entries were added or removed. All archives remain local; nothing was released.

Evidence: `ARESLib-Kotlin/build/audit-pass204-verified-evidence/`, including baseline logs,
copied focused/library XML, full logs, candidate identity and archive content/hash records.

## Coverage boundaries and next work

MicroPython kinematics, the waypoint cache/loader and the FTC request wrapper were read
in full and checked with the focused tests above. Filesystem/classpath search order was
reviewed statically; actual Android deployment/storage and target-device timing remain
external checks. Existing Redux feedback freshness, explicit arming, hardware leases and
output enforcement were preserved; issuing neutral intent is not proof of physical stop.

`PathfindToPoseTask` retains partial status: its terminal delegate/registry behavior was
fixed and tested, while direct initialization/generation exceptions, reuse and broader
planning ownership still need dedicated audit. In particular, investigate neutralization
when path generation throws before the delegate exists. The larger task engine and
telemetry manager were inspected only to establish these call/cleanup boundaries.

The Studio routine preview compiler, its five current tests and its analyzer were read
for the next distinct scope. The caller correctly seeds a neutral preview from its first
drive target. Planner-failure omission, invalid draft handling, bounded duration and
asynchronous diagnostic publication still need dedicated behavioral tests; no source
change or broader correctness claim is made for those files here. The global
file-coverage goal remains incomplete. Generated/vendor/declarative/documentation and
hardware-only entries retain their individual accounting and limitations in the ledger.

The final ledger contains 2,904 tracked files: 1,086 reviewed, 156 partial and 1,662
pending, with no stale fingerprints or orphaned records. This is file-review accounting,
not line/branch coverage or a claim that every tracked file is executable.
