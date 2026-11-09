# Framework lifecycle and skills checkpoint — 2026-11-09

## Scope and source

Bounded continuation of the [published acceptance follow-ups](PUBLISHED_7_0_65_ACCEPTANCE.md):
inspect startup/shutdown ownership and assess the four canonical ARES skills. This is not a
repository-wide audit or a resumption of a historical goal.

- Base: `ba7402263bd46f29c4204e0194d75efcfe7d43cc` on `codex/published-app-acceptance`.
- Isolated branch: `codex/framework-lifecycle-audit-20261109`, worktree
  `.codex-validation/framework-lifecycle-audit-20261109` under the original checkout.
- Protected main observed through `git ls-remote origin refs/heads/main`:
  `8294bd6de9663e4f9cecc86dbd6a64acc3ae36ee`.
- The original checkout has extensive pending library, Studio, product, and skill changes.
  Those changes were preserved in place and are not validated by these isolated-build results.
  Its registry still contains the same delegate-inspection defect, but its combined tree was not built.
- No library, version, public contract, starter archive, robot output policy, or release workflow
  changed. No push, pull request, release, deployment, or physical hardware operation occurred.

## Confirmed defect and correction

`ServiceRegistry.lazyFieldInitialized` inspected a Kotlin delegated property without granting
reflection access to its private delegate field. Even public service properties have private
delegate fields. `getDelegate()` therefore threw, and the existing conservative fallback returned
`true`. Disposal then accessed and initialized services that had never been used.

A first focused probe failed on an untouched database service. Two subsequent regressions exercise
the actual `disposeAndJoin()` path, replacing cold lazy initializers with traps to prevent real
storage, native-device, or client startup. Both failed against unchanged production at the first
unused `updateCheckerService` initializer. The partial-startup case also requires already-created
keyboard controls to be disarmed and held inputs released; avoiding disposal altogether cannot pass.

The correction makes the property accessible before reading its delegate. Initialized services
still follow the existing cleanup and persistence order. The exceptional fallback is retained.
Both behavior regressions now pass, including repeated cold disposal.

This establishes an unused-service startup defect during disposal. It does **not** establish the
cause or resolution of the earlier installed-app `Connection was closed` event.

## Skills assessment

| Canonical skill | Disposition and evidence |
|---|---|
| `ares-workspace` | Updated its audit reference to follow the latest relevant unresolved checkpoint, distinguish an isolated baseline from the user's pending tree, and avoid inheriting historical delegation/release authorization. |
| `compose-desktop-tester` | Updated its recovery reference to exercise cold/partial initialization and inspect coroutine/dependency lifetime during early close. Two settled-window cycles alone do not cover that case. |
| `ares-build-release` | No change needed in the selected committed source. Dependency modes, candidate reuse for a consumer-only correction, process isolation, and failure-evidence guidance match this pass. |
| `ares-subsystem-authoring` | No change needed in the selected committed source. Ownership, generated-source boundaries, source-derived schema identity, and confirmation routing remain applicable. |

All four skill folders pass the skill-creator validator. Shared guidance/link checks and all six
agent-guidance regression tests pass. Pending text-DSL skill additions and their uncommitted library
implementations in the original checkout were not imported into this branch or certified here.

## Validation

The binary dependency for every Gradle test and native run was the existing isolated candidate
`19.1.5-rc.acceptance.20260925.1` from the original checkout's
`ARESLib-Kotlin/build/release-repository`, supplied as an absolute file URI. The pinned source version
is `19.1.5`; Studio is `7.0.65`. No new publication was required for this Studio-only correction.

The focused test command was `:app:test --offline --no-parallel`, selecting
`ServiceRegistryShutdownAuditTest`, `DesktopServiceBootstrapTest`, `desktop.*`,
`DatabaseCoordinatorAuditTest`, and `DatabaseServiceIntegrationTest`, with the candidate properties
above. Result: **59 tests in 11 suites, zero failures, errors, or skips**. The initial unchanged
shutdown/coordinator smoke run passed; the new baseline behavior regressions then failed twice as
described above. The first sandboxed wrapper attempt could not access the Gradle cache and executed
no tests; the authorized retry used the existing cache.

Three native launches used the same isolated test home and `-PskipKill`. Two automatic exact-window
captures followed settled presentation and posted native `WM_CLOSE`. A third launch verified the
owned PID `50064`, captured through dedicated loopback port `49339`, and closed through that port.
All captures were inspected and show the rendered setup screen. All three runs completed normally,
without forced-exit escalation; all runtime snapshots disappeared. The exact owned PID exited and
the test port stopped listening. These runs cover a fresh setup profile, not the large BioBuzz profile.

The first source-policy run rejected the stale source-size inventory after the four-line registry
change. Regenerating that inventory changes only its registry line count. The final policy check
passes with **1,114 production Kotlin files, zero size violations**, unchanged limits, valid release
identity, and valid guidance/Markdown links. `git diff --check` passes.

Local logs, baseline/fixed JUnit XML, PNGs, and process/snapshot evidence are retained under
`build/audit-lifecycle/` and `build-*-baseline.log`, `build-lifecycle-fixed.log`,
`build-native-*.log`, and `build-source-policy*.log` in this worktree. The small
[evidence summary](FRAMEWORK_LIFECYCLE_SKILLS_2026_11_09_EVIDENCE.json) binds source hashes and counts;
the shared file-review ledger records bounded review depth separately from passing checks.

## Remaining work

The two earlier acceptance follow-ups remain open: bulk-import responsiveness on a large profile,
and reproducing the late workspace-session read during startup shutdown. In particular, joining or
preventing bootstrap/window-owned work before dependency closure still needs a controlled early-close
reproduction. The cold-registry fix and fresh-profile native cycles do not close that investigation.
No controller timing, electrical neutralization, or physical hardware result is claimed.
