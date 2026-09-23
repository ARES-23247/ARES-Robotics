# Local AGY improvement campaign completion — 2026-09-23

Assessment source: `8292ddea97473c9b9a1a70f2bb1c99dbaffe00a2`. The commit containing this report
adds documentation only. The original [saved goal](AGY_IMPROVEMENT_GOAL.md) is preserved; this closes
its identified practical-priority batches, not the historical audit-every-file campaign. No assertion
of whole-repository correctness, physical robot readiness or measured Codex quota savings follows.

## Requirement-by-requirement decision

| Goal requirement | Inspected evidence | Disposition |
| --- | --- | --- |
| Parallel external AGY, Gemini 3.8 Flash High, high effort; Codex supervises | Retained assignments, CLI streams, model discovery and conversation results; final independent report-only reviewers listed below | Complete. Two or three useful isolated workers per batch, no native Codex subagents. |
| Disjoint ownership, independent review and correction | Worker changed-path checks; accepted/rejected patch dispositions in each checkpoint; baseline and corrected test variants | Complete. Worker claims and completion labels did not determine acceptance. |
| Local-only work; preserve unrelated work and processes | Local feature-branch checkpoint chain, clean root/coordinator at assessment; owned-window shutdown manifests and prior process records | Complete. No push, PR, protected-main merge, release or deployment in this campaign. Worker checkouts/evidence remain preserved. |
| Initial Studio/Lightbot tuning, acknowledgement, disconnect, disarm and shutdown | Actual JUnit cases for reconnect/disconnect, target/replay boundaries, lease ownership, disarm failure and aborted samples; native NT4 observer, captures and stop/shutdown evidence | Complete for selected desktop/simulated scenarios. Hardware boundary below remains unverified. |
| Fix demonstrated practical defects; distinguish origins and reject speculation | Pre-existing baseline failures, released-method comparisons and explicit rejected findings across linked checkpoints | Complete. No outstanding demonstrated high-impact defect within selected scopes. |
| Appropriate focused and consumer checks | Parsed preserved JUnit for 16 selected final/consumer evidence bundles; no failures/errors/skips. Current save/editor suite 116; generated consumer outer plus nested tests separately inspected | Complete. Counts overlap and are not a repository coverage metric. Unchanged library/runtime contracts reuse prior evidence. |
| Evidence remains applicable to current source | All 47 campaign-changed source/guidance records match normalized content fingerprints; selected transport, tuning and workspace/session files compared byte-for-byte with their validated Git sources | Complete scoped identity check. Partial reviews remain partial; fingerprints alone do not prove behavior. |
| Meaningful regressions remain in changed-part CI | Existing Analytics job selects `:app:test :app:consumerRoundtripTest`; FTC checks cover the season facade. Campaign changed-path classification retained | Complete without CI redesign. No remote CI was run. |
| Reproducible checkpoints before expansion; scoped ledger preservation | Ten local setup/implementation commits from `674901bb3` through `8292ddea9`; earlier records preserved under named prior-record keys | Complete. New source/ledger evidence is bound to each accepted checkpoint. |
| Reusable, tracked AGY workflow and future setup | Canonical `parallel-agy-audit.md`, audit workflow, saved goal/state; model/effort, ownership, prompts/logs, review and permission limits; retained six passing guidance tests | Complete. No blanket permission rules, credential changes or denied-action bypass. |
| Optimize measured bottlenecks; assess delegation efficiency honestly | Worker read/repeat/correction counters; duplicate-save regression observes two baseline dispatches versus one accepted dispatch | Complete as scoped. No robot timing/allocation optimization or measured net quota saving is claimed. Speculative optimization remains deferred. |
| Continue until identified practical-priority work closes; document limits | Named next-action chain below and two independent closure reviews checked against source/results | Complete with explicit physical/native boundaries; no new broad audit is started. |

## Closed practical-priority chain

| Local checkpoint | Scope and disposition |
| --- | --- |
| `56c53cc60` | [Tuning lifecycle/transport](AGY_TUNING_CHECKPOINT.md): aborted-sample handling, request target ownership, disarm reporting and FTC neutral-hold wiring. Unsafe capability/STOP changes rejected. |
| `e5331cc7c` | [Operator journey and guided evaluation](AGY_OPERATOR_GUIDED_CHECKPOINT.md): rendered Lightbot live-tuning/stop/close journey and superseded evaluation fix. Persistence review found no additional defect; redundant test rejected. |
| `e93d927e8` | [Workspace context](AGY_WORKSPACE_CONTEXT_CHECKPOINT.md): same-ID folder teardown/model lifetime and stale shared-session selection. |
| `484f29b29` | [Session callers](AGY_SESSION_CALLERS_CHECKPOINT.md): Field/Drivebase load ownership; native same-ID folder switch supplies the earlier missing interaction evidence. |
| `fdf2c7e68` | [Project owners](AGY_PROJECT_OWNERS_CHECKPOINT.md): Identity, PathPlanner/RoutinePersistence and Subsystem refresh ownership. |
| `1612a01a4` | [Readiness/Superstructure](AGY_READINESS_SUPERSTRUCTURE_CHECKPOINT.md): inspection, runtime evidence and save/reload ownership. Synchronous/authorization callers did not demonstrate the same defect; unused helper cleanup deferred. |
| `85194cedf` | [Rendered Superstructure](AGY_RENDERED_SUPERSTRUCTURE_CHECKPOINT.md): review-tab routing and discard dialog fixed; review/cancel/save/reload/discard and readiness update observed. Speculative verification-gate change rejected. |
| `786074a44` | [Failure recovery](AGY_FAILURE_RECOVERY_CHECKPOINT.md): dirty drafts survive failed discard reload; late Drivebase read publication guarded. Native malformed-metadata diagnostics and restoration verified. |
| `8292ddea9` | [Drivebase pending saves](AGY_DRIVEBASE_SAVE_CHECKPOINT.md): later edits/validation preserved, obsolete reviews invalidated, saves serialized, repeated confirmations coalesced and callbacks tied to their operation. |

