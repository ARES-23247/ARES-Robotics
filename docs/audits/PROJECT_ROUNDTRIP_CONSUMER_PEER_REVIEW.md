# Local consumer round-trip peer review

Date: 2026-09-18. Submitted commit: `a4a9e6133`, based on the reviewed `d1ff42edf`.
The clean source checkout was on `codex/consumer-roundtrip-integration`. Review/fixes were made
in `.codex-validation/reviewed-consumer-roundtrip` on `codex/reviewed-consumer-roundtrip`.
This checkpoint is local only. No push, release, deployment, new task, or subagent was used.

## Findings and disposition

The submission added three integration-test classes and a report/ledger delta, with no production
robot/library changes. The consumer round trips are useful: they compile genuine generated FTC
projects, run simulated IO, preserve custom source, and compare multiple generated files. All three
new classes were reviewed; supporting service, schema, Gradle and CI paths were traced only as
needed. No new whole-file review credit is claimed for those existing production dependencies.

| Issue in the submission | Correction |
| --- | --- |
| All three classes defaulted to one user's absolute Windows Maven repository and old candidate version. Gradle did not forward its selected dependency arguments to those test JVM properties. | Add a dedicated `:app:consumerRoundtripTest` task using Studio's selected `-ParesVersion` and optional absolute local `-ParesRepository`. Remove machine-specific defaults. Ordinary `:app:test` excludes these Android consumer builds. Existing app CI scope explicitly runs both tasks and retains consumer evidence. |
| Generic template creation used the installed app's default cache. | Use a cache inside that test's temporary workspace. Require a discovered SDK with a clear configuration error instead of a hard-coded SDK fallback. |
| Two-stage polling could miss a short-lived RUNNING phase, especially an immediate preflight failure or repeated identical completion. | Share a small test driver using the existing production operation gate: observe scheduled ownership while execution is held, then release and join the owned job. A focused regression exercises repeated identical immediate failures. No production scheduling behavior changed. |
| Cancellation waited only for observable RUNNING, published before child creation, and never checked an OS PID or generated output. | Run the real code generator, hold the disposable Gradle task at a test-only completion barrier, observe a generated runtime and a live Gradle PID, cancel through ProjectBuildService, and assert that PID exits before retrying. The barrier is explicitly after CLI output, not inside a codegen write. |
| Configuration setup bypassed Studio persistence by writing files directly; tuning used a fragile regex. | Save geometry through ProjectMetadataRepository; use ProjectSession for subsystem, controls, routine/catalog, and typed tuning promotion. Reopen and check independent expected values. USER-OWNED source remains deliberately authored in the fixture. |
| Simulator checks showed basic motion but did not distinguish the edited configuration from the old one. | Execute generated geometry and autonomous metadata assertions in both consumers; the generic consumer additionally proves a 0.07 command is suppressed by the saved 0.08 deadband before full input drives the motors. |
| `runBuild` regenerates before verification, and the verification task itself depends on preparation, so failure with an obstructed manifest alone did not establish check-only rejection of existing incomplete outputs. | Invoke the real `:TeamCode:verifyAresProject` task with `-x :TeamCode:prepareAresSubsystemPlumbing` before and after recovery, retaining both logs. Assert the verifier itself fails at the obstructed manifest, preparation does not run, and existing runtime output and the obstruction remain unchanged. Normal build preparation is unchanged. |
| Recovery preservation checks covered only one geometry value and one extension. | Compare hashes of the entire `.ares` tree excluding machine-local `.ares/local` state before/after failure or cancellation, alongside USER-OWNED extension content. Require partial generated runtime output to be absent before the failed run. |

All are test infrastructure/coverage corrections introduced by this submission, not newly found
robot-control defects. No measured performance change is claimed. The previous report remains
as [historical submitted evidence](PROJECT_ROUNDTRIP_CONSUMER_REVIEW.md) with a correction notice.

## Validation and reproduction

Host: Windows, JDK 17, Android SDK. ARESLib is unchanged at tree
`4161ce50ae762d9ba02cd65663c6e9a40da6afbf`. Reused the already validated candidate
`19.1.3-rc.roundtrip.dbb5b9f.1` without publishing or changing its bytes. Repository:

