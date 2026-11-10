# SDK 12 servo cache reliability checkpoint

Date: 2026-11-10. Branch: `codex/ftc-sdk12-20261109`. Baseline: `cb51688d8`.
Work remains in the isolated audit worktree; the original dirty checkout is excluded.
This follows the [remaining software verification](FTC_SDK12_SOFTWARE_FOLLOWUP_2026_11_09.md).

## Reproduced defect and fix

`CachedServo` delegated SDK direction and range mutations while retaining its last logical command.
The actual SDK 12 `ServoImpl` changes the mapping between logical position and controller output.
After either mutation, the decorator could return an obsolete cached position and suppress an
explicit repetition of the prior position. This defect was present at the selected baseline;
the review does not attribute it to an official SDK regression or establish its earliest origin.

Failure-first tests against the real SDK implementation with a mocked `ServoController` showed:

- Position `0.4`, reverse direction, explicitly request `0.4` again: controller stayed at `0.4`
  instead of `0.6`.
- Position `0.4`, scale to `[0.2, 0.8]`, explicitly request `0.4` again: controller stayed at `0.4`
  instead of `0.44`.
- Library fixtures also reproduced stale getters and suppression after a delegate changed its
  configuration then threw. The SDK/mock linkage check alone cannot detect these semantics.

The decorator now invalidates its accepted-command flag **before** `setDirection` and `scaleRange`.
The next getter observes the delegate, and the next explicit command reaches it. Subsequent
identical/sub-epsilon writes remain suppressed. Configuration does not issue a position command;
finite-value checks, clipping, reset handling, unknown-write retries, and upstream enable/freshness
requirements remain intact. No synthetic universal servo neutral was introduced.

The change and regressions are committed as `0cd05d83`; the library source tree is
`d56236e7b4faebbcb559f343b165a3ef62bc32b6`.

## Performance and compatibility evidence

The desktop allocation probe uses a primitive counting servo. After 50,000 three-command warmup
patterns (150,000 setter calls), each 20,000-pattern measured window contains 60,000 setter calls:
40,000 accepted writes and 20,000 suppressed sub-epsilon writes. ThreadMXBean counters observed
**zero allocated bytes in each of two consecutive windows**, with zero warmup-following overhead
in this run. This establishes the measured JVM path's allocation/write-suppression contract;
it is not a controller bus-latency, loop-deadline, physical actuator, or speedup measurement.

Full library validation: **3,112 tests, zero failures/errors/skips**, plus API dump/check. The binary SDK/mock regression still verifies **740 referenced member instructions**. All cache correctness and allocation regressions pass.

FTC and FTC starter: generation/verification, **198 unit tests**, **14 simulator tests**, and both debug APK assemblies passed. The two new tests use actual SDK 12 `ServoImpl` with mocked controllers and now observe the correct mapped outputs and getters. FRC and its starter passed generation/verification and **512 tests**. Both Android builds loaded D8/R8 9.1.29 without Kotlin metadata rewrite errors.

Studio validation: **26 focused archive/authoring/round-trip-support cases**, **four dedicated real consumer round-trip/recovery cases**, **59 dashboard smoke cases**, and the baseline comparison all passed without skips. Shared 37 and gateway 18 tests also passed. Fresh official/generic FTC/FRC/XRP and representative FTC/FRC projects were exercised. The full unrelated Studio unit suite was not rerun. Desktop smoke metrics and unchanged budgets are preserved in the evidence summary.

Two isolated Studio launch/capture/graceful-close cycles passed with this candidate. Exact owned setup-window captures were inspected; both PIDs exited, loopback ports closed, and runtime snapshots were removed. No crash log, watchdog halt or closed-database shutdown error was observed. This verifies startup/cleanup, not a new complete graphical authoring walkthrough.

Library and consumer versions advance together without replacing existing artifact bytes:

| Input | Value |
|---|---|
| ARES release pin | 20.0.1 |
| Validated local candidate | `20.0.1-rc.sdk12.reliability.20261110.1` |
| Studio | 7.0.67 |
| FTC / FRC starter archives | 20.0.1 / 20.0.2 |
| XRP starter archive | 3.0.66 |
| Lightbot / BIOBUZZ archives | 3.0.70 / 1.1.8 |
| FTC SDK / D8-R8 | 12.0.0 / 9.1.29 |

The library was published once to the isolated local validation repository before consumer checks.
All final builds use that one candidate. New deterministic archives carry the new dependency pin;
Lightbot also includes the actual-SDK regression fixtures. Prior archives/hashes remain in Git
history. Workflow versions/URLs/hashes, catalog resources, source identity and manifests are aligned.
The version preflight correctly rejected the first focused check before its canonical pin had
been advanced; the manifest was updated and the guard was preserved.

## Scope, guidance and limits

This audit covers the changed cache decorator and its SDK mutation semantics, failure/retry
fixtures, allocation/write-count contract and affected dependency/bundle consumers. The unchanged
VisionPortal freshness, stationary-ID/mount and cluster-targeting boundaries were inspected for
adjacent issues, with no additional defect confirmed. It is not a full upstream or repository audit.

The canonical SDK build skill now calls out mapping-changing SDK mutations, pre-delegation
invalidation, actual implementation tests, and separate allocation/write-count measurements.
Personal skill locators remain unchanged. Scoped review records retain earlier evidence/history.

Canonical skill validation, six guidance unit tests, local-link/source/archive policy, and the maintainability ratchet passed. The size ledger remains 1,116 production files and zero violations; limits were unchanged.

Local evidence is retained under `build/sdk12-reliability-20261110/`: failing/passing JUnit results,
actual SDK bytecode inspection, library/consumer/build logs, allocation output, new bundle hashes,
Studio child projects, and desktop evidence. The [tracked evidence summary](FTC_SDK12_CACHE_RELIABILITY_2026_11_10_EVIDENCE.json)
binds the source/candidate, commands, counts, artifacts and limits; the [file ledger](file-reviews.json)
records bounded review and validation separately. Earlier baseline evidence was preserved.

No physical servo/controller, Driver Station pairing, camera processing, or on-controller timing
was tested. Desktop mocks and SDK JVM instances do not establish physical reliability. No public
release, protected-main merge, deployment or integration of the original checkout is claimed.
