# Robot terminal lifecycle and registry audit

Pass 214 reviews FRC terminal closure, fatal-loop retry diagnostics, and physical lifecycle
iteration in HardwareRegistry. Changes and validation artifacts remain local.

## Confirmed issues and corrections

- FRC updates could run after close. The baseline reached enabled/mode providers, the platform
  sensor hook and the normal output hook before closed telemetry failed. Update now rejects
  calls after closure before any frame work, including latched-fault safety retries. Topology
  publication likewise rejects closed robots, including when its once-only flag was already set.
  Closure remains terminal if cleanup throws, and repeated close does not repeat cleanup.
- Retrying a latched fatal FRC update called safety without protecting the primary failure.
  A later safety exception could replace the original diagnostic. Initial failure and retry
  now share one best-effort safety helper, preserve the original throwable and add each distinct
  secondary throwable at most once by identity. Reusing the primary throwable is safe.
- HardwareRegistry stopped its safety/close pass immediately when a device threw a Throwable
  outside Exception, such as AssertionError or LinkageError. Later devices were skipped; close
  also left ownership collections populated. Both passes now attempt remaining resources before
  rethrowing the first serious failure. Distinct additional serious failures are suppressed onto
  it. Ordinary Exceptions retain their existing isolated/suppressed behavior. Close clears state
  before rethrowing, preserves device-before-auxiliary ordering and closes shared identities once.
- Multiple names for one SubsystemIO caused repeated physical refresh and safety calls per loop.
  Registration now builds a private array of unique physical objects by identity. Warm loops
  traverse that stable snapshot without creating collections or checking device types. The first
  surviving logical name determines physical ordering. Name replacement and equal-but-distinct
  devices remain supported, while each logical telemetry prefix is still published.

Kotlin already guards self-suppression in its Throwable extension. Bytecode inspection and a
passing baseline regression rejected that hypothesis; no FTC exception helper was changed.
The small private FRC helper removes duplicated failure handling without adding public API.

## Validation

Twelve new regression methods ran against unchanged production code: eight failed and four
passed. After correction all twelve pass, together with existing registry shutdown, registry
behavior, FRC lifecycle and zero-GC checks (36 focused results in total).
The focused allocation regression measured zero bytes for 10,000 warmed refresh/safety pairs
with 32 aliases; it observed 20,000 physical calls versus the baseline's 640,000. This is a desktop
JVM measurement of registry iteration, not a robot timing or SDK allocation measurement.

Source commit: `88fb3f0b34e40602a60a0050e8f2b531c6a6d1d0`.
Library tree: `6cb97758e409ea1d12b60b63b6bb0078106287af`.
Candidate: `17.0.21-rc.6cb97758e409`.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,487 | 0 |
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

The suites account for 5,296 passing results, zero failures/errors and six existing Studio
skips. These cover three opt-in starter integration scenarios, native file chooser, dashboard
performance baseline and physical dashboard validation. FTC/starter generated-project checks
and APK assembly passed; FRC/starter generated-project checks passed. All 410 candidate files
were hashed and reverified after consumer validation. Monorepo policy passed, including
source/version/archive identity, shared guidance and links in 375 current documents, with
38 explicitly historical records skipped. Four rebuilt archives differ only in version properties.

Evidence directory: `ARESLib-Kotlin/build/audit-pass214-verified-evidence/`, including the
original failures, focused/full XML, candidate identities and hashes, archive comparisons and
final summary. Gradle cache/up-to-date results count as validated suite results; focused tests
are not counted twice in the aggregate.

## Remaining scope and coverage

These guards reject calls begun after close. They do not cancel an update already executing.
The lifecycle owner must quiesce foreground callbacks and registration before closing.
Registry polling shutdown still has a bounded join and cannot cancel blocked device IO.
Generic registration/polling concurrency, polling counter rollover, cached motor/current-source
alias semantics, inherited subsystem mutation/ownership, FRC frame-time edge cases and shared
global status ownership remain distinct scopes. HardwareRegistry and FrcBaseRobot retain partial
coverage rather than claiming their complete runtime behavior has been audited.

The ledger accounts for 2,942 tracked files: 1,157 fully reviewed, 166 partially reviewed
and 1,619 pending, with zero stale or orphaned records. This pass adds two fully reviewed
regression files and this report, and moves FrcBaseRobot from pending to partial coverage.

No physical robot, target MicroPython runtime, rendered Studio window, remote workflow,
deployment or public release was exercised. These findings are in ARES code; this pass does
not identify a WPILib or CTRE defect.
