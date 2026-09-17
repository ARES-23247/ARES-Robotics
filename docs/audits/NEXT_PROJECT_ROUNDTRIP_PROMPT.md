# Next bounded project-roundtrip checkpoint

Copy the following prompt to the other agent after the local review merge:

```text
Complete the remaining project round-trip integration checkpoint, keeping all work local.

Start from the LOCAL codex/project-roundtrip-audit branch containing the reviewed merge and
docs/audits/PROJECT_ROUNDTRIP_REVIEW.md. Record its exact commit. These changes are not on GitHub:
do not reset to origin/main or start from a remote branch that omits the local review fixes.
Read AGENTS.md, the review report, and applicable repository skills. Preserve unrelated work and
processes; create an isolated feature branch/worktree from this local checkpoint.

Review one coherent boundary: Studio project export/reopen and generation recovery. Select the
exact production files and acceptance scenarios before editing, then proceed autonomously.
Reuse the now-tested preview and archive exclusions. Focus new review on callers, project-session
loading, document persistence, generation orchestration, and transaction/failure boundaries.

Use a disposable copy of a real BioBuzz project and one generic starter. Through the actual
application services, save/export the project, extract it safely, reopen it, regenerate, compile,
and run the relevant generated consumer tests or simulated IO. Confirm that field, hardware,
subsystem, tuning, control, and autonomous settings retain their intended meaning. Preserve a
real USER-OWNED source extension byte-for-byte. Repeated generation must be deterministic.

Exercise cancellation and an injected failure after an intermediate write through the real
orchestration path. Establish whether the previous project remains usable or recovery is required.
Verify recovery preserves earlier user edits and reports partial failure clearly. A per-file atomic
write or passing serializer round trip does not establish a whole-project transaction guarantee.

Fix demonstrated high-impact defects. Capture fail-before/pass-after evidence with independent
expectations where practical. Measure before proposing performance changes. Defer cosmetic work,
speculative guards, and unrelated refactors. Keep safety and ownership checks intact.

You may use up to two read-only subagents with disjoint review paths; keep one coordinating writer
and serialize builds sharing outputs. Do not recursively delegate.

Preserve historical ledger evidence. Record reviewed hashes, actual scope, commands, results,
skips, findings and their origin in one linked report. Mark partial reviews as partial. Tests or
hash refreshes do not establish full-file review. Update the source-tree identity if library code
changes; use a new unique local candidate coordinate and validate affected consumers consistently.
ARES 19.1.3 is reserved locally, not published. Preserve existing released package bytes.

Stop when these selected scenarios pass and scoped high-impact defects are resolved or a concrete
external blocker is documented. Report limitations and exact local commits for independent review.
Do not push, merge, publish, deploy, or automatically start another audit batch. Hardware is unavailable;
desktop/simulator results must be identified as such.
```