The completion review traced each named follow-up to its subsequent disposition. It found no
remaining demonstrated practical defect requiring another code batch. This is a finite assessment
of those selected findings, not a promise that future use cannot reveal other bugs.

## Authoritative evidence rechecked

Coordinator directory: `.codex-validation/agy-audit-coordination/build/agy-completion-assessment/`.

- `current-source-ledger.json` records current fingerprints, bounded review status and evidence for
  each changed source/guidance file. `evidence-reuse.json` records exact source comparisons: transport
  and SysId match `56c53cc60`; tuning and workspace/session implementation match `e93d927e8`.
- `junit-evidence-index.json` parses actual retained XML, not report PASS labels. The latest generated
  consumer bundle has one outer test and two generated projects, each with 25 TeamCode and seven
  simulator tests. These layers are not summed into an inflated independent-test count. Changes since
  that consumer checkpoint are confined to editor UI/models/tests; generator, schema, library, robot
  runtime and bundled archives are unchanged.
- All 39 operator manifest artifacts still match their SHA-256 values. Selected native captures were
  inspected again. The subscription-only observer records source 0.08, requested/current 0.09 and
  `1|1|APPLIED`; recorded arm transitions include false → true → false. Independent stop observations
  show all four motor powers nonzero before ending at zero with final OpMode state DISABLED.
- All 13 authored operator-fixture files are unchanged. The later metadata recovery restores all 12
  original canonical files exactly. Superstructure save adds only its canonical document and immutable
  history; its discarded draft is absent. Owned-run shutdown records identify exited processes,
  closed ports and removed snapshots. No new native app was launched for this completion assessment.
- `native-evidence-check.json`, final source policy, scoped ledger checks, whole-campaign CI scopes,
  worker counters and local integration identity accompany this report. Library source, release
  manifests and CI workflows have no campaign delta.

The two final external AGY reviewers ran independently from the assessment source:

| Reviewer | Conversation | Result / successful reads |
| --- | --- | --- |
| Tuning/operator/workspace | `0863f0ec-b7f3-44a9-9610-949ee3f97177` | Report only; 9 reads of 9 distinct paths; terminal |
| Editor/readiness follow-ups | `0a7727b6-32a9-4f11-ab7b-558b5f6b8328` | Report only; 11 reads of 9 distinct paths; terminal |

Their reports remain in `gemini-completion-tuning` and `gemini-completion-editors` worktrees. Codex
checked their conclusions against raw evidence. Some worker rows attribute combined-suite counts to
individual components; those are not accepted as component coverage claims. This report and the raw
JUnit index control the counts. Lower repeated-read counts in this smaller review do not establish
an end-to-end speedup or savings percentage.

## Remaining boundaries and supported next action

- Physical motor neutralization, controller loop-time distribution/deadlines, allocations and
  sensor-to-output latency require target hardware. Desktop/simulator observations do not establish
  electrical safety or controller performance. No new measured robot bottleneck was established.
- Native presentation of an injected unhandled inspection exception remains unverified. The actual
  model exception/stale-evidence behavior is covered by `RobotStudioSessionOwnershipAuditTest`; the
  ordinary malformed-metadata and recovery path has separate native evidence. They are not equated.
  This remains a documented GUI boundary under the goal, not an unreported passing scenario.
- Minor calibration-label wrapping, unused helper removal and speculative locking/performance changes
  remain lower-priority deferrals. Unselected calibrations, arbitrary user scripts and universal
  multi-file crash recovery are not covered. Enqueued network packets cannot be recalled; robot-side
  leases/authorization still apply, and cancelled blocking IO can already have completed its write.

The supported next runtime checkpoint is physical-controller acceptance when hardware is available:
exercise arm/disarm, feedback loss and stop under controlled load, verify neutral outputs, and measure
timing against configured controller budgets. Publishing the accumulated local changes requires
separate authorization and existing protected release checks. Neither action starts automatically.
