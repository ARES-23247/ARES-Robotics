# Drivebase save ownership checkpoint — 2026-09-23

Base `786074a44159de102c5339cee5ecce7fbbf061a4`; accepted source is the commit containing this
report. Local only, with released ARESLib 19.1.4 and Studio 7.0.64 dependency mode. No library,
generator, schema, robot runtime, release version, archive or CI workflow was changed.

## Accepted behavior

- **High impact within authoring:** a save completing after another edit used to replace the later
  draft and mark it clean. The regression reproduced a 0.60 m draft being replaced by the earlier
  0.50 m save. Completed saves now update the actual saved document and project revision while
  retaining later edits, dirty state and validation. A fresh review can save the retained draft.
- A delayed save result or failure no longer overwrites a subsequent completed reload. Obsolete
  reviews are invalidated when their saved base changes; existing token/hash/revision checks remain.
- Save attempts are serialized. Repeated confirmation of the same in-flight review is coalesced;
  the regression proves one session save dispatch instead of two. A different queued review still
  requires a valid current base and can be rejected for fresh review. Editing remains available.
- Cancellation propagates without publishing an application-error banner. Completed blocking IO
  is not claimed to be undone by cancellation.
- History checkpointing remains ancillary to the actual save. It still runs when a successful
  result has been superseded in the editor. Its delayed failure cannot replace a newer status;
  an immediate failure for the current save still produces the warning. These are workflow fixes,
  not established robot-output defects or measured loop-time improvements.

The unconditional result update also exists in released baseline
`9426ee3c86f331910b2ce1656e33c774506a44e1`. The pre-batch tests independently reproduce the other
callback defects. The accepted helper keeps save IO, publication and history handling together;
the ViewModel is 687 lines and helper 229, below unchanged source-size limits.

## Independent review and validation

Codex rejected the first worker patch: its completion counter still allowed a duplicate stale-save
failure, and its state updater depended on an external mutable publication flag. The worker revised
the patch to serialize saves and invalidate obsolete reviews. Codex then corrected immediate history
callback ordering, rethrew cancellation, and preserved newer validation errors against a delayed
failure. The saved status is published before starting its optional history callback.

Test corrections included actual Kotlin DTO/import/matcher types, a canonical FTC mecanum fixture
with tuning profiles, immediate coroutine entry, barriers outside the session lock, and joins before
asserting old callbacks. The final invalid-input scenario edits a visible geometry field to temporary
zero width and requests review; it does not rely on a UI-inaccessible FRC type in an FTC selector.
Temporary projects, scopes and barriers are owned and cleaned by the fixtures.

- Initial baseline variant: five tests, two intended failures. Its two passing reload tests had weak
  ordering/assertions and were replaced; ordinary save succeeded.
- Strengthened baseline: eight tests, six intended failures. Immediate history-warning control passed.
  The duplicate test initially passed because callback ordering could clear its error; an explicit
  dispatch assertion then failed separately, expected one session save call versus actual two.
- **Final combined suite: 116 passed, zero failures/errors/skips.** Includes eight new save scenarios,
  prior Drivebase discard/load regressions, Superstructure, RobotStudio and ProjectSession checks.
  Command with JDK 17:

  ```powershell
  ./ARES-Analytics/gradlew.bat -p ARES-Analytics :app:test --tests '*Superstructure*Test' --tests '*Drivebase*Test' --tests '*RobotStudio*Test' --tests '*ProjectSessionTest' --no-parallel --console=plain
  ```

Real temporary repository/session saves verify canonical disk content, later-draft preservation,
new revision, obsolete-review invalidation and a second reviewed save. Controlled IO barriers withhold
results or failures; they do not claim measured disk latency. Baseline variants and final test source
identities are retained separately. Source policy, staged whitespace and scoped ledger checks pass.

Source reachability was checked through `DrivebaseBuilderScreen`, review/geometry controls and
`confirmSave`: save does not set loading, so step chips and fields remain enabled. Workspace ownership
is keyed by project identity/path; navigation within the workspace does not itself cancel the model.
No native window or physical robot was launched in this batch. Earlier native metadata/readiness
evidence remains scoped to [the prior checkpoint](AGY_FAILURE_RECOVERY_CHECKPOINT.md).

Two worker-report claims were rejected: session-mode reads/writes are serialized by the same lock,
and coroutine cancellation does not automatically interrupt blocking filesystem operations or guarantee
rollback. This audit does not establish universal multi-file atomicity or fault recovery. Delayed UI
delivery after actual persistence is distinct from simultaneous physical writes.

Existing changed-part CI selects `:app:test` and `:app:consumerRoundtripTest` for these app paths.
The regenerated source-size inventory additionally selects unchanged shared/gateway scopes; their
full suites were not rerun locally. The generated FTC roundtrip from `1612a01a4` remains applicable
to unchanged generator/library/runtime contracts. No remote CI or release was triggered.

## Delegation and evidence

Three external AGY workers used `gemini-3.8-flash-high`, effort `high`, isolated from the same base:

| Role / worktree suffix | Conversation | Turns | Successful reads / distinct paths |
| --- | --- | ---: | ---: |
| `gemini-drivebase-save-ownership` | `0afdfef1-da9e-4342-97a3-2087a7db1417` | 2 | 26 / 8 |
| `gemini-drivebase-save-ui` | `0fbab10f-ade9-4043-bf55-64687f686ef4` | 1 | 17 / 9 |
| `gemini-drivebase-save-storage` | `3e4baa07-1fbd-4ed2-9844-b32645894c5f` | 1 | 26 / 10 |

All workers ended; ownership checks passed. Worker prompts, correction, source edits, reports and
streams remain under each `.codex-validation/<suffix>/build/gemini-audit/`. No worker shell command,
permission bypass or native Codex subagent was used. Initial sandboxed model discovery failed to use
normal AGY application state; discovery through the coordinator's reviewed permission flow succeeded.
No personal permission policy was broadened.

Coordinator evidence is `build/agy-save-ownership/` in `.codex-validation/agy-audit-coordination`:
source identity, released method snapshot, baseline logs/JUnit and test variants, `final-junit/`,
`final-test-summary.json`, `combined-candidate-tests.log`, `independent-disposition.json`, worker
counters, policy and integration evidence. Do not overwrite accepted files with worker copies.

Read limits again exceeded the advisory brief. Parallel reviews provided useful checks but needed
corrections; recorded read/token counters do not establish faster completion or Codex quota savings.
No new allocation, sensor latency, loop-budget or controller performance result is claimed.

This closes the demonstrated pending-save workflow defects. The goal remains active pending a
completion assessment across the accepted practical-priority batches and their remaining findings.
Do that assessment next; use concrete unresolved evidence to select further work, not a fresh broad
file pass. Hardware remains unavailable; native unhandled-inspection-exception presentation and
unmeasured IO/GUI timings remain explicit limitations, not implied passes.
