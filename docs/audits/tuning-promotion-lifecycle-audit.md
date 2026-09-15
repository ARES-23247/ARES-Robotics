# Tuning promotion lifecycle and repeated work audit

Pass 237 moves from pure tuning value resolution to asynchronous Studio review/promotion and
immutable-state row resolution. The preceding pass finished clean at
`fa6dc5ab29f702ddc00ed3b6e669121fee8ab513`. This pass validates Studio tree `df2572d37e7dfd2015d293b1ca3efd32f41e156e`
against unchanged local ARESLib candidate `17.0.41-rc.a559b2f1934c` and library tree
`a559b2f1934c9317ec4b67b2dd812d867f91f6df`. No library source, version, archive or artifact bytes changed.

## Findings and fixes

1. **A completed save replaced whichever project was currently open.** The old IO completion
   copied its captured profile list into the current state without checking project identity
   or reload generation. Success and failure now update only the original workspace snapshot.
   A save already executing can finish for its original project; it cannot overwrite the new
   project's profiles, drafts, errors or status. Tests hold a real canonical replacement,
   load another project, then verify both final files and the complete newer state.
2. **Save completion erased newer student work.** The old handler unconditionally cleared all
   proposals, provenance, reviewer and summary fields. It now updates the committed profile
   by stable UID within the current list and clears only an unchanged reviewed draft. Edits
   made during saving and drafts for another selected profile survive. Reviews are invalidated
   when canonical profiles change so inheritance-sensitive diffs must be reviewed again.
3. **Late checkpoint errors appeared in another project's status.** The committed project
   still receives its checkpoint. Failure notifications apply only while the original
   completion context and status remain current. A positive test verifies that a current
   checkpoint failure is still reported without undoing the successful canonical save.
4. **Evidence hashing blocked the intent caller and stale reviews could return after edits.**
   Review construction, file evidence validation and token hashing now run on an IO dispatcher.
   Results require both matching review inputs and the latest review request generation.
   A controlled old evidence check cannot restore a stale diff after a student edit, and a
   later successful review wins when an older check finishes last. Telemetry-only changes
   do not invalidate a review's source inputs.
5. **Queued confirmations read their context too late and duplicate requests repeated work.**
   Confirmation now captures its project and reviewed inputs before coroutine launch, checks
   them again before persistence, and admits only one in-flight promotion. A test queues
   confirmation, opens a copied project with an identical review token, and verifies neither
   file is written by the obsolete request. Duplicate clicks produce exactly one save and
   checkpoint. Failed and cancelled queued requests release the guard, allowing a valid retry.
   Coroutine cancellation is propagated instead of being reported as a failed user operation.

## Efficiency and validation

TuningState now resolves its selected profile and sorted/validated rows once per immutable
state instance. GainTuningPanel reads rows for both grouping and the empty-state check; those
reads reuse the same resolution. The regression counts catalog accesses rather than relying
only on list identity: the first single-parameter resolution made five accesses, and 64 further
reads made zero additional accesses. Changed proposal, canonical profile, selected profile,
numeric observation and typed-observation snapshots resolve their own current values. This is
evidence of removed repeated work, not a measured UI frame-time or robot-loop claim.

All eight initial regression methods failed their intended behavior assertions. The final
15-method suite strengthens the row-cache assertion with input counting and adds normal-save,
duplicate-confirmation, retry, current-checkpoint-error, latest-review, queued-context,
cancellation and invalid-token compatibility coverage. Fixtures own their latches, queued
runnables, coroutine jobs, NT4 clients, databases and temporary directories.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,090 | 6 |

The final focused run has **65 passing tests** across lifecycle, external proposals, persistence,
authoring and value resolution. Full Studio validation has **2,139 passing results**, zero
failures/errors and six unchanged opt-in skips. App tests executed; unchanged dependencies
include Gradle up-to-date/cache results. Focused results are not counted twice. All 410 library
candidate files were rehashed. The unchanged library/robot candidate validation was retained
instead of rerunning unaffected suites. Repository policy is verified separately.

## Coverage and limits

The ledger accounts for 3,038 tracked files: 1,354 reviewed, 185 partially reviewed and
1,499 pending, with zero stale or orphaned records. These are scoped review and validation
counts, not universal executable test coverage.

TuningViewModel remains partial for live push/nonces/acknowledgements, reconnection, telemetry
sampling and exhaustive load scheduler interleavings. This pass covers review/promotion,
checkpoint completion, affected profile selection and state row resolution. The panel remains
partial for its broader editor/project-switch lifecycle; its two row consumers were inspected
without editing UI code. No rendered window, physical robot, hardware latency, remote CI,
push, merge, release or deployment was performed. The overall monorepo audit remains active.

Machine-local evidence: `ARESLib-Kotlin/build/audit-pass237-verified-evidence/`, including the
five-case and expanded eight-case failing baselines, focused/full XML and logs, measured row
accesses, policy results and summary.
