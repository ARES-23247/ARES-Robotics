# AGY improvement campaign: resume state

Updated 2026-09-23. Goal: [autonomous local improvement](AGY_IMPROVEMENT_GOAL.md), active.
Baseline: protected release merge `9426ee3c86f331910b2ce1656e33c774506a44e1` (Studio 7.0.64,
ARESLib 19.1.4); saved-workflow commit `674901bb3393d49c9b4ce6fb95c4999f82def77f`.
New work is local only. Tuning checkpoint: `56c53cc60568855e00ca1c39c2f74c06996899e6`.
The commit containing this state adds the rendered operator journey and guided-experiment correction.

| Role | Local branch/worktree | Ownership |
| --- | --- | --- |
| Coordinator | `codex/agy-audit-coordination`, `.codex-validation/agy-audit-coordination` | Guidance, goal/state, shared ledger, independent review, build scheduling and integration |
| Operator worker | `codex/gemini-operator-audit`, `.codex-validation/gemini-operator-audit` | Tuning screen/components, Lightbot tuning OpMode/facade, new OperatorAudit tests, individual report |
| Lifecycle worker | `codex/gemini-tuning-lifecycle-audit`, `.codex-validation/gemini-tuning-lifecycle-audit` | Tuning/SysId viewmodels and signal lifecycle, focused lifecycle tests, individual report |
| Transport worker | `codex/gemini-tuning-transport-audit`, `.codex-validation/gemini-tuning-transport-audit` | NT4 tuning publication and replay/target boundary regressions |
| Guided worker | `codex/gemini-guided-experiment-audit`, `.codex-validation/gemini-guided-experiment-audit` | Guided experiment asynchronous context and focused lifecycle regression |
| Persistence worker | `codex/gemini-profile-persistence-audit`, `.codex-validation/gemini-profile-persistence-audit` | Bounded profile load/promotion failure review |

All workers used `gemini-3.8-flash-high`, effort `high`, and returned substantive final
responses. The two latest workers ran in parallel with coordinator UI validation. No worker is
running or awaiting a correction. The user authorized up to three parallel
AGY workers. The reusable workflow records verified manifests, bounded exploration and disjoint
ownership; Codex owns tests and acceptance. Do not copy worker files back over the coordinator:
the accepted versions contain independent test/source corrections and an extracted transport helper.

Final main conversations (retain these identifiers with the local evidence):

- Operator: `12230138-3694-4054-be01-afeedeb3913b`, three turns.
- Lifecycle: `642cf6fe-3cac-475a-90a5-6d6f32f7e8ec`, three turns.
- Transport: `56e400c9-d365-4dc2-840a-eaa96a4ec9d2`, two turns.
- Guided: `7f5acee9-b879-46f3-bfd9-256f5e09ca26`, two turns.
- Persistence: `82f7fa7c-fe86-4588-a4e6-677943004aa5`, one turn.

Exact assignments, corrections and CLI streams remain in each worker's ignored
`build/gemini-audit/`. Earlier shell escalation and unrelated path reads remained denied. The
working headless arrangement uses authorized source-file tools only; the coordinator runs commands
through its normal permission flow. No personal permissions were broadened. An earlier empty
`SUCCESS` result was a print timeout with work still in progress, not completion; the same
conversation was continued and later returned substantive results.

## Closed tuning batch

The [independent checkpoint report](AGY_TUNING_CHECKPOINT.md) controls actual acceptance claims.
Accepted changes discard aborted SysId data, expose failed disarm publication, bind tuning actions
and acknowledgements to the live target, guard suspended NT4 publication against replay/target
changes, and wire FTC's existing neutral-output hold into its tuning context. Library safety policy,
versions and release archives remain unchanged. Unsafe capability-check removal and stale-operation
STOP writes were rejected, along with speculative/cosmetic changes and inadequate tests.

Evidence retained under coordinator `build/agy-audit/`:

