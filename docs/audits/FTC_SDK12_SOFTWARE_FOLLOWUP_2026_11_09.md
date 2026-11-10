# FTC SDK 12 remaining software verification

Date: 2026-11-09. Branch: `codex/ftc-sdk12-20261109`.
Baseline: `ecdc94bc3`. This continues the [SDK migration checkpoint](FTC_SDK12_MIGRATION_2026_11_09.md)
in its existing isolated worktree. The original dirty checkout remains excluded.

## Scope and correction

Reviewed the changed VisionPortal adapter, pose conversion, field-library factory, real-SDK compile
configuration, SDK/mock bytecode check, cache wrappers, OTOS setup, and their selected tests/callers.
This is a bounded compatibility follow-up, not a full upstream SDK or monorepo audit. The review
found a documentation ambiguity: field tag roll/pitch/yaw are degrees, while camera-mount
`Rotation3d` angles and internal headings are radians. The runtime already converts these correctly.
The migration guide and canonical SDK skill now distinguish the units explicitly.

The corrected library source tree is `757ee76277d5e1451823dabb985c86192331eb97` (commit `c00fb28d`).
It was published once locally as `20.0.0-rc.sdk12.20261109.4`; the earlier `.3` candidate is preserved.
All **1,768 uncompressed entries, including 1,690 classes, across 16 published runtime JARs** are
byte-identical between `.3` and `.4`. Existing software evidence using `.3` therefore still applies
to the runtime; exact source/candidate identities remain separate. Canonical release versions and
all five immutable starter/example archives are unchanged. No external publication occurred.

## Remaining checks

The three software opt-ins skipped in the preceding checkpoint have now been exercised:

- Generic starter acceptance: fresh FTC, FRC, and XRP projects created through the production
  archive verification/personalization path and passed their generation, unit/simulation tests,
  verification, and applicable packaging. The template dependency version came from the archive
  manifests, independently of the FRC artifact's 20.0.1 filename. Hardware Review gates remain.
- Native chooser: two rendered Windows dialogs were captured and inspected. Actual approval and
  Escape cancellation passed; dialog state and worker threads were released. The test JVM used a
  dedicated home, and its filesystem fixture was temporary. This does not claim a new complete
  Studio setup walkthrough or physical robot interaction.
- Dashboard performance: smoke workload **12,000 frames persisted and restored, zero loss**;
  ingestion **242,040 frames/s**, query p95 **16.1 ms**, replay load **24.4 ms**, replay scrub p95
  **29.3 ms**, and heap growth **5.70 MiB**. The checked-in baseline comparison passed without
  budget changes. These are desktop smoke measurements, not controller or long-duration results.

Observed JUnit results: **2 generic/native cases, 59 dashboard smoke cases, and one baseline comparison; zero failures/errors/skips.** The three previously skipped software opt-ins all ran.

The dedicated `:app:consumerRoundtripTest` is excluded from normal `:app:test`. It covers real FTC
export/reopen/edit/build flows, saved tuning and generated simulated IO, preservation of user-owned
extensions, deterministic regeneration, controlled partial-write failure, cancellation, and recovery.

**All four dedicated round-trip cases passed, zero failures/errors/skips.** Retained operation logs include intentional failing child generation and cancellation as negative-test oracles; successful recovery and canonical/user-source preservation are asserted by the passing cases.

Candidate `.4` library test/API validation passed (**3,109 tests**, with unchanged test outputs reused by Gradle where applicable). FTC and FTC starter generation/verification, **196 unit tests and 14 simulator tests**, and both debug APK builds passed. FRC and its starter passed generation/verification and **512 tests**. Loaded D8/R8 was 9.1.29 with no Kotlin metadata rewrite errors. The additional Studio opt-ins and dedicated round-trip run used `.3`; all runtime JAR contents match `.4` as recorded above.

The ordinary Studio suite was not repeated wholesale: its source is unchanged. Its previous
2,789-case coverage, earlier representative recovery, and these three explicitly passing opt-ins
now leave only the physical telemetry opt-in outstanding. This accounting combines separate runs;
it does not relabel an earlier skipped or failing JUnit file as a fresh full-suite pass.

## Evidence and guidance

Local logs, JUnit snapshots, desktop measurements, exact chooser images, candidate runtime parity,
and child generation/build evidence are under `build/sdk12-followup/` and the retained Studio
`app/build/consumer-roundtrip-evidence/` directory. The [tracked evidence summary](FTC_SDK12_SOFTWARE_FOLLOWUP_2026_11_09_EVIDENCE.json)
binds counts, hashes, commands, candidates and limits. The [file ledger](file-reviews.json) records
the scoped delta and retains previous review/validation history.

The canonical build skill now routes the independent generic export, native chooser, performance
baseline, and dedicated consumer round-trip checks. It requires actual nonzero, non-skipped test
results for selected boundaries; `BUILD SUCCESSFUL` alone can include skipped or `NO-SOURCE` tasks.
The SDK-specific reference explains tag-angle units and the round-trip suite's ordinary-test
exclusion. Personal locator skills remain unchanged.

Tracked guidance, local links, source identity, archive hashes and maintainability policy passed; six guidance unit tests and the changed build skill validator passed. The size ledger remains 1,116 production files with zero violations and unchanged limits.

Physical Control Hub/Driver Station pairing, camera processing/calibration, actuator behavior, and
on-controller performance remain untested. The hardware-dashboard opt-in was not claimed as
physical coverage. No protected-main merge, release promotion, deployment, or integration into the
original dirty checkout was performed.
