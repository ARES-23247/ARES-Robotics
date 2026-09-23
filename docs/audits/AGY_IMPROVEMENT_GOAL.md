# Autonomous local improvement with AGY workers

Authorized 2026-09-23. Status: **complete** (2026-09-23). This is a new practical-improvement campaign, not a
restart of the historical audit-every-file objective. The user explicitly requested parallel
AGY/Gemini workers, high effort, independent Codex review, and local-only changes.

Use `agy` with model `gemini-3.8-flash-high` and effort `high`, verifying availability first.
Codex coordinates up to three explicitly authorized workers in isolated worktrees with disjoint ownership, audits their
patches, runs appropriate validation, maintains shared contracts and the review ledger, and
integrates only verified changes into local checkpoints. Preserve unrelated work and processes. Use verified path manifests, bounded source exploration,
and batch review; assess delegation overhead without claiming unmeasured Codex quota savings.

Prioritize demonstrated realistic behavior, safety, freshness/enable, lost-command and lifecycle
defects. Optimize performance only with measurements against explicit budgets. Defer speculative
improvements, unrealistic extremes and cosmetic cleanup. Reuse valid prior evidence and existing
regressions rather than repeating whole matrices or accumulating redundant tests.

The initial batch covers the Studio/Lightbot operator tuning path and tuning acknowledgement,
disconnect, disarm and shutdown behavior. Subsequent batches should address distinct practical
risks supported by findings or missing scenario evidence. Before expanding, each batch must have
independent review, necessary fixes, appropriate passing checks or explicit remaining limitations,
preserved evidence, scoped ledger updates and a reproducible local checkpoint. No whole-repository
review or physical-performance claim follows from a green test count.

**No new pushes, PRs, protected-main merges, releases or deployments.** Publication requires new
explicit authorization. The earlier approved Studio 7.0.64 release completed separately before
this local-only campaign; it does not authorize publishing these new changes.

Keep the goal active across turns. Continue until the user pauses, the identified practical-priority
work is demonstrably complete, or a genuine external/user-input blocker satisfies the goal system's
repeated-blocker rule. Do not redefine completion around a convenient subset, mark unobserved tests
as passed, bypass tool permissions, or use blanket dangerous approval flags.

Follow the [reusable AGY workflow](../../.agents/skills/ares-workspace/references/parallel-agy-audit.md)
and keep [the resume state](AGY_IMPROVEMENT_STATE.md) current. The canonical instructions and this
record preserve the setup for future sessions without modifying personal permissions or credentials.

Completion: [requirement-by-requirement assessment](AGY_CAMPAIGN_COMPLETION.md). The identified
practical-priority batches are closed at source `8292ddea97473c9b9a1a70f2bb1c99dbaffe00a2`, with
physical/native boundaries explicit. The objective and operating constraints above are preserved;
no new broad audit or publication is authorized by completion.
