# Parallel AGY audits with independent review

Use this reference when the user requests Antigravity CLI/Gemini workers. The coordinator owns
the audit, acceptance decisions, shared contracts, ledger, build scheduling and integration.
Workers supply bounded findings and patches; their completion labels are not verification.

## Select and isolate workers

- Inspect `agy --help` and `agy models` on the current host. The requested configuration for
  this workflow is **Gemini 3.8 Flash High**: `--model gemini-3.8-flash-high --effort high`.
  Honor a later explicit user override. Do not silently substitute another model when unavailable.
- Use up to three independent workers when requested and capacity permits (otherwise two), with separate `codex/` branches/worktrees from the same
  recorded commit. Give each an exact absolute workspace root and disjoint writable paths.
  Do not rely on the model inferring the repository from its default scratch directory.
- Each assignment states the behavior to investigate, practical failure scenarios, allowed files,
  existing tests, expected evidence, stop criteria and other workers' ownership. Require preservation
  of other people's changes and processes. Report out-of-scope defects without editing their files.
- Supply a small verified source/test path manifest with each assignment. Set a practical exploration
  boundary (about 8-12 substantive reads for a narrow task), then require a finding/patch or a
  no-defect report. Do not spend the turn guessing filenames or rereading entire files repeatedly.
- Keep new work **local** unless the user explicitly authorizes publication: no push, PR, protected-main
  merge, release or deployment. Workers do not manage Git integration, permissions, release versions,
  shared instructions, or the central audit ledger, and do not launch further agents.

## Run and observe

Use a prompt file and separate ignored evidence directory for each worker. A representative
PowerShell invocation from that worker's checkout is:

```powershell
$assignment = Get-Content -Raw -LiteralPath build/agy-audit/assignment.txt
agy --model gemini-3.8-flash-high --effort high --mode accept-edits --sandbox `
  --add-dir $PWD.Path --disable-slash-commands --output-format stream-json `
  --print-timeout 15m --log-file build/agy-audit/agy.log --print $assignment `
  1> build/agy-audit/events.jsonl 2> build/agy-audit/stderr.log
```

Verify current CLI help before reusing flags. Start only concrete independent assignments in
parallel. Retain the actual conversation IDs, selected model, process handles, prompts, tool errors,
patches and reports. Poll a live process or its terminal result; an observation timeout is not a
reason to launch a duplicate worker. A reported `SUCCESS` with an empty response and nonempty
`denied_actions` is **not completed work**. A live JSONL stream may end with a partially written
line; wait for the complete record rather than treating that observation as a worker failure.

A print timeout can report `SUCCESS` with an empty response while stderr says the turn remains
in progress. That is partial output, not completion. Continue the same conversation deliberately;
do not infer that a new independent worker can safely take over its files.

On the observed Windows headless setup, a sandboxed `run_command` requested `escalate_admin` and
was denied. The worker must leave that action denied. Do not add broad permission allow rules,
disable permission checks, or use `--dangerously-skip-permissions`. Assign source-file reads/edits
that are permitted, and have the coordinator schedule commands through its normal reviewed tools.
If source tools need a path, provide the exact authorized root; never search personal configuration,
shell history, credentials or unrelated directories to rediscover a repository. Do not retry a
denied resource through another tool. Report unresolved permissions precisely.

The observed CLI help exposes no per-command headless approval flag. `accept-edits` is not
shell escalation approval. A user can use an interactive session to review a specific request;
do not assume that approval persists into headless runs. The working unattended setup keeps
workers on file tools and delegates build/Git commands to the coordinator's normal approval flow.

## Check delegation efficiency

Record completed/failed tool calls, distinct/repeated source reads, final reported token counters,
correction rounds and accepted changes. Deduplicate conversation/step IDs across continuation
logs; final usage may be cumulative across turns. Treat cache and thinking counters as reported
metrics, not interchangeable billing units. Repeated reads may include necessary verification.
Compare bounded worker briefs with prior broad briefs, and prefer reviewing finished patches over
repeatedly reading live drafts. Preserve independent review: verify that tests fail on the defect,
check production visibility and resource cleanup, and compare claimed restores with actual diffs.

Gemini processing does not itself invoke a Codex model, but orchestration/review still consumes
Codex usage. Account-wide quota snapshots do not establish task-level savings or a Codex-only
counterfactual. Do not claim savings percentages without suitable evidence.

## Independently accept each batch

- Inspect the complete worker diff and relevant callers. Reproduce plausible findings and distinguish
  pre-existing defects, worker regressions and unsupported claims. Reject unnecessary or unsafe changes.
- Run focused checks that exercise the failure and actual consumer contract. Coordinate compiler and
  GUI ownership centrally; do not run competing Gradle tasks against the same outputs. Follow
  [build/release guidance](../../ares-build-release/SKILL.md) for changed library contracts and candidate
  identity. Follow [desktop testing guidance](../../compose-desktop-tester/SKILL.md) for visible behavior.
- Update only reviewed ledger records, preserve earlier evidence, and state GUI/hardware/performance
  limits honestly. Record a reproducible local commit and the next distinct priority before expanding.
- For an explicitly requested autonomous goal, continue with successive bounded batches; do not revive
  an audit-every-file campaign or manufacture work. Keep an updated saved goal and resume state.
  Pause when the user asks. Respect the goal system's completion and repeated-blocker rules.

The current campaign is recorded in [the saved goal](../../../../docs/audits/AGY_IMPROVEMENT_GOAL.md)
and [resume state](../../../../docs/audits/AGY_IMPROVEMENT_STATE.md). Those records, not this reusable
reference, determine active scope. Application permissions remain controlled by the user's tools.
