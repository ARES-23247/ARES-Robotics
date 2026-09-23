# Editor failure recovery checkpoint — 2026-09-23

Local base `85194cedfb1e1512be67d4ad65ee6227b928e4f0`; accepted source is in the commit
containing this report. Studio 7.0.64, released ARESLib 19.1.4, disposable Lightbot 3.0.66 project.
No library, generator, schema, robot runtime, release version or archive changes. Nothing published.

## Findings and acceptance

- **High impact within the editor workflow:** Superstructure and Drivebase confirmed discard cleared
  `dirty` before asynchronous reload succeeded. On read failure, modified drafts remained visible but
  were marked clean, undermining save/discard guards. Both now keep the draft dirty until replacement
  succeeds. Drivebase also invalidates the obsolete save review when discard is confirmed.
- Both premature dirty clears exist in released baseline
  `9426ee3c86f331910b2ce1656e33c774506a44e1`; they were not introduced by this batch.
- **State ownership:** a delayed Drivebase reload could replace later edits or a newer completed save,
  including its saved document and project revision. Reproduced on pre-batch source. Result publication
  now checks draft, saved document, review and revision against the request's starting state, alongside
  the existing load generation. This was verified at the model/session boundary; the native editor
  hides its body during loading, so these tests do not establish that the same ordering is reachable
  by visible controls. No robot output defect was established.

Codex independently reviewed changes and callers. The initial worker generation-counter proposal
still installed stale saved/revision data and was rejected. Accepted code preserves existing review
tokens, optimistic disk revisions, cancellation and successful replacement behavior. Field Editor's
different autosave flow did not demonstrate the same discard defect; no speculative patch was made.

## Validation and corrections

- Corrected baseline regressions: Superstructure four tests, two intended failures and two controls;
  Drivebase five tests, four intended failures and one kind-switch control. Failures demonstrate
  incorrect dirty state and stale read publication. They are not nine independent production defects.
- Worker fixtures required correction: interception initially blocked constructor load, a blocking
  latch occupied the coroutine test thread, a geometry DTO/enum did not exist, and one dirty reload
  needed explicit discard confirmation before waiting for the read. Original logs remain retained.
- First combined candidate run: 107/108 passed. The retry fixture omitted the canonical tuning
  profile, so successful reload correctly remained dirty for repair. The fixture now creates the
  profile through real `saveReviewed`; production repair behavior was preserved. Baseline and final
  test variants are retained separately and are not claimed identical.
- Final suite: **108 passed, zero failures/errors/skips**, including nine new recovery scenarios and
  existing project-session/revision/readiness ownership checks. Command with JDK 17:

  ```powershell
  ./ARES-Analytics/gradlew.bat -p ARES-Analytics :app:test --tests '*Superstructure*Test' --tests '*Drivebase*Test' --tests '*RobotStudio*Test' --tests '*ProjectSessionTest' --no-parallel --console=plain
  ```

- Drivebase uses malformed disposable files and real repository saves. Superstructure injects an
  IOException at the document-read boundary; it does not equate every malformed document with a thrown
  exception. Delayed snapshot tests capture actual reads before withholding their return value.
- Native Studio at **base `85194cedf`**, before these two model patches: valid identity/drivetrain Ready
  and Build enabled; malformed disposable `.ares/project.json` plus real Project Identity re-entry
  produces Identity Invalid, dependent readiness Blocked, Build disabled and a repair/preservation
  banner. Restoring exact bytes and re-entering restores readiness and Build. All 12 original canonical
  files are byte-identical afterward. This is expected diagnostic handling, not an induced unhandled
  inspection exception. The worker's proposed generic Refresh instruction was replaced with actual
  navigation controls. No build, deploy or simulation command was launched; no robot was connected.
- Exact native captures are 1424x861. Owned Studio PID 46504, port 49327, Gradle run and isolated
  runtime snapshot closed normally; parent daemon 38960 and unrelated processes were preserved.

Evidence under coordinator `.codex-validation/agy-audit-coordination/build/agy-failure-recovery/`:
`candidate-source.json`, baseline JUnit/log variants, `first-fixed-junit/`, `final-junit/`,
`final-test-summary.json`, `final-tests.log`, `native-readiness-result.json`, captures, canonical
before/after manifests, `shutdown.json`, `independent-disposition.json` and `worker-efficiency.json`.
Logs and screenshots are local evidence, not packaged release artifacts.

Existing changed-part CI runs app regressions and relevant consumer checks. The size inventory also
conservatively selects unchanged shared/gateway scopes. No CI redesign is needed; those full suites
were not rerun locally. Generated FTC roundtrip evidence from `1612a01a4` is reused for unchanged
generator/library/runtime contracts. Source policy, scoped ledger fingerprints and staged whitespace
checks pass; no remote CI was invoked. The size change is solely Drivebase 688 to 699 lines, below the
unchanged 750 limit; Superstructure remains 742.

## Delegation efficiency and continuation

Three external AGY workers ran in parallel, all `gemini-3.8-flash-high`, effort `high`, and ended:

| Worker | Conversation | Turns | Successful reads / distinct paths |
| --- | --- | ---: | ---: |
| Superstructure discard | `457af168-b7fe-4857-b60b-75741d812927` | 1 | 6 / 4 |
| Drivebase discard | `1c312857-516e-4f2e-aa8b-ad1266adec80` | 3 | 32 / 8 |
| Readiness recovery review | `798e4e31-6454-41c9-bdf5-77529c854d7a` | 1 | 7 / 7 |

Assignments, corrections, original patches/reports and CLI streams remain in the separate
`gemini-discard-recovery`, `gemini-editor-discard-review`, `gemini-readiness-recovery-review` worktrees
under `.codex-validation/`; each worker's evidence is in `build/gemini-audit/`. Ownership checks passed.
Workers used source-file tools; Codex performed commands and acceptance. No permission bypass was used.
Do not copy their files over independently corrected coordinator files. Worker reports describing
proposed UI steps as observed are superseded by actual captures and this report.

The Drivebase worker reread paths 24 times and needed two follow-ups. These observed counts support
tighter API/fixture manifests and targeted correction requests, not a quantified quota saving. CLI
token/cache counters are retained as diagnostics; there is no matched Codex-only cost/time baseline.
More concurrency alone does not establish lower total cost. No new loop-time, allocation or hardware
performance result is claimed.

The goal remains active and local. Next bounded lead: Drivebase `confirmSave` publishes after IO
without checking for newer draft/operation state. Trace actual enabled controls and reproduce before
changing it; this batch checks old reload after newer save, not old save after newer edits. Native
unhandled-inspection-exception presentation and hardware behavior remain unverified. No claim that
all editor races or every file have been audited; do not begin another broad pass.
