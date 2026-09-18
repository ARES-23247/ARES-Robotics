# Local review of the round-trip integration submission

Date: 2026-09-17. Reviewed submission: `e491ac3157fd1b0583cf9cea12e63cb4ad3491eb`,
parent `d512d64fe8cc955003ccd82b06c455132f32e1a5`. Review worktree:
`.codex-validation/reviewed-roundtrip-integration`, branch `codex/reviewed-roundtrip-integration`.
The source checkout was clean; its branch is `codex/project-roundtrip-integration`.
This is a local review/fix checkpoint, not a release or completion of the earlier consumer journey.
No GitHub writes, release/version changes, new tasks, or subagents were used.

## Scope and disposition

Reviewed all six submitted file changes together: archive extraction, generation cancellation,
their tests, maintainability data, and the submitted report. Traced extraction callers (currently
tests only), process ownership/replacement/stop, and wrapper/dependency preflight. The archive
file and new/changed tests were read in full; ProjectBuildService was reviewed only across the
generation and process-lifecycle boundary. This does not grant full-file credit to its build,
verification-report, and transient-retry paths. Unchanged project/session/codegen dependencies
were used and selectively traced, not credited as newly audited.

The submission's cancellation handlers and useful BioBuzz snapshot, USER-OWNED source,
determinism, stale-output, and transaction-recovery fixtures are retained. The original
[submission report](PROJECT_ROUNDTRIP_INTEGRATION_REVIEW.md) is preserved with an explicit
correction notice; its broad claims must not be treated as independently verified readiness.

## Findings and corrections

| Finding | Origin | Correction / evidence |
| --- | --- | --- |
| A late invalid entry or missing identity left files in the final extraction directory, preventing retry. Existing empty directories became polluted. | New extractor in e491ac315 | Extract into a unique sibling staging directory, validate, then rename without replacement. Failure removes only owned staging. Regressions cover rejection, retry, existing empty directories, and destination files created by another writer. |
| Cancellation did not stop archive reads/writes; the cancelled call left a project behind. | New extractor | Check cancellation between entries and buffer reads. A controlled input-stream boundary cancels after IO begins, proving reads stop and no partial destination/staging remains. |
| Absolute/repeated-separator paths were normalized into relative paths; case aliases could overwrite files on Windows. Directory payloads bypassed regular-file size checks. | New extractor | Reject ambiguous/nonportable paths and duplicate/case-colliding entries, use create-new file writes, and reject nonempty directory payloads. |
| Streaming ZIP parsing accepted EOF between entries without the central directory, allowing an incomplete archive to appear successful. | New extractor | Validate the ZIP directory and require every declared entry to be consumed. A JDK probe demonstrated streaming-parser acceptance and ZipFile rejection; regression covers truncation after the identity entry. |
| Starter-generation preflight ran outside error handling: a missing wrapper ended the job while leaving observable generation RUNNING. | Pre-existing in d512d64fe, unchanged by submission | Move preflight into the guarded operation. The regression failed with expected FAILED / actual RUNNING, then checks failure diagnostics and successful retry. |
| The cancellation test called runManagedProcessForTest and never reached either new cancellation handler. The failure test edited completed output instead of interrupting generation. | Submission validation gap | Replace the cancellation fixture with actual generateAresProject/applySubsystemStarters entrypoints and controlled wrappers. Wait for an intermediate file and a live PID before cancellation; verify exact handler diagnostics, child exit, unchanged canonical input, and cleared running state. Rename the manual stale-output test and strengthen its canonical-content assertion. |

These are correctness/recovery changes, not measured performance optimizations. Buffer reuse and
reuse of the existing wrapper normalization helper avoid needless duplication; no timing or
allocation improvement is claimed. The reported general cancellation defect was not reproduced:
the older stop path already finalizes generation. The submitted handlers usefully finalize state
before ownership release; the new tests now actually exercise them.

## Reproduction and validation