- `baseline-abort-reproduction/`: the new regression failed on baseline at the intended assertion.
- `collector-correction/`: 31 passing collector tests, included in the combined suite below.
- `combined-studio-corrected/`: 154 passing Studio tests; initial test-compilation failure log retained.
- `ftc-wiring-baseline/`: 27 tests with one intended neutral-hold assertion failure.
- `ftc-wiring-corrected/`: all 27 pass with the facade correction.
- `generated-consumer-integration/`: one outer test passes; fresh generated operations 3 and 4
  each pass 32 nested TeamCode/simulator tests in the app consumer evidence directory.
- `transport-extraction-verified/`: all 13 wire/replay tests pass after extraction.
- `source-policy-final.log` and `agent-guidance-tests.log`: policy and six guidance tests pass.
- `ci-scopes.json`: FTC/Studio integration selected; the size inventory conservatively also selects
  unchanged Analytics shared/gateway. Those full suites were not rerun locally; no remote CI was run.
- `worker-efficiency-final.json`: deduplicated final delegation diagnostics and cumulative AGY counters.

The source-size inventory was regenerated without weakening its limits. Scoped ledger records bind
reviewed changes to source content and preserve prior evidence. Test counts do not imply complete
file review. AGY read counts expose avoidable exploration, but do not establish a Codex quota saving.

## Continue safely

The [operator/guided checkpoint](AGY_OPERATOR_GUIDED_CHECKPOINT.md) closes the selected rendered
tuning journey: released Lightbot setup, simulator launch, staged LIVE_SAFE gain, confirmed arm,
matching robot acknowledgement, explicit disarm, unchanged canonical files/local overlay, active
drive Stop with all four motor powers ending at zero, and graceful owned-process/snapshot cleanup.
It used Studio source at the tuning checkpoint and the bundled released Lightbot 3.0.66 with
ARESLib 19.1.4. The archive does not contain the local neutral-hold facade fix; separate prior
constructor evidence still covers that correction. Physical electrical behavior is not verified.

The guided worker's pre-existing stale-evaluation bug was reproduced against unchanged production:
three intended failures and a passing positive control. After independent correction, all 57
focused guided/profile tests passed with no skips. Obsolete work cannot stage a proposal or overwrite
a successor context, and cancellation does not surface a false operator error. Completed disk work
is not rolled back merely by changing context. Restored a comparison report accidentally dropped by
the worker; corrected its candidate, coroutine and DTO test assumptions. The profile review found no
production defect; its duplicate/incomplete new tests were rejected and existing tests were reused.

Evidence is in coordinator `build/agy-ui-check/`: UI manifest/captures, read-only NT4 observations,
canonical before/after hashes, local overlay, shutdown proof, baseline/fixed JUnit and logs,
worker efficiency metrics and source-policy/CI scope results. Studio PID 38028, simulator PID 28048,
their wrappers, both runtime snapshots and ports 49323/5810 were verified gone. Other processes were
preserved. No GUI or AGY worker from this batch remains running.

Keep the goal active. Before another worker assignment, use this checkpoint and verify the branch,
dirty state and process ownership. The next concrete review lead is whether a supported workspace
project-path change can retain a guided model keyed only by workspace ID. Establish the actual
user flow and reproduce before changing code; this is not yet a confirmed defect.
Any following parallel batch must address distinct demonstrated risks with verified path manifests,
disjoint ownership and bounded reads. Preserve the retained worker worktrees/evidence until no
longer needed. Do not restart the audit-every-file campaign.

Physical neutralization, target-controller timing and unselected GUI paths remain unverified.
The right-hand calibration selector's narrow labels are a deferred usability issue. The read
budget was exceeded by one worker; use tighter follow-up briefs and inspect completed patches.
Tests here are desktop JVM/mock/simulated IO checks, not robot timing measurements. A queued packet
cannot be recalled by a later replay/target change; robot authorization and leases remain required.

The earlier release is historical: PR #107, candidate `35799612841`, promotion `35825999923` and
Studio 7.0.64 completed before this campaign. **No new pushes, PRs, protected-main merges, releases
or deployments are authorized.**
