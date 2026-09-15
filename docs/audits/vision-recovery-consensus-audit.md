# Vision recovery consensus audit

Pass 212 reviews the observation requirement, translation/circular means and reset transitions
used by FTC and FRC vision recovery. It follows the distinct physical-quality, freshness and
selection scopes in earlier passes. All changes and candidate artifacts remain local.

## Confirmed issues and corrections

- Both trackers truncated fractional observation requirements. A request of 2.5 observations
  could authorize FTC recovery after two observations. FRC single-tag recovery truncated
  before doubling, requiring four instead of five. Requirements now round upward after any
  single-tag scaling.
- NaN, zero and negative requirements could become one through integer conversion/clamping.
  FRC single-tag multiplication could overflow an Int into a negative requirement, bypassing
  the count gate after the dwell. A shared checked conversion uses Long counts and returns
  zero to disable automatic recovery for invalid or unrepresentable requirements. Positive
  representable requests have no arbitrary Int cap. Initial alignment retains its separate
  physical/stationarity policy.
- Repeated finite poses could overflow the accumulated X/Y sums and send an infinite pose
  to the reseed callback. The shared accumulator updates finite means directly, including
  circular sine/cosine means. Tests reproduce the original failure with two 1e308 poses;
  this is a numerical boundary test, not a claim about realistic field dimensions.
- FTC recovery before initial alignment did not complete the initialization flag, allowing
  a later normal frame to trigger a second initial snap. Successful recovery now completes
  initialization. Successful ordinary initialization also clears evidence collected before
  that pose change. Failed reseed callbacks clear the consensus without completing alignment.

`VisionRecoveryConsensus` owns primitive state and the common inclusive 0.35 m / 20 degree
continuity rule. Invalid samples clear evidence; inconsistent samples start a new window.
Counter exhaustion starts a fresh window rather than wrapping. FRC restarts its 500 ms dwell
when the helper starts a new window. Camera freshness, quality and motion checks remain in
the platform trackers.

The shared helper removes duplicate averaging/counter logic. FTC also avoids constructing
a temporary pose and rotation for each MT2 recovery sample that uses the cached robot heading.
The public API snapshot adds the helper and its checked conversion; existing descriptors
remain unchanged. Consumers are validated against the same locally published candidate.

## Validation

Ten tracker regression methods failed against the original production code: five FTC and
five FRC methods. The expanded FTC baseline includes the initialization evidence-reset case.
The final source adds 26 tests: eleven core, seven FTC and eight FRC methods.

Core checks include 4,126 comparisons with an independent exact BigDecimal conversion
oracle, incremental means versus batch arithmetic, wrapped headings, inclusive boundaries,
extreme finite inputs, invalid inputs, explicit reset and counter exhaustion. Counter
exhaustion uses reflection only in the test to reach an otherwise impractical state.
Tracker tests exercise invalid-to-valid tuning transitions, failed reseed callbacks and
fresh consensus/dwell requirements. Existing tracker, freshness and zero-GC tests also pass.

The retained focused benchmark measured zero allocated bytes over 10,000 warmed helper
samples, at 68.27 ns/sample on this desktop JVM. It measures helper accumulation and count
conversion only. It does not establish full tracker, Store, SDK or physical robot loop timing.

Source commit: `b0e09c1e91780eb41d9aa8965311e58f002905fc`.
Library tree: `c8cd236c4f2004cdffcd478f7e63d8128f58a12c`.
Candidate: `17.0.19-rc.c8cd236c4f20`.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,458 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,020 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |

The suites account for 5,267 passing results, zero failures/errors and six
existing Studio skips. The skips cover three opt-in starter integration scenarios, the native
file chooser, the dashboard performance baseline and physical dashboard validation. FTC and
its starter also passed generated-project verification and APK assembly; FRC and its starter
passed generated-project verification. All 410 candidate publication files were hashed and
reverified after consumer validation. Four rebuilt starter archives differ only in
release version properties. Monorepo policy passed, including source/version/archive identity,
shared guidance and links in 373 current documents; 38 historical records were explicitly skipped.

Evidence directory: `ARESLib-Kotlin/build/audit-pass212-verified-evidence/`, including the
baseline failures, focused/full XML, source/staging whitelists, candidate hashes, archive
comparisons and final summary. Gradle cache/up-to-date results are included as validated
suite results; focused tests are not counted twice in the aggregate.

## Remaining scope and coverage

The shared consensus helper and three new test files are reviewed in full. The concrete
trackers retain partial coverage: FRC estimator-time fallback, historical-pose failure
handling, simulation recovery and the complete disabled/moving policy remain separate scopes.
FTC externally requested reinitialization/manual pose changes need a separate lifecycle
review. Camera/robot close ownership and native NT clock coherence are also unclosed.
Only the recovery requirement section of the broad tuning file is covered here.

The ledger accounts for 2,937 tracked files: 1,152 fully reviewed, 165 partially reviewed
and 1,620 pending, with zero stale or orphaned records. This pass adds the fully reviewed
shared helper, three regression test files and this report. The broad tuning file gains a
partial record for its recovery requirement contract; the trackers retain partial records.

No physical robot, target MicroPython runtime, rendered Studio window, remote workflow,
deployment or public release was exercised. These findings are in ARES code; this pass
does not identify a WPILib defect.
