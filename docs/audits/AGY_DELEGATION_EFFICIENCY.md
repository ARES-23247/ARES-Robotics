# Delegation efficiency check — 2026-09-23

Three AGY CLI jobs ran concurrently using `gemini-3.8-flash-high`, effort `high`,
with disjoint worktrees. Codex reviewed completed results and serialized builds/native UI work.
All three finished with substantive responses. Ownership checks found no out-of-scope edits.
No pushes, releases, permission bypasses or native Codex subagents were used.

| Worker | Successful file reads | Distinct paths | Repeated reads | Tool errors | Latest reported total tokens |
| --- | ---: | ---: | ---: | ---: | ---: |
| Superstructure UI review + patch, two turns | 22 | 7 | 15 | 2 | 258,112 |
| Readiness UI review + boundary correction, two turns | 44 | 16 | 28 | 2 | 390,048 |
| Efficiency analysis, one turn | 9 | 9 | 0 | 0 | 89,613 |

These are deduplicated conversation/step records and the latest cumulative result per conversation.
File reads include guidance/reports, not just production source. Repeated reads can be necessary;
the logs do not establish that each was avoidable. Errors were invalid file-tool calls/paths, not
accepted shell escalation. No worker invoked shell tools in this batch. Prompts are instructions,
not an enforced file-tool allowlist; ownership was independently checked afterward.

The early tuning workers reported 113/126 successful reads and 65/95 repeat reads. Later narrow
assignments often visited fewer files, but the task mixes differ and the readiness follow-up still
exceeded its requested read boundary. This is evidence to improve scoping, not a controlled speedup
or quota-savings result. Reported session duration includes time between continuation turns, so it
must not be treated as active compute time. Cache, thinking and total counters are retained separately;
adding them together would not establish billing usage or a comparable Codex token count.

Accepted outcomes: two reproduced superstructure UI fixes. The readiness safety allegation was
rejected after tracing the independent execution boundary; no speculative gate change was made.
Codex corrected asynchronous test completion predicates, an invalid review fixture, and removed an
unnecessary empty-ID fallback from the worker patch. The saved workflow now requires command-boundary
proof before patching a static lead, exact existing fixtures, compact terminal summaries, and current
resume actions before history. Parallelism remains capped at three useful disjoint jobs; builds stay
serialized and prior unchanged consumer evidence is reused.

AGY processing is external to Codex, but coordination, source review and verification still consume
Codex usage. Neither these logs nor account-wide quota snapshots establish a Codex-only counterfactual.
Official guidance also identifies model, context, reasoning, tools and caching as usage factors;
see [OpenAI usage guidance](https://learn.chatgpt.com/docs/pricing#what-can-i-do-to-make-my-usage-limits-last-longer).

Evidence: coordinator `build/agy-rendered-readiness/worker-efficiency.json` and `worker-metrics.py`;
individual prompts, JSONL streams and original reports in each worker's `build/gemini-audit/` and
`docs/audits/`. Conversations: `5f5342b7-9aff-4e4b-89de-564ee64617d5`,
`f5922e67-3002-4392-bdd6-d1a528c34d75`, `36bc9be6-c84b-436c-bde0-b5e151872193`.
Original worker reports are retained locally; the independent disposition above controls claims.
