# Local project-roundtrip submission review

Date: 2026-09-17. Scope: review and integrate the other agent's submitted changes locally;
no GitHub push, pull request, release, deployment, or new broad audit.

## Identities and scope

- Released baseline: `9da7fe90e3d5f244ff8ad56cfbaba063dc1cba3d` (PR #105 / Studio 7.0.62).
- Submitted commit: `dbb5b9f600aecd45d78d9adc783478b1a5eeb6c9` on `codex/project-roundtrip-audit`.
- Review checkout: `.codex-validation/reviewed-project-roundtrip`, branch `codex/reviewed-project-roundtrip`.
- Reviewed library source tree: `4161ce50ae762d9ba02cd65663c6e9a40da6afbf`.
- Isolated local dependency candidate: `19.1.3-rc.roundtrip.dbb5b9f.1`, published only under
  this checkout's `ARESLib-Kotlin/build/release-repository`.

Reviewed the nine submitted paths, the generator's actual hashing caller, starter preview/apply
tasks, output ownership helpers, export root/sensitive-path/atomic-write helpers, and affected
test/CI/version contracts. This is a bounded change review, not full-file review of every dependency
or proof of the complete authoring-to-export-to-reopen workflow.

## Findings and disposition

1. **Retain: pre-existing preview side effects.** `--preview-subsystem-starters` previously reached
   runtime/drivebase/superstructure/verification writes. It could also reject existing runtime
   output that preview should leave alone. The early preview return fixes this. Four regression
   cases on the released CLI failed; they cover standalone and subsystem-only preview, both with
   absent outputs and existing output bytes. The reviewed cases compare the complete fixture's
   files before/after and verify that subsystem-only application writes only its owned roots.
2. **Retain: pre-existing archive leakage.** Portable exports included local tuning/verification
   state, recovery transactions, and internal `.ares` dot/lock files. The submitted exclusions
   are consistent with portable project ownership. Tests additionally preserve canonical tuning,
   subsystem, routine, and field bytes, assert the complete archive file set, and compare repeated
   exports byte-for-byte. These small archive fixtures validate file ownership, not document semantics.
3. **Remove: redundant hashing change.** `AresKotlinProjectGenerator` already sorts routines before
   passing them to its internal hash helper. Sorting again in the helper does not fix the real
   generation path. Restored the helper and changed the test to compare generated source and both
   hashes through the generator; removing a routine must still change the content hash. This test
   passes on the baseline. No timing improvement is claimed.
4. **Correct: library identity.** The submission changed the library source-tree pin while retaining
   already-published ARES 19.1.2. Reserved 19.1.3 locally and aligned the existing packaging workflow's
   ARES pin. Studio's integration test then exposed the coupled template requirement: project creation
   rejects a template declaring 19.1.2 when the app pins 19.1.3. Prepared Studio 7.0.63 locally with new
   FTC/FRC 19.1.4, XRP 3.0.63, Lightbot 3.0.65, and BioBuzz 1.1.5 archive filenames, identities, and hashes.
   Replaced superseded resource filenames in this checkout; prior bytes remain in Git history and the
   untouched GitHub release. No artifact was uploaded or republished under an existing version.
5. **Correct: review evidence.** The submission replaced historical ledger evidence with XML paths
   and broad review claims. Restored prior evidence and retained each submitted record separately
   under `projectRoundtripSubmission`. Current edits are partial review; validation is recorded
   independently. Hash refresh and green suites do not establish full-file coverage.
6. **Correct: pre-existing opt-in test harness.** Enabling `GenericStarterTemplateIntegrationTest`
   exposed its obsolete Gradle invocation for Python-native XRP. Fresh FTC and FRC projects passed,
   while XRP failed because `gradlew.bat` does not exist. The test now invokes `ares.bat build` on
   Windows (and `sh ares build` on POSIX), with Gradle/dependency arguments confined to FTC/FRC.
   This follows the already-correct official-template test's platform boundary; it changes no robot
   runtime or safety gate. The failing opt-in evidence is retained separately.

## Validation

Detailed commands, logs, before/after test XML, source snapshots, and summaries are retained locally
under `build/roundtrip-review/` in the review checkout. All consumers explicitly use the candidate
above and the absolute file URI of its repository; there is no ambient `mavenLocal()` substitution.

- Baseline CLI: `:codegen:test --tests '*AresProjectCodegenCliTest' --tests '*AresKotlinProjectGeneratorTest'`:
  30 tests, four expected preview failures; the generation-order test passes without the redundant sort.
- Reviewed library: `test apiCheck publishReleaseValidation --no-parallel`: 3,090 tests,
  zero failures/errors/skips; API check and local publication passed.
- Baseline exporter: `:app:test --tests '*ProjectArchiveExporterTest'`: three tests, one expected
  failure showing local/recovery files in the archive. The first attempt stopped at the stale workflow
  ARES-version pin before tests; after aligning that pin the behavioral failure reproduced.
- FTC/FRC starters: actual `previewSubsystemChanges` leaves 25/45 source and generated files
  byte-identical; generated-project verification and 17/206 tests pass. FTC APK assembly passes.
- FTC/FRC season consumers: generation, generated-project verification, and 190/306 tests pass;
  FTC APK assembly passes.
- Actual source-exported BioBuzz consumer: generation, verification, 34 robot tests, 11 simulator
  tests, and APK assembly pass. Evidence includes `biobuzz-validation.log` and its test summary.
- The first reviewed Studio service run exercised 119 tests (one failure, one opt-in skip). Its sole
  failure was the template/library identity mismatch described above; the export regression passed.
  Retained this run's log/XML under `studio-template-alignment-before.log` and `before-template-alignment/`.
- CI path classifier/boundary checks: 25 tests passed. The first sandboxed invocation failed on
  temporary Git-fixture permissions; the same checks passed with filesystem access. Both logs retained.
- New archive integrity: all 606 ZIP entries compare byte-for-byte with the canonical exported source
  copies (normalizing text CRLF to LF), and all five archive SHA-256 values match the pinned manifest.
- Source policy, shared guidance, Markdown links, and maintainability checks pass: 1,111 production
  files and no size-policy violations. These are policy/accounting results, not file-review coverage.
- Final Studio evidence: 118 unchanged scoped service/persistence/template tests pass in the integrated
  run, plus the corrected generic-template integration test passes on its focused rerun: 119 selected
  tests supported, no remaining failures or skips. This is not a full Studio suite run.
- The opt-in rerun creates fresh FTC/FRC/XRP projects through the actual Studio template service, then
  passes 17/206/124 consumer tests respectively, FTC headless drivetrain/rotation acceptance and APK
  assembly, and FRC `build`. Detailed results are in `studio-final-summary.json`,
  `generic-starter-verified.log`, and `fresh-{ftc,frc,xrp}-validation.log`.

Reproduction tasks (from the respective product roots, always with the candidate `-ParesVersion`
and absolute `-ParesRepository` above, and `--no-parallel --console=plain`):

| Product | Tasks |
| --- | --- |
| ARESLib | `test apiCheck publishReleaseValidation` (producer needs only the version override) |
| FTC + FTC starter + exported BioBuzz | `generateAresProject :TeamCode:verifyAresProject :TeamCode:testDebugUnitTest :simulator:test :TeamCode:assembleDebug` |
| FRC + FRC starter | `generateAresProject verifyAresProject test` |
| Studio | `:app:test --tests 'com.ares.analytics.service.versioncontrol.*' --tests 'com.ares.analytics.service.project.persistence.*' --tests '*RobotProjectTemplateServiceTest' --tests '*GenericStarterTemplateIntegrationTest' --tests '*BiobuzzTemplateAuthoringTest'` |

For the opt-in Studio creation/build check, supply `ARES_GENERIC_STARTER_ARCHIVE_DIR` containing
the new FTC/FRC/XRP archives named `ftc.zip`, `frc.zip`, `xrp.zip`; set
`ARES_GENERIC_STARTER_OUTPUT_DIR` to a fresh disposable directory,
`ARES_GENERIC_STARTER_VALIDATION_REPOSITORY` to the local candidate repository,
`ARES_GENERIC_STARTER_VALIDATION_VERSION=19.1.3-rc.roundtrip.dbb5b9f.1`, and
`ARES_GENERIC_STARTER_TEMPLATE_VERSION=19.1.3` (the test's dependency pin). The test additionally
  runs FTC `:TeamCode:runVerification`, FRC `build`, and XRP's Python-native `ares build` on newly
created projects. Preserve any existing output directory; use a new one for a new run.

The existing changed-part classifier routes ARESLib changes through library, robot/starter, Studio,
and packaging checks. New regressions run in the existing codegen and app test tasks; no CI redesign.

## Remaining checkpoint

The submitted batch did not supply an end-to-end proof of exporting a real configured project,
reopening it, regenerating, preserving USER-OWNED extensions, and running the resulting consumer.
That is the next bounded batch. Test partial generation failure and cancellation explicitly instead
of treating per-file atomic writes as a whole-project transaction guarantee. Existing low-level
ownership/rollback suites are useful evidence but do not by themselves close that integration gap.

No target hardware, native Studio interaction, whole-project transaction guarantee, or performance
improvement is claimed here. Local source validation is not a publishable release candidate: future
publication still requires complete native release validation and the protected CI/attestation/merge process.

The [next-agent prompt](NEXT_PROJECT_ROUNDTRIP_PROMPT.md) starts from the reviewed local branch and
targets the remaining export/reopen and failure-recovery integration scenarios. No next audit batch
was started during this review.