```text
file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-project-roundtrip/ARESLib-Kotlin/build/release-repository
```

From `ARES-Analytics`, use `gradlew.bat` with that `-ParesVersion`, that `-ParesRepository`,
and `--no-parallel --console=plain`. The complete integration task is:

```text
:app:consumerRoundtripTest
```

It does not silently skip on missing SDK or dependencies. Local sibling substitution alone cannot
supply nested consumer builds; the task rejects that mode without an explicit validation repository.
Normal released development uses the selected released version and normal immutable repositories.

Evidence is retained under this review worktree's `build/consumer-review/`,
`ARES-Analytics/app/build/test-results/consumerRoundtripTest/`, and
`ARES-Analytics/app/build/consumer-roundtrip-evidence/`. The latter includes bounded operation
logs and actual nested robot/simulator JUnit reports before JUnit deletes temporary projects.
CI path classifier/boundary tests passed: 25 tests. CI wiring is reviewed locally; GitHub jobs
have not been run for this unpublished checkpoint. Final execution results are recorded below.

| Check | Observed result |
| --- | --- |
| Full `:app:consumerRoundtripTest`, plus the fast-failure driver regression | Passed in 6m 4s: all four integration scenarios and the driver regression; zero failures, errors, or skips. This run preceded the additional check-only manifest assertions below. |
| Nested BioBuzz consumer | 46 robot/simulator tests passed. |
| Nested generic FTC consumer | 27 robot/simulator tests passed, including the saved-deadband distinction. |
| Nested recovery consumers | 45 tests passed after cancellation/retry and 45 after partial-write failure/retry. These reuse the same BioBuzz suite; they are scenario executions, not 90 distinct tests. |
| Focused Studio regressions | All six passed: ConsumerRoundtripSupportTest (1), ProjectGenerationLifecycleTest (2), ProjectRoundtripIntegrationTest (3). Both recovery scenarios also passed again in this run. |
| Final check-only recovery oracle | Passed in the final 2m 31s focused run: the verifier rejected the incomplete manifest without preparation, passed after recovery, and the full build passed all 45 nested robot/simulator tests. Zero failures, errors, or skips in the outer scenario or nested tests. |
| CI classifier and boundary regressions | All 25 passed; existing scope propagation covers app/shared/runtime/generator changes. |
| Source policy | Passed: canonical guidance, local Markdown links, maintainability ledger (zero violations), release versions and source/artifact identity. |

The first run's reports and nested evidence were copied into
`build/consumer-review/first-run/` before the focused rerun so subsequent Gradle tasks cannot
overwrite that evidence. The elapsed build time includes compilation and nested Gradle operations;
it is not a robot loop-time measurement.

`recovery-final.log` and `recovery-preparation-prerequisite-run/` retain the six focused regressions
and recovery rerun. Inspection of that run showed verification was still invoking its preparation
prerequisite. That run therefore supports lifecycle/recovery only, not check-only rejection. The
final check-only evidence is kept separately in `check-only-final.log` and the final
`write-recovery/verify-existing-*.log` files. The focused oracle excludes preparation; it does not
alter normal consumer build behavior.

## Scope limits

These are headless Windows service/consumer checks, not a rendered Studio workflow, Linux/macOS
execution, target-controller validation, or performance benchmark. No physical robot was connected.
Schema values, compiled generated geometry/autonomous metadata, selected simulated commands,
extension execution, generated-file determinism, and recovery are distinct assertions; this does not
prove every saved tuning/safety value's physical effect. In particular, the edited feedback timeout
and heading gain are preserved/compiled but not independently exercised at their behavioral limits.

The intermediate-write failure is real CLI output followed by a blocked manifest write. Cancellation
is deterministic at Gradle task completion after the real CLI writes outputs; it does not establish
arbitrary interruption inside every generator write or crash durability. No new broad audit follows
automatically. Native UI and hardware checkpoints remain separate from this local integration.

The audit ledger preserves the submitted records as history and fingerprints this batch's reviewed
delta. Seven unrelated stale identities and five missing historical paths predate this peer review;
they are left outside this bounded scope. No repository-wide clean-audit claim is made.
