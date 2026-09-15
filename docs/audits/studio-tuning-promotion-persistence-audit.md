# Studio tuning promotion and recovery audit

Pass 235 moves from robot-local overlay persistence to Studio's canonical promotion workflow.
The preceding pass made verified progress and finished clean at `7a4bdcadaaa1928a5a0c4a06c9241f8c26fd6cf1`.
This pass validates Studio tree `ea30ad04fed12dfc634871372f42130cac8f57f4` against the unchanged local ARESLib candidate
`17.0.41-rc.a559b2f1934c` and library tree `a559b2f1934c9317ec4b67b2dd812d867f91f6df`.
No library source, version, archive or artifact bytes changed, and nothing was republished.

## Findings and fixes

1. **Review identity lost numeric precision and field boundaries.** The old token formatted
   doubles to five decimal places, so proposals 3.000001 and 3.000002 shared a token. Delimiters
   also let reviewer/summary text exchange fields, while policy and ownership were omitted.
   Tokens now hash structured typed JSON containing exact values, declarations and complete
   review fields. They use the full SHA-256 digest and are independent of display locale.
2. **Promotion trusted caller-supplied policy and incompletely checked evidence.** A change
   could claim LIVE_SAFE for a calibration/vendor declaration, and manually attached evidence
   bypassed hashing. Promotion now checks declaration identity/policy/ownership, rejects vendor
   edits and duplicate parameters, and verifies every supplied evidence pair. Paths must be
   relative and resolve inside the project; an external Windows junction baseline was rejected
   after the fix. Valid manual/calibration evidence and uppercase hashes remain supported.
3. **Competing repositories could accept the same reviewed revision.** A controlled first
   save paused before replacement while a second repository successfully promoted that same
   revision. The shared project lock now covers recovery, revision checks, preparation and
   commit, so the second save waits and then fails stale validation.
4. **Failed promotion left successful-looking history behind.** Promotion now uses the existing
   recovery transaction for only its four target files: canonical profile, proposal snapshot,
   prior canonical backup and review record. Failure rolls those files back; it does not copy
   the complete growing history directory. Unique staged files replace the old fixed temporary
   filename, and canonical replacement uses the existing strict atomic-write helper.
5. **Session recovery could undo a live transaction.** Recovery previously ignored the project
   lock and treated an active journal as abandoned. A separate one-test baseline reproduced it.
   Recovery now takes the same reentrant lock, including when called by another session loader.

The eight promotion baseline tests and one recovery baseline test all failed their intended
behavior assertions. The fixed suite adds 14 methods: 13 promotion tests and one recovery race.
It also checks project-root aliases, rejected canonical/history/recovery aliases, duplicate and
invalid typed changes, and failed Windows file replacement. When a locked canonical file also
prevents rollback, the journal is retained; after unlocking, recovery restores the baseline and
removes the journal. Intermediate rollback failure is not silently treated as a completed save.

## Efficiency and fixture ownership

Drivebase-edit assignment filtering now builds one UID set instead of scanning every declaration
for every assignment. JSON parsing and the unknown-assignment pattern are reused across profiles.
These are code-level reductions in repeated work, not a measured UI latency claim. The existing
authoring tests now give their five temporary project fixtures to a JUnit cleanup rule, preventing
further accumulation from those fixtures. Unrelated or historical temporary directories were not
swept. Promotion still runs through the view model's existing IO dispatcher.

## Validation

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,060 | 6 |

There are **2,109 passing Studio results**, zero final failures/errors and six unchanged opt-in
skips. The 51-test focused run covers promotion, authoring, recovery and ProjectSession integration.
The full Studio run validates the final runtime code and parser/lookup cleanup. A subsequent
12-test authoring run validates the fixture-only cleanup, with unchanged assertions. Evidence
includes Gradle up-to-date/cache results; focused and fixture reruns are not counted twice.
The 410 unchanged library candidate files were rehashed. Robot/library suites already passed in
pass 234 and were not rerun for this Studio-only change. Monorepo policy is verified separately.

## Coverage and limits

The ledger accounts for 3,034 tracked files: 1,349 reviewed, 185 partially reviewed and
1,500 pending, with zero stale or orphaned records. These counts describe scoped review and
appropriate validation; they are not universal executable test coverage.

The tuning repository remains partial for broader catalog-loading ownership/identity, history
text rendering and external concurrent mutation. The shared recovery engine remains partial
outside its newly tested in-process locking boundary. The token data model, view-model/session
call paths and generic atomic helper were read only in the relevant scope. This pass does not
establish cross-process locking, crash/power-loss durability, live path-swap resistance, hardware
or physical timing, or a rendered Studio window. No remote CI, push, merge, release or deployment
was performed. The overall monorepo audit remains active and incomplete.

Machine-local evidence: `ARESLib-Kotlin/build/audit-pass235-verified-evidence/`, including both
failing baselines, focused/full XML, the fixture cleanup rerun, policy results and `summary.json`.
