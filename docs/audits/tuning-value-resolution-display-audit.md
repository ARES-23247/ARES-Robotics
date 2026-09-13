# Tuning value resolution and display audit

Pass 236 follows Studio promotion persistence with the pure tuning value model and its value
display/source-label bindings. The preceding pass finished clean at
`08d466855d0853142d6dafde4bb39bf409b6bf53`. This pass validates Studio tree
`e002c1fdfb915bd3987a9ee405fef9b5b024dde3` against unchanged local ARESLib candidate `17.0.41-rc.a559b2f1934c`
and library tree `a559b2f1934c9317ec4b67b2dd812d867f91f6df`. No library source, version, archive or
published artifact changed.

## Findings and fixes

1. **Editable display silently rounded values and depended on desktop locale.** Five fixed
   decimal places turned subnormal values into zero and changed higher-precision gains when
   text was reused by the editor. A decimal-comma locale produced text the editor could not
   parse, while very large numbers expanded into hundreds of characters. Display now uses
   the exact locale-independent Double representation, omitting only a terminal `.0`.
   Scientific notation remains editable. Tests verify raw-bit round trips, including signed
   zero, subnormal/extreme values and finite samples from 2,048 fixed-seed random bit patterns.
2. **Studio omitted the declaration defaults used by the robot.** Resolution now falls back
   to each validated declaration's typed default, matching TypedTuningRuntime. Defaults have
   an explicit status and no fabricated profile UID. Restating an editable default produces
   no change and does not require invented provenance. Direct overrides, inheritance and
   defaults retain their typed values and source UIDs across all five supported types.
3. **Resolution could mix selected and loaded profile snapshots.** A selected profile must
   now equal its same-UID entry in the loaded collection. Invalid declaration defaults are
   also rejected before resolving values. Tests cover mismatched snapshots, nonfinite and
   incorrectly typed defaults, and defaults outside declared bounds.
4. **Legacy numeric observations lost integer type; malformed typed feedback was accepted.**
   Numeric fallback now creates an INT only for a finite whole number within Int range and
   cannot synthesize text, enum or boolean feedback. Explicit typed observations must match
   the declared union shape and have finite numeric content. Invalid explicit typed feedback
   is dropped without falling back to a potentially stale numeric map. Finite out-of-range
   feedback remains visible for diagnosis; proposal limits still govern proposed changes.
5. **Blank provenance origins were accepted.** Actual changes now require a nonblank source.
   Notes remain optional, consistent with the guided experiment caller. The original failing
   baseline combined source/note expectations but failed at the blank-source assertion;
   after reading that caller, the final tests narrow the rejection to origins and positively
   preserve empty-note compatibility. No blank-note defect is claimed.
6. **Source labels compared a stable UID with a human-facing profile ID.** The panel now
   compares UIDs and labels the default source explicitly. The model documents this existing
   source field's UID meaning. Value accessibility text retains the complete exact value.

## Efficiency

Parent assignment membership and unknown proposal validation now build sets once instead of
rescanning lists for each row/key. The declaration comparator is reused, proposal numeric
values and bounds are read once, and an unreachable integer shape check is removed.
At 256 parameters, the input-access regression measured 1,280 parent-assignment reads and
1,536 declaration reads, including shared validation work. Both remain below its conservative
16N bound. This verifies the targeted scan removal; it is not a wall-clock benchmark, an
overall resolver complexity proof or a robot-loop timing result. Display also no longer
constructs a locale-sensitive fixed-point Formatter.

## Validation

The 10 original regression methods failed their intended baseline assertions. The final new
suite has 15 passing methods, including compatibility, typed precedence, source identity and
input-access cases. All **63 focused tests** passed across resolution, authoring, promotion,
guided experiments and external proposals.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,075 | 6 |

Full Studio validation has **2,124 passing results**, zero failures/errors and six unchanged
opt-in skips. The app tests executed; unchanged dependencies include Gradle up-to-date/cache
results. Focused tests are not counted twice. All 410 unchanged library candidate files were
rehashed; library/robot validation from the preceding unchanged candidate remains applicable
and was not rerun for this Studio-only change. Monorepo policy is verified separately.

## Coverage and limits

The ledger accounts for 3,036 tracked files: 1,352 reviewed, 185 partially reviewed and
1,499 pending, with zero stale or orphaned records. These are scoped review and validation
counts, not universal executable test coverage.

TuningProfileModels is reviewed for its complete pure value/resolution/review behavior.
GainTuningPanel remains partial: only raw value initialization, typed display, accessibility
and source-label bindings were inspected. Its wider editing/project-switch lifecycle remains
for a later scope. No rendered Studio window, physical hardware, robot-loop latency, concurrent
caller mutation, remote CI, push, merge, release or deployment is claimed. The full monorepo
audit remains active and incomplete.

Machine-local evidence: `ARESLib-Kotlin/build/audit-pass236-verified-evidence/`, including
the failing baseline, focused/full XML and logs, measured input reads, policy and summary.