JDK 17, Windows desktop JVM. Unchanged ARESLib source tree:
`4161ce50ae762d9ba02cd65663c6e9a40da6afbf`. Reused the prior validated local source-validation
candidate `19.1.3-rc.roundtrip.dbb5b9f.1`; no republishing:

```text
file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-project-roundtrip/ARESLib-Kotlin/build/release-repository
```

From `ARES-Analytics`, all commands use `gradlew.bat`, that `-ParesVersion`, that absolute
`-ParesRepository`, and `--no-parallel --console=plain`:

- Baseline `:app:test --tests '*ProjectArchiveExporterTest'`: 11 tests, five expected failures.
  The only production adaptation before this run was an internal stream-opening seam with the
  original File.inputStream default; extraction behavior was unchanged.
- Baseline `:app:test --tests '*ProjectGenerationLifecycleTest'`: two tests, one expected
  preflight-state failure; both actual cancellation entrypoints already passed.
- Corrected scoped run: 166 tests, zero failures/errors/skips. Filters were
  `com.ares.analytics.service.versioncontrol.*`,
  `com.ares.analytics.service.project.persistence.*`, `*ProjectRoundtripIntegrationTest`,
  `*ProjectGenerationLifecycleTest`, `*ProjectBuildServiceTest`, `*ProjectSessionTest`,
  `*RobotProjectTemplateServiceTest`, and `*BiobuzzTemplateAuthoringTest`.
- After the final ZIP completeness guard: `:app:test --tests '*ProjectArchiveExporterTest'
  --tests '*ProjectRoundtripIntegrationTest' --tests '*ProjectGenerationLifecycleTest'`:
  21 tests, zero failures/errors/skips (16 archive, three round-trip, two generation lifecycle).
  This rerun includes one additional archive regression; it overlaps the scoped run above.
- Source policy passed: shared agent guidance, links in 191 current Markdown documents,
  maintainability data (1,111 production files, no violations), and local release alignment.
  CI path classification selects the app test scope (and broader Studio/package scopes);
  `ci-scope.json` records the routing. Those additional CI/release jobs were not run locally.

Evidence is retained locally under
`.codex-validation/reviewed-roundtrip-integration/build/integration-review/`: baseline logs and
XML directories, `studio-final.log`, `scoped-xml/`, `scoped-summary.json`, the truncated ZIP probe,
and `archive-completeness-final.log`. These are local artifacts, not committed test reports.

The normal Studio app CI scope already invokes `:app:test`, including these non-opt-in
regressions. No CI redesign or library/robot dependency change was needed. Earlier exact-tree
library and consumer evidence remains in [the prior review](PROJECT_ROUNDTRIP_REVIEW.md);
those broad suites were not rerun for these Studio-only changes.

## Limits and next action

The extractor is a service primitive with no production import-screen caller. Archive identity
presence is not semantic project validation; ProjectSession/codegen still own that validation.
This is not an authenticity verifier or a sandbox for executing project build scripts.
Cancellation observed before publication cleans staging. Cancellation racing after the final
rename can leave a complete project even if coroutine delivery is cancelled; no partial project
is published. Process crash/power loss can leave staging; crash durability and hostile concurrent
filesystem manipulation were not tested. Cleanup failure is surfaced with its staging location.
The 100 MiB expanded-file limit was executed; aggregate 1 GiB and 50,000-entry limits were
inspected but not exhaustively exercised. POSIX/macOS filesystem behavior was not run here.

The original BioBuzz round-trip fixture invokes the CLI in-process, does not compile/run its
reopened consumer, and does not include a generic starter. Transaction recovery uses a prepared
manifest fixture. Controlled wrapper tests prove real service lifecycle handling, not interrupted
real generator execution or a whole-project transaction guarantee. No native Studio interaction,
robot IO, target hardware, performance baseline, or release readiness is claimed for this batch.

The next action is the bounded [consumer round-trip prompt](NEXT_ROUNDTRIP_CONSUMER_PROMPT.md):
complete BioBuzz plus generic FTC export/reopen/generate/compile/simulated-consumer journeys
and actual generator failure/cancellation recovery. No new broad audit should start automatically.
