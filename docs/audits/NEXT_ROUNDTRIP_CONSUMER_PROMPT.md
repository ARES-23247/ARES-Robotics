# Next bounded consumer round-trip checkpoint

This historical prompt produced submission `a4a9e6133`; see the
[consumer peer review](PROJECT_ROUNDTRIP_CONSUMER_PEER_REVIEW.md) for its corrections, verified
scenarios, and remaining limits before assigning more work.

Copy this prompt to the other agent:

```text
Complete the remaining consumer round-trip proof. Keep all work local.

Start from the LOCAL codex/project-roundtrip-integration branch after its reviewed local
integration, containing docs/audits/PROJECT_ROUNDTRIP_INTEGRATION_PEER_REVIEW.md. Record its
exact commit and create an isolated feature branch/worktree from it. Do not reset to origin/main:
the local changes are not published. Read AGENTS.md, that peer review, and relevant repository
skills. Preserve other people's files, processes, and historical audit evidence. Use one agent.

Your scope is one BioBuzz project and one generic FTC starter through the actual Studio services:
create/open, save a meaningful configuration change, export, extract, reopen in a fresh session,
regenerate with the project's real wrapper through ProjectBuildService, then compile and run the
resulting robot and simulator tests. Use the reviewed extraction method; do not add an import UI.
Keep a working USER-OWNED extension byte-identical and compile it into the resulting consumer.
Choose independent expected values for field/hardware/subsystem/tuning/control/autonomous
settings. Show that the reopened configuration produces the intended behavior in generated
runtime and simulated IO; a matching fingerprint alone is insufficient. Check regeneration
determinism over the relevant generated files, not just the primary source string.

Add one controlled intermediate-write failure and one cancellation while the real generation
process is running. Establish that the write occurred before the failure. Reopen/retry through
the application orchestration and verify diagnostics, preserved canonical/user edits, stale-output
rejection, process cleanup, and successful recovery. Do not call manual editing of a completed
output an interrupted-generation test. A controlled wrapper test proves process lifecycle only;
it is not proof that the real generator and consumer recover. If failure injection needs a seam,
keep it narrow and test-only/internal without weakening production ownership or safety checks.

Fix demonstrated defects in this boundary and capture fail-before/pass-after evidence where
practical. Do not expand into another broad audit, cosmetic refactor, or speculative optimization.
Reuse the passing extraction, process-lifecycle, session, and persistence checks. The unchanged
ARESLib tree is 4161ce50ae762d9ba02cd65663c6e9a40da6afbf; use local candidate
19.1.3-rc.roundtrip.dbb5b9f.1 with the absolute repository URI recorded in the peer review.
Do not rebuild that candidate or overwrite its bytes. Library changes require a new unique
candidate and dependency-ordered validation. The reserved final ARES version is 19.1.3.

Preserve useful automated regressions in the existing changed-part CI scopes. If a heavyweight
test is opt-in, prove its CI/release invocation selects it and record any skips explicitly. Update
docs/audits/file-reviews.json truthfully with reviewed content identities, partial/full review scope,
commands, findings and origin, actual results, and linked evidence. Keep earlier evidence intact;
the maintainability size ledger is not the file-review ledger.

Stop when these two consumer journeys and the selected recovery scenarios pass, or a specific
external blocker prevents them. Return exact local commits, a concise readiness report, and
remaining limitations. Hardware and native UI were not validated by these headless checks.
Do not merge, push, publish, deploy, start other tasks/subagents, or automatically begin another pass.
```
