# Monorepo audit coverage

The active audit goal is to account for every tracked file, review its owned behavior, and
complete appropriate validation. The inventory started at 2,349 tracked files on
`e6640f5a`; additions are included after staging. No repository-wide coverage claim is made.

Run `python scripts/audit_inventory.py` from the monorepo. It regenerates the complete
file-level inventory at `.codex-validation/audit-inventory.json`, including product,
provisional file category, content fingerprint, review status, and validation evidence.
The small tracked [review ledger](file-reviews.json) stores evidence; files absent from it
are pending, not implicitly covered by an old suite run. Deleted entries are reported as
orphaned. A changed or missing file loses completion credit automatically. Fingerprints
normalize UTF-8 text line endings, but preserve binary bytes.

Review and validation are independent. `partial` means only the recorded sections were
audited. `reviewed` means the entire file was inspected. `passed` means the recorded
checks passed for their stated scope, not that every branch executed. Completion requires
an unchanged file, full review, explicit scope, and passing applicable validation (or a
justified `not-applicable` record for non-executable content). A matching fingerprint does
not establish that dependencies or consumers are unchanged: rerun their affected checks
after integration changes. Keep the command, candidate/source identity, failures, skips,
and limitations in the linked report. The inventory is accounting, not a substitute for
Kover/line coverage or measured hardware tests.

Use unit, property, integration, and generated-project tests for executable behavior;
schema/semantic checks for configuration; link and content checks for documentation;
integrity and consumption checks for binary resources. Identify generated and upstream
files explicitly during review; do not manufacture unit tests for every file extension.
Hardware, platform-specific UI, and deployment evidence must be recorded separately.
Opt-in skips remain open until run or documented as requiring an unavailable environment.

Prior bounded findings and test counts are in the [robot audit report](../robot-loop-audit.md)
and [math audit report](../mathematics-audit.md). Historical edited runtime/test files are
seeded as partial review only; historical green suites do not close their remaining review.

## Current pass and remaining work

Pass 4 covers XRP field loading, shape geometry, and continuous collision constraints.
It fixes invalid/partial field installation, repeated obstacle geometry construction,
translation tunneling, and rotational sweep collisions. Evidence is in the robot audit
report. The checker uses a conservative swept footprint, at most 256 interval queries,
and 16 subdivision levels; uncertainty stops at the last proven safe pose. This is a
desktop kinematic constraint, not contact dynamics or a physical safety sensor.

Pass 5 covers [XRP lifecycle, timing, and hardware adapters](xrp-lifecycle-audit.md), with
24 additional tests. Pass 6 covers [deployment and boot recovery](xrp-deployment-audit.md)
with 18 additional tests. Pass 7 covers [transport and control leases](xrp-transport-audit.md)
with 18 additional tests. The next passes should cover the remaining MicroPython runtime,
then logging/transport and Studio analytics math.
Continue through all library modules, robot/starter products, Studio modules, generators,
build/release/CI tooling, configuration, documentation, and resources using the inventory
as the work queue. Map Kover reports to source files and identify uncovered behavior.

Open validation concerns include the timing-sensitive `TelemetryUpdateE2ETest` failure
documented in pass 3, Studio opt-in tests, and physical loop/jitter/electrical validation.
The goal remains active until every file has a defensible disposition and all feasible
checks have completed. Changes remain local; no push, merge, or release is part of this goal.
