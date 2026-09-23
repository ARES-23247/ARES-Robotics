# AGY improvement campaign: resume state

Updated 2026-09-23. Goal: [autonomous local improvement](AGY_IMPROVEMENT_GOAL.md), active.
Baseline: protected release merge `9426ee3c86f331910b2ce1656e33c774506a44e1` (Studio 7.0.64,
ARESLib 19.1.4). New work is local only.

| Role | Local branch/worktree | Ownership |
| --- | --- | --- |
| Coordinator | `codex/agy-audit-coordination`, `.codex-validation/agy-audit-coordination` | Guidance, goal/state, shared ledger, independent review, build scheduling and integration |
| Operator worker | `codex/gemini-operator-audit`, `.codex-validation/gemini-operator-audit` | Tuning screen/components, Lightbot tuning OpMode/facade, new OperatorAudit tests, individual report |
| Lifecycle worker | `codex/gemini-tuning-lifecycle-audit`, `.codex-validation/gemini-tuning-lifecycle-audit` | Tuning/SysId viewmodels and signal lifecycle, focused lifecycle tests, individual report |

Both workers use `gemini-3.8-flash-high`, effort `high`. Their exact initial assignments and CLI
events are retained in each worktree's ignored `build/gemini-audit/`. No worker patch has yet been
accepted or tested; do not infer success from the AGY result label alone.

The initial calls ended when headless mode denied shell escalation for `git status`. File-only
continuations then wandered into unrelated personal paths and ended on denied reads. Those
resources remain out of scope and denied; no permissions were broadened. Fresh file-tool-only
assignments with explicit absolute worktree roots now successfully read the intended source and
existing tests. Their current streams are `build/gemini-audit/absolute-root-events.jsonl` in each
worker checkout. Use actual process/result state before resuming or relaunching any worker.

The reusable workflow, saved goal and this state passed `verify_agent_guidance.py` and all six
`test_agent_guidance.py` tests. Worker patches and runtime verification remain pending.

Next: obtain substantive worker findings/patches within the allowed paths, independently review
them, run the relevant regression and consumer checks, and update this state and scoped ledger.
Actual rendered operator acceptance remains unverified. Hardware validation remains unavailable.

The earlier release is historical: PR #107 merged, candidate run `35799612841` passed all release
checks, and promotion run `35825999923` completed successfully. It is not part of this goal's new
changes or ongoing publication authority.
