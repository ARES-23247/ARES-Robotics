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
with 18 additional tests. Pass 8 covers [robot lifecycle and autonomous control](xrp-robot-autonomous-audit.md)
with 23 additional tests. Pass 9 covers [subsystem control math and sampling](xrp-subsystem-control-audit.md)
with 16 additional tests; advanced descriptor behavior keeps that source partially reviewed.
Pass 10 covers [generated Kotlin controller boundaries](kotlin-subsystem-control-audit.md):
PID timing, feedback age, output limits, and finite arithmetic. Profile generation and
advanced safety state machines remain open.
Pass 11 covers [motion profiles](motion-profile-audit.md), including initial overspeed,
continuous braking and generated-profile parity. The remaining MicroPython profile and
feedforward contracts still need implementation and verification.
Pass 12 covers the [basic PID controller](pid-controller-audit.md), including signed
anti-windup, invalid-state recovery, overflow, periodic differences, and measured allocations.
Pass 13 covers [MicroPython profiles and feedforward](xrp-profile-feedforward-audit.md),
including continuous braking, gravity models, sensor dependencies and finite output.
Pass 14 covers [Studio geometric calibration](studio-calibration-audit.md), including
identifiability, finite recommendations, sample chronology and stable vision deviations.
Pass 15 covers [SysId log imports](sysid-log-import-audit.md): stable column positions,
explicit timing, missing acceleration, duplicate handling and chronological samples.
Pass 16 covers [live SysId collection](sysid-live-collection-audit.md): complete rows,
microsecond identities, bounded history, immutable snapshots and stop/restart boundaries.
Pass 17 covers [SysId analysis](sysid-analysis-audit.md): identifiable scaled regression,
microsecond alignment, FFT endpoints and stable arithmetic, one background fit per motor
run, and generation-checked result publication. Pass 18 covers [AutoTuner inputs and proposal contracts](autotuner-input-audit.md): shared
preparation/parsing, timestamp and median/range checks, dimensionally correct declaration
mappings and approval revalidation. Pass 19 covers [step-response and PI math](step-response-audit.md): separate rise/delay
estimates, constant-input plateaus, moving initial states, stable residuals, linear settling
analysis and consistent SIMC PI gains. Pass 20 covers [proposal eligibility and delivery](tuning-proposal-delivery-audit.md):
feedforward-only eligibility, bounded queued delivery, immutable snapshots, atomic board
staging and failed-load isolation. Pass 21 covers [replay windows and seeking](replay-window-audit.md):
coalesced reads, in-flight prefetch reuse, stale request rejection, truthful seek completion,
error recovery, joined cleanup and database baseline/paging boundaries. Pass 22 covers
[replay playback time and load ownership](replay-playback-audit.md): overflow-safe clock
scaling, loop endpoints, command timing, initial-load cancellation and terminal disposal.
Pass 23 covers [telemetry density and metadata queries](telemetry-density-metadata-audit.md):
exact interval bins, single-instant density, one-statement snapshots, bounded output,
direct/workspace session lookups, read coordination and atomic paired metadata edits.
Pass 24 covers [database metrics and action storage](database-metrics-actions-audit.md):
coherent primitive latency tracking, stable means, nearest-rank p95, native appender
transaction activation for actions/telemetry, input domains and deterministic action ties.
Pass 25 covers [database coordination and repository transactions](database-coordinator-transactions-audit.md):
caller-owned transaction preservation, shared cleanup across metadata/evidence/imports,
set-based console writes and cancelled/failed coordinator attempts.
Pass 26 covers [dashboard health calculations](dashboard-health-audit.md): observed counter
baselines, target epochs, invalid/precise clock intervals, unsigned log-byte differences,
prefetch ratios and unavailable dashboard drop counts.
Pass 27 covers [controller health sources and presentation](controller-health-audit.md):
shared live/replay observations, exact aliases, finite domains, unknown states, bounded
sampling, per-topic freshness, collector lifecycle and platform battery display boundaries.
Pass 28 covers [mission summaries and alert presentation](dashboard-mission-audit.md): exact
selected-frame identity, historical/unknown evidence, current alert filtering and ordering,
dismissal lifecycle, bounded popup rendering and honest diagnostic units.
Pass 29 covers [alert occurrence transitions](alert-lifecycle-audit.md): acknowledgment and
resolution, separate recurrence intervals, stable peak excursions, invalid inputs, chronology,
CAS retries and unchanged-write suppression.
Pass 30 covers [alert source identity and telemetry publication](alert-source-publication-audit.md):
queued target epochs, reset caches, source microseconds/order, duplicate suppression, one-buffer
fan-out and reentrant publication/reset ordering.
Pass 31 covers [loop-overrun windows](loop-overrun-window-audit.md): source microsecond
boundaries, independent aliases, valid periods, constant three-slot evidence, preserved
occurrence peaks, an independent oracle and measured detector allocations.
Pass 32 covers [motor diagnostic evidence and current windows](motor-diagnostic-window-audit.md):
complete fresh feedback, exact units/time, independent motor updates, bounded stable averaging,
invalid-input recovery and removal of duplicate temperature evaluation.
Pass 33 covers [alert persistence and shutdown](alert-persistence-audit.md): failure isolation,
initial/latest occurrence retention, autonomous fair retries, saving status and joined drains.
Pass 34 covers [CAN, I2C and vision scalar diagnostics](scalar-diagnostic-audit.md): independent
sources, fixed units, valid domains, configured limits and removal of duplicate/cache scans.
Pass 35 covers [platform battery alert policy](platform-alert-audit.md): retained evidence,
serialized context changes, coherent cached rules, battery aliases and voltage domains.
Pass 36 covers [alert rule configuration](alert-rule-configuration-audit.md): whole-file
validation, bounded UTF-8 reads, visible fallback and exclusive default-file creation.
Pass 37 covers [diagnostic rule policy](diagnostic-rule-policy-audit.md): binary motor bounds,
explicit fixed/disabled loop policy, derived-source ownership and configured loop keys.
Pass 38 covers [alert audio timing and lifecycle](audio-notifier-audit.md): monotonic cooldown,
one pending/active playback attempt, cancellation ownership, cached PCM and Java Sound cleanup.
Pass 39 covers [primitive filter math and repeated work](primitive-filter-audit.md): stable
finite weighting and slew steps, invalid-time/reset boundaries, a cached incremental median,
independent numerical oracles and measured hot-path allocation.
Pass 40 covers [joystick conditioning and adapter allocation](joystick-conditioning-audit.md):
validated scalar/vector domains, narrow deadbands, positive curve powers, a reusable output API,
trigger isolation and immutable snapshot ownership with measured intermediate allocation.
Next is calibrated interpolation and remaining kinematics/estimation; the AresGamepad DSL
and remaining controller connection/age ownership also need separate full reviews.
Studio follow-ups include cross-policy consistency, remaining lifecycle and global retention,
upstream connection ownership, replay timeline readiness, diagnostics/evidence
reads, remaining schema/backup behavior, recording-memory costs, stale approval, legacy
states, verified project/evidence
identity, simulation provenance, logging/transport, advanced descriptor safety and remaining
Studio math.
Continue through all library modules, robot/starter products, Studio modules, generators,
build/release/CI tooling, configuration, documentation, and resources using the inventory
as the work queue. Map Kover reports to source files and identify uncovered behavior.

Open validation concerns include the timing-sensitive `TelemetryUpdateE2ETest` failure
documented in pass 3, the first-parent PID readiness timeout in pass 19
(`ProjectBuildServiceTest`, cause unproven after passing reruns), the pass 20 replay-scrub
performance-baseline failure (105.8996 ms against 100 ms; cause unproven), Studio opt-in
tests, and physical loop/jitter/electrical validation.
The goal remains active until every file has a defensible disposition and all feasible
checks have completed. Changes remain local; no push, merge, or release is part of this goal.
