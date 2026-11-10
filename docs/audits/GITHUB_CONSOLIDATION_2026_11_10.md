# GitHub consolidation and fresh-CI follow-up

Date: 2026-11-10. Baseline: `03d1e40ee6d4e9eab82d352168bd6573129177b8`,
branch `codex/ftc-sdk12-20261109`. Integration: [PR #109](https://github.com/ARES-23247/ARES-Robotics/pull/109).
This checkpoint follows the [setup and history audit](STUDIO_SELECTION_HISTORY_2026_11_10.md).
It reviews the changed CI environment, test diagnostics, and one real-wire fixture; it is not a
new full-file or hardware audit. The original dirty checkout and all other worktrees are preserved.

## Fresh-CI failures and corrections

The first PR run passed source/release policy, ARESLib candidate, FTC robot/simulator, both starter
source gates, FRC contracts, dashboard budgets, autonomous verification, and CodeQL. It exposed
failures absent from cached local validation:

- Windows fresh-project acceptance failed resolving WPILib `2024.3.2` from `frcmaven.wpi.edu`:
  Java rejected the certificate chain. A local HTTPS probe using the exact CI Zulu
  `17.0.21+0.3.1` truststore reproduced `PKIX path building failed`. The same probe using the
  CI Temurin `17.0.21+9` truststore returned HTTP 200. Both probes used the same local Java
  `17.0.12` executable and URL; only the truststore changed. Desktop candidate, verification,
  acceptance, and native-package jobs now use Temurin, matching the monorepo jobs. TLS and
  hostname verification remain enabled; no certificate is imported or repository URL replaced.
- Linux ordinary app tests ran 2,807 cases, with three failures and 11 skips. Both real Compose
  database-shutdown cases failed with `HeadlessException`. The Linux Studio Gradle commands
  now run under `xvfb-run -a`, retaining the actual Compose cancellation/bootstrap/database
  assertions. Windows local execution passed both cases; Linux confirmation belongs to CI.
- The real-wire health test also timed out locally at its final stale-health assertion. Its
  injected receipt clock jumped while health frames were still queued in the socket/UI fan-out,
  making their later receipt look fresh after the jump. It now uses real monotonic receipt time
  with a 500 ms producer cadence, subscribes before connection, and waits for both health topics
  at each robot timestamp. Seven equal-value publications span three seconds; after production
  stops, health must expire while another topic arrives and the connection stays open. The
  two-second freshness limit and five-second assertion timeouts are unchanged. Deterministic
  tracker tests remain separate. The fixed wire test and both shutdown cases passed locally
  (three tests, zero failures/errors/skips).

The Zulu release-verification job also reported four failing nested consumer round-trip cases.
Its artifact excluded the custom test task's reports and child-build evidence, so the original
underlying assertions were unavailable. Full test exceptions and all app test-task XML/HTML and
consumer evidence are now retained. The truststore correction is not independently claimed to
resolve those four assertions until the fresh CI reports establish their result.

At follow-up commit `65915631e5797e897c797bd377433c16ae47162b`, the
[monorepo run](https://github.com/ARES-23247/ARES-Robotics/actions/runs/42876932903) passed
all 2,807 ordinary app tests (11 skips) and all four consumer round-trip/recovery tests (no skips),
with zero failures/errors. The
[desktop run](https://github.com/ARES-23247/ARES-Robotics/actions/runs/42876932896) passed
fresh Windows FTC/FRC/XRP acceptance and the complete Studio release-verification job. This
establishes the corrected CI contract's result; the unavailable original child assertions are
not retrospectively assigned a proven cause.

The complete local Windows `studioReleaseVerification` contract also passed: 2,926 tests across
ordinary app, consumer, dashboard smoke/performance, shared, and gateway tasks; six expected
Windows skips, zero failures/errors, and all coverage/maintainability gates passed. The command
supplied Temurin through `JAVA_HOME`, but Gradle's toolchain/daemon selection used local Oracle
Java 17.0.12 workers. Temurin-specific confirmation comes from CI and the isolated truststore
probe. The original three-test result remains separate from this complete-suite result.

## SDK sample and immutable archive follow-up

The separate CodeQL PR scanning check reported implicit double-to-float compound assignment in
both SDK `SensorColor.java` sample copies, even though analysis/upload jobs succeeded. The sample's
gain is a float; both increase and decrease now use a `0.005f` step. Both FTC and starter
`FtcRobotController:compileDebugJavaWithJavac` tasks passed against SDK 12 after the correction.
These scanner classifications are not independent evidence of an exploitable vulnerability.

Because those samples are exported, new archives reserve FTC starter `20.0.3`, Lightbot `3.0.72`,
and BioBuzz `1.1.10`; Studio advances to `7.0.69`. Existing-version bytes are not overwritten.
Freshly regenerated FRC `20.0.3` and XRP `3.0.67` archives retain their exact prior hashes. The
canonical manifest, bundled resources, and workflow URLs/checksums are updated together. Library
version/tree/candidate remain unchanged. After removing the top-level archive-name prefix, each
new FTC/example archive differs from its predecessor in exactly the sample file; no file is added
or removed. All 19 template-service/BioBuzz authoring tests passed against these new bundles,
with zero failures/errors/skips. Source/archive/guidance policy and the 1,117-file,
zero-violation maintainability ratchet pass. Final archive/sample integration remains subject to
the subsequent PR-head CI checks, rather than inheriting the earlier archive acceptance result.

Original run evidence: [monorepo run](https://github.com/ARES-23247/ARES-Robotics/actions/runs/42871938995),
[desktop run](https://github.com/ARES-23247/ARES-Robotics/actions/runs/42871938850).
Local probes, failed/fixed XML, logs, inventory, and deletion evidence are retained under the ignored
`build/github-consolidation-20261110/` directory. Canonical build guidance now covers Java
truststore diagnosis, virtual displays, custom-task artifacts, and clock/receipt synchronization.
Required protected-branch checks on PR #109 remain the integration gate; these focused local
results do not substitute for the complete fresh-CI contract.

## Branch and pull-request cleanup

At main `8294bd6de9663e4f9cecc86dbd6a64acc3ae36ee`, ancestry and worktree inspection established
that 23 unused local branches and six remote branches were already merged. They were deleted;
remote deletions were guarded by their exact observed head SHA. No checked-out local branch,
worktree, dirty contents, tag, main, Maven publication branch, or unique unmerged change was removed.

PRs #88, #94, and #95 were closed only after verifying their complete filename sets and patch
text duplicated the newer open PRs #99, #100, and #101 respectively. The three closed heads were
subsequently absent from `git ls-remote`. Remaining dependency PRs include failing checks and
coordinated Kotlin/library-version changes; they are not eligible for an unchecked bulk merge.
The clean BioBuzz turret branch contains 18 separate commits, shared library changes, and
overlapping version/archive work. It requires a new integration/candidate validation pass.
Checked-out and other unique branches remain available for that review.

No branch-protection bypass, direct main commit, public artifact promotion, live robot actuation,
or physical hardware validation is part of this checkpoint. Library source-tree identity remains
`dc60c8818d75ff351d909535befa9bf8c4096ca9`; local tests use candidate
`20.0.2-rc.sdk12.otos.20261110.1` from the isolated file repository.
