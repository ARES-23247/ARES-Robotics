# OTOS lifecycle and input-refresh checkpoint

Date: 2026-11-10. Baseline: `887fcb4df`, branch `codex/ftc-sdk12-20261109`.
The isolated worktree preserves the original checkout's unrelated pending changes.
This follows the [servo-cache audit](FTC_SDK12_CACHE_RELIABILITY_2026_11_10.md).

## Confirmed issues and corrections

Failure-first tests found that OTOS reset could retain previous velocity with a newly stamped
timestamp even if the first subsequent read failed; an uncertain reset did not suspend sampling;
and close allowed further SDK reads and fresh samples. The adapter now invalidates motion and
freshness before reset, requires successful reinitialization after a failed reset, and permanently
stops sampling on close. The injected SDK driver remains caller-owned.

Short cache locks and lifecycle generations reject delayed read completions/failures after close
or reset. SDK I/O runs outside the cache lock, so close completes while a device read/reset is
blocked. Controlled latch tests cover late success/failure, old-frame reads, and reset completion
after close. No old operation can reopen sampling or replace closed health.

`updateInputs` formerly constructed an intermediate owned action. With a primitive SDK fixture,
50,000 warmup refreshes followed by 20,000 measured refreshes allocated **2,720,000 bytes before
the fix and zero after**, in both normal JVM and escape-analysis-disabled runs. ThreadMXBean
measurements exclude SDK/transport internals. The legacy `getPoseUpdate` API still returns an
independently owned action; it is not converted to a borrowed mutable snapshot.

Actual SDK 12 bytecode also showed temporary allocations inside its output-parameter bulk-read
API. That API was not substituted on the assumption that output parameters imply zero allocation.
The existing position/velocity read strategy and coordinate/unit conversions are preserved.
No controller timing, physical sensor accuracy, or end-to-end zero-GC claim is made.

## Validation and identities

- **3,120 library tests**, API dump/check, and normal/diagnostic allocation checks passed.
- FTC and FTC starter: **201 unit tests**, **14 simulator tests**, generation/verification and both
  APK builds passed. Three new tests use actual SDK driver/pose types with mocked device calls;
  no I2C device is used.
- FRC and its starter: **512 tests** and generation/verification passed.
- Studio: **24 affected authoring/archive cases**, shared **37**, and gateway **18** tests passed,
  including fresh pinned FTC/FRC/XRP projects. Unrelated full Studio, duplicate generic/representative
  builds, and previously verified generation-recovery/performance suites were not repeated.
- All selected checks passed without skips. D8/R8 remains 9.1.29, FTC SDK 12.0.0.

Library commit: `6a52af60`. Source tree: `dc60c8818d75ff351d909535befa9bf8c4096ca9`.
The unique local candidate is `20.0.2-rc.sdk12.otos.20261110.1`. Canonical pins: ARES **20.0.2**,
Studio **7.0.68**, FTC/FRC starters **20.0.2 / 20.0.3**, XRP **3.0.67**, Lightbot **3.0.71**,
BIOBUZZ **1.1.9**. New immutable archives and workflow hashes are aligned; prior bytes are preserved.

Two isolated Studio launch/capture/graceful-close cycles passed. Exact owned setup-window captures were inspected; PIDs exited, ports closed, runtime snapshots were removed, and no crash/watchdog/database-close error was observed. This is startup/cleanup evidence, not a full new graphical workflow.

The SDK build skill now covers reset/close invalidation, late-read fencing, short cache locks,
owned snapshot compatibility, and separating wrapper allocation evidence from SDK internals.

Source/archive/link policy, six guidance unit tests, the canonical build skill validator and the unchanged maintainability ratchet passed (1,116 production files, zero violations).

The [evidence summary](FTC_SDK12_OTOS_RELIABILITY_2026_11_10_EVIDENCE.json) binds source, candidate,
test counts, before/after measurements, commands and hashes. Raw evidence is retained under
`build/sdk12-otos-20261110/`; the [file ledger](file-reviews.json) records scoped review separately
from validation and retains earlier history. A fixture-only JVM getter-name clash was corrected
before recording the failing runtime baseline; that compilation failure was not a product finding.

Physical OTOS/controller/camera validation, Driver Station pairing, and target timing remain
outstanding. No public release, protected-main merge, deployment, or integration of the original
dirty checkout is claimed. Subsequent setup/project-creation UI work has its own validation scope.
