# Rendered superstructure checkpoint — 2026-09-23

Local base `1612a01a400516d4a0831724e59c6a63507c5a53`; accepted source is in the commit
containing this report. Studio 7.0.64, released ARESLib 19.1.4, isolated Lightbot 3.0.66 fixture.
No library, generator, schema, robot runtime, release version or archive changes.

## Findings and acceptance

- **High impact within the operator workflow:** header **Review & save** created a review but left
  the user on Postures with the button disabled. It now selects Verification & Save after successful
  validation. Invalid drafts remain on their current step with an error.
- **High impact within the operator workflow:** **Discard Changes** and dirty header Reload requested
  a discard but no UI rendered the request. The old error told the user to discard before reloading.
  A root confirmation dialog now handles the existing pending selection. Cancel preserves the draft
  and review; Discard uses existing reload/selection logic, clearing the obsolete review.
- Both defects predate this campaign: corresponding model behavior exists at released baseline
  `9426ee3c86f331910b2ce1656e33c774506a44e1`. No robot output defect was established here.
- Rejected the worker's proposed verification gate restriction: `ProjectExecutionCoordinator.execute`
  force-reloads the project and rejects league/model errors before dispatch. `VERIFY_AND_BUILD` routes
  to build/verification, not deployment. The difference between toolbar and diagnostic-page gating
  did not demonstrate a high-impact defect. No speculative gate change was integrated. This is scoped
  source evidence, not proof about arbitrary build scripts or untested deployment behavior.

Codex reviewed the worker diff and callers, removed an unnecessary empty-ID fallback, strengthened
async test completion and corrected a review fixture. Prior async generations, revision/hash tokens,
completed disk writes and runtime authorization rules remain intact. Original worker patches/reports
remain in their isolated worktrees and must not overwrite the accepted coordinator version.

## Validation

- Native baseline captures reproduce both defects (`captures/capture-006.png`, `capture-009.png`).
  Four baseline regressions: two intended assertions fail, two existing-flow controls pass.
- The first integrated run passed 69/70; the remaining test used an invalid added-posture review
  fixture. It now edits valid metadata and asserts a real review before checking Cancel. Baseline
  and final test variants are preserved separately; their source is not claimed identical.
- Final focused suite: **70 passed, zero failures/errors/skips** using
  `:app:test --tests '*Superstructure*Test' --tests '*RobotStudio*Test' --tests '*ProjectSessionTest'`.
  Includes existing revision/operation ownership regressions plus four review/discard scenarios.
- Actual corrected window: create draft -> header Review & save selects step 3 -> Discard opens
  confirmation -> Cancel preserves the reviewed draft -> Confirm & Save creates canonical document
  and immutable history -> readiness tree changes to Ready -> clean Reload preserves saved data ->
  create another draft -> dirty header Reload prompts -> confirmed Discard restores saved coordinator.
  The discarded coordinator is absent on disk. One early input sequence did not create the second
  draft; it was repeated only after capturing the settled dialog. No success was inferred from clicks.
- Original 12 canonical fixture files remain byte-identical. Only the explicitly saved coordinator
  and its history entry were added. No real user project/home was selected; no robot/simulator linked.
- Both exact native windows were captured at 1424x861 and reported settled visible state. Owned Studio
  PIDs 7664 and 16504, control ports 49325/49326, Gradle run sessions and runtime snapshots exited
  normally. Pre-existing daemon 38960 and unrelated processes were preserved.

Evidence: coordinator `build/agy-rendered-readiness/` contains fixture/archive identity, source
hashes (`candidate-source.json`), baseline/final JUnit and logs, original/corrected captures,
canonical hashes/delta, process manifests and shutdown proof. Native inputs used the existing
loopback AWT/Skia test controller. No process/state injection substituted for visible controls.

Existing changed-part CI selects Analytics app tests and relevant consumer integration. The generic
FTC roundtrip from `1612a01a4` remains applicable to unchanged generator/library/runtime contracts;
it is reused rather than rerun for these UI/model-step changes. Local source policy and all six guidance tests pass. The initial sandboxed guidance test run
could not create/clean temporary fixtures; its log is retained, and the normal reviewed rerun passed.
The regenerated size inventory also conservatively selects unchanged shared/gateway scopes; those
full suites were not rerun locally. Scope evidence is stored beside this batch. No GitHub CI or
release was triggered.

## Limits and continuation

This closes the selected rendered review/save/discard/readiness-refresh journey. It does not validate
physical hardware, robot timing, every GUI path, disk failure during discard, or native presentation
of a deliberately failed readiness inspection. Those are distinct checkpoints, not implied passes.
No new robot-performance measurement or optimization is claimed.

The [delegation efficiency assessment](AGY_DELEGATION_EFFICIENCY.md) records the three parallel
workers, measured repeated reads and counter limitations. The active autonomous goal remains local.
Next, reassess only concrete remaining practical leads against the accepted checkpoint; prioritize
an actual failed workflow or measured bottleneck and close the goal if none remain actionable.
Do not reopen the historical all-files audit or launch workers merely to fill slots.
