# Studio 7.0.60 / ARESLib 19.1.1 integration review

Reviewed the unpublished `antigravity/audit-pass278` work through `d16dcb84b`, together with
Limelight cluster checkpoint `587816751`, against protected main `121da5b65`.
[Release pull request](https://github.com/ARES-23247/ARES-Robotics/pull/103).
This is a bounded integration/release review, not a claim that every repository file was audited.
The earlier broad audit goal remains paused.

## Review decisions and fixes

- Preserved the Studio service/view-model extractions, file consolidation, off-UI-thread telemetry
  queries, formatter reuse, finite matrix-inverse handling, geometry helpers, and cluster support.
  Reviewed the substantive changes separately from unchanged declarations moved between files.
- Fixed an OAuth extraction regression: old login/logout cleanup could stop a successor's callback
  server. Server installation and detachment now share the authentication lifecycle lock; detached
  shutdown targets only the captured server. A real loopback-server regression exercises delayed
  cleanup after replacement.
- Fixed the new rumble helper's expired cooldown state and stale-trigger activation. Duration
  configuration is validated before polling. These were defects in the newly introduced helper.
- Removed new Compose immutability annotations from chart containers holding caller-owned lists;
  their API does not enforce immutable ownership. Primitive immutable models retain their annotations.
- Fixed the new inventory guard: verification now recomputes current tracked file sizes instead of
  accepting a saved PASS field. Removed unsupported AUDITED/VERIFIED labels based on dirty state.
  Missing tracked sources and stale/tampered inventory fail checks. Small-file counts are descriptive.
  Repaired the policy fixtures to include their new ledger dependency.
- Restored upstream FTC controller/sample files byte-for-byte from main, following the documented
  SDK ownership boundary. Kept a corrected reference document without unsupported performance claims.
- Fixed the BioBuzz drivetrain/tuning identity mismatch found by opening the real example in Studio.
  The inherited generic starter UIDs disagreed with BioBuzz's project identity and blocked the
  drivetrain builder. The canonical drivetrain and tuning profile now share BioBuzz ownership.
  A bundled-archive test opens the actual document through `DrivebaseBuilderViewModel` and checks
  project ownership and tuning consistency. Runtime-only generated-code tests did not detect this.
- Fixed a pre-existing FRC starter simulation allocation path exposed by the generated-project CI
  run. Three nullable `Double.takeIf` expressions boxed values until JVM escape analysis eliminated
  them. Primitive finite checks retain the same invalid-input behavior without those allocations.
  The unchanged 1,024-byte allocation budget passes with escape analysis disabled: 3,600,000 bytes
  before the fix versus zero afterward across 50,000 frames. FRC Starter is versioned 19.1.2.
- Fixed a pre-existing file chooser job-tracking race exposed by the separate Studio consumer CI
  run. Immediate folder creation could overwrite the pending navigation job with its completed
  predecessor. Operations now record ownership before starting. A controlled dispatcher reproduces
  the old failure deterministically and verifies that waiting for idle includes follow-up navigation.

## Local evidence

One library candidate, `19.1.1-rc.c85fa2951f3c`, binds library tree
`c85fa2951f3c3d168aee03fbe009b6c0586d65b6`. Logs and screenshots are retained locally under
`build/release-review/` in the isolated review worktree.

- Full library tests and API checks passed: 3,080 tests, no failures or skips.
- FTC/FRC products and both starters passed their applicable generated-project, robot/simulator,
  and Android build checks against that candidate. The exported BioBuzz scenario covers cluster
  observations, simultaneous intake/shooter commands, missing feedback, and safe stop.
- `studioReleaseVerification` passed: deterministic suites, coverage gates, maintainability and
  dashboard budgets. Six opt-in checks were skipped; this does not represent hardware coverage.
- All 107 repository script tests passed after repairing the policy fixtures. Source identity,
  archive hashes, documentation links, and source policy passed.
- Three isolated native Studio launches rendered and closed gracefully. Observed onboarding,
  the BioBuzz dashboard, field-panel expand/restore, Profile settings, and loaded control bindings.
  The drivetrain screen exposed the identity mismatch described above. After the fix, the actual
  BioBuzz 1.1.3 archive opened its drivetrain editor without the ownership error. The additional
  archive/authoring regression and regenerated BioBuzz robot/simulator/APK checks also passed.
- After the chooser ownership fix, all chooser state/async/render tests passed, along with the
  opt-in native Windows test. Both dialogs rendered; real approval and cancellation each closed
  the owned window and completed its result. Captures are retained in the app's diagnostics output.

Desktop dashboard smoke baseline: all 12,000 expected samples persisted, zero drops; approximately
353,539 frames/s ingestion, 11.99 ms query p95, 26.07 ms replay-scrub p95, and 1.27 MiB heap growth.
Explicit budgets were at least 1,000 frames/s, query p95 at most 1,000 ms, scrub p95 at most 2,000 ms,
heap growth at most 256 MiB, and zero drops. These are desktop service measurements, not camera
latency or robot-controller timing. See the separate [cluster checkpoint](LIMELIGHT_CLUSTER_CHECKPOINT.md)
for the geometry benchmark and remaining hardware checkpoint.

## Publication and remaining limits

Promotion must follow all required PR checks and protected merge, then verify the attested candidate
against the complete main Git tree and promote its exact bytes. Earlier candidates from a superseded
PR head are ineligible. No branch checks or provenance controls are waived for this release.

Physical Limelight calibration, cluster accuracy during motion, sensor-to-actuator latency, and
real-controller timing remain unverified. Cluster observations do not automatically arm or aim a
robot. A narrow field-edge label can wrap vertically in the BioBuzz display; this cosmetic finding
is deferred. No broad follow-on audit was started.
