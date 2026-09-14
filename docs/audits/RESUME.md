# Paused audit: restart checkpoint

**Historical checkpoint.** The user subsequently replaced the open-ended audit with the bounded
[robot-readiness goal](ROBOT_READINESS_GOAL.md). Preserve the evidence below, but follow the new
goal's scope and stopping conditions instead of this document's former restart/file-batch procedure.

The user requested a pause after finishing and recording pass 277. **Do not start further audit
work until the user explicitly resumes it.** The monorepo-wide goal remains unfinished. The
application goal status was also verified as **paused** before the checkpoint commit.

## Saved work

- Audit branch: `codex/robot-loop-math-audit`, in the existing isolated worktree
  `.codex-validation/audit-pass191/` beneath the main monorepo checkout. Do not confuse that directory
  name with the current pass number. Do not move or overwrite unrelated work in the main checkout.
- Latest completed batch: [pass 277, generated runtime lifecycle and math](generated-runtime-lifecycle-math-audit.md).
  Its 33 reviewed file paths, content fingerprints, dispositions, validation summaries, and source
  identities are preserved in the tracked [checkpoint data](checkpoints/pass277.json).
- All accumulated file-level review scopes and evidence remain in [file-reviews.json](file-reviews.json).
  This is the authoritative coverage record. A suite pass does not make an unreviewed file complete.
- Library fixes: `72c50d1febcd35d0a10deb6cbde66ad1a2bd26fd`; reviewed generated-lifecycle
  characterization refresh: `17e0c3fd059b64be6949222ae28d97a0f57abbe2`; Studio adapter migration:
  `62cca78d7dcb5f7f66c14a6a54389871eb46c9d0`. The checkpoint documentation is committed after these.
- Tested library tree: `e1e0b12c1f6ae7dd4d9c5545dccd2409dcb28c2e`.
  Local candidate: `19.0.0-rc.e1e0b12c1f6a`. The Studio-only follow-up leaves this tree and the four
  robot consumer source trees unchanged. No push, merge, external publication, or deployment occurred.

The runtime fixes cover host-isolated generated controls, cancellation/close cleanup, freshness
overflow and authored guard ages, target hash collisions and multi-field composition, elapsed-time
arithmetic, and finite/STEP LUT interpolation. Stable metadata allocation measured zero bytes over
10,000 reads after the fix, versus 1,280,000 bytes before it. This is not whole-loop timing evidence.

At this checkpoint, the inventory contains **3,179 tracked files: 1,595 complete and 1,584
unfinished**. Of the unfinished files, 226 are partially reviewed, 1,347 await review, and 11 have
full review but pending validation. By file category, the unfinished work comprises 842 source,
322 test, 180 documentation, 121 configuration/resource, 74 build/tooling, and 45 asset/binary files.
These are frozen checkpoint counts; rerun the inventory after future changes.

## Validation retained

Full ARESLib: **3,030 tests, 472 suites, 13 API checks, and the source-size check passed**.
FTC/FRC and both starters: **716 tests passed**, including generated-project verification and FTC
APK assembly. Prior test identities were retained. All three Studio modules' test sources compile
against the same candidate after the adapter fix. Normal Studio tests do not pass their existing
release-alignment gate, so the two new Studio preview cases are compiled but unexecuted.

The candidate's 410 artifact hashes and the preceding candidate's 410 hashes were verified unchanged.
Full logs, XML, compiler fixtures, failing baselines, candidate manifest, and collection scripts are
retained in `ARESLib-Kotlin/build/audit-pass277-verified-evidence/` in the audit worktree; the local
Maven repository is `ARESLib-Kotlin/build/release-repository/`. **These build directories are ignored
and are not Git backups.** Preserve them when cleaning or moving the worktree. The tracked JSON
checkpoint retains source hashes, test totals, per-suite results, API comparisons, evidence hashes,
and artifact identity so the checkpoint remains understandable if local build outputs are lost.
Hashes cannot reconstruct deleted artifacts; lost evidence must not be described as newly verified.

All owned build processes were terminal before the pause. Stale handle numbers in historical build
notes are not live jobs and must not be restarted or terminated on that basis. No recurring audit
automation or new task was created for this checkpoint.

## Open validation and authorization boundaries

- Studio tests stop at `verifyReleaseVersionAlignment`, expecting `ARES_VERSION: 19.0.0` in the
  distribution workflow. Monorepo policy stops at the missing bundled FTC starter 19.0.0 archive.
  Guidance and document-link checks pass. Tracked archive rebuilding, starter artifact manifest
  changes, distribution workflow version changes, and protected template version references remain
  outside the previously approved migration scope. Do not bypass their gates or silently apply that
  migration. The earlier automatic approval rejection is a release-migration boundary, not evidence
  that unrelated review is blocked.
- No physical robot, hardware IO, rendered Studio window, or whole-loop timing/jitter validation was
  performed in this batch. Lightbot physical dimensions still need real measurements.
- The [audit overview](README.md) retains older timing/opt-in/platform concerns. Later unrelated suite
  passes do not resolve them. Consult each original report and current code before reopening a fix.

## How to restart

1. Wait for an explicit user request to resume. Read this checkpoint and the current root/product
   instructions and relevant repository skills. Inspect branch, dirty state, and running processes.
   Preserve all unrelated work; use the existing audit checkout only if it is still appropriate.
2. Run `python scripts/audit_inventory.py` from that checkout. Its fresh inventory is more current
   than frozen counts in this checkpoint. Hash mismatches reopen changed files; preserve all pending
   and partial scopes. Verify the recorded source commits before reusing previous test evidence.
3. Select one coherent batch of 20-40 related unfinished files, prioritizing live robot correctness,
   safety, and timing. Fully read each claimed-complete file; execute meaningful tests for uncovered
   behavior. Do not expand a batch just to improve a percentage or repeat all completed reviews.
4. Use focused regressions while fixing. Once a shared-library batch is stable, run affected module
   checks, then one distinct local candidate and dependency-ordered robot/Studio consumer checkpoint.
   Preserve source/version identity and immutable candidate bytes. Do not publish different bytes
   under this candidate version. Serialize builds that write the same outputs.
5. Update one batch report and the ledger with actual evidence and limitations. Refresh the ledger's
   own fingerprint after the final edit. Documentation-only changes need documentation/accounting
   checks, not another unchanged runtime matrix. Keep all work local unless the user authorizes more.

## Suggested next batch: hypotheses to investigate

These are leads from the last review, **not confirmed additional defects**. No new batch is started.

- Integer dynamic-LUT targets: compare schema admission with the runtime's integer branch and define
  an explicit representability/rounding policy before changing behavior.
- Superstructure generation: test a subsystem referenced only by a health fallback; verify that its
  ports and imported types are included. Broader renderer and schema paths retain partial status.
- Request/transition sequence exhaustion and the `Long.MIN_VALUE` initialization sentinel: add
  reachable boundary probes before changing timing/state identity contracts.
- FTC autonomous/runtime wrappers: finish deadline/rewind and cleanup aggregation review, including
  final-pose capture. Review remaining template trajectory/path and routine lifecycle code.
- Generated subsystem IO: trace FTC homing output scaling and FRC zero-position unit conversion to
  the actual physical contracts; test renderer behavior before asserting a units defect.
- Studio preview: run the newly compiled age-boundary tests once its release gate is legitimately
  resolved, then review the remaining preview clock, injection, and target semantics.

Do not mark the goal complete solely because pass 277 is closed or because work is paused.
