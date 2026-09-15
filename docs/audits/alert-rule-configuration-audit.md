# Alert rule configuration audit

Pass 36 reviews Studio threshold-file loading, validation, fallback behavior, startup file
creation and user-visible configuration diagnostics. It does not change robot actuator policy.

## Findings and changes

- Reversed bounds and duplicate transport-normalized keys were accepted; the latter silently
  replaced an earlier row. Configuration now validates the entire list before registration.
- Unknown JSON fields were ignored. A misspelled bound could leave a rule with no active limit.
  Unsupported fields now reject the file, as do blank/control-character names or topic keys,
  nonfinite bounds, invalid UTF-8 and malformed JSON. A UTF-8 BOM remains accepted.
- Read failures silently selected defaults, while failures creating a missing file could abort
  engine construction. Expected filesystem/permission failures now select defaults and expose
  a startup warning in the alert panel. Fatal errors are not broadly swallowed.
- Reads were unbounded. The loader reads at most 1 MiB plus one byte and accepts at most 4,096
  rules. Exceeding either limit rejects the complete configuration.
- Missing-file initialization could overwrite a configuration created after the existence
  check. Defaults now use exclusive CREATE_NEW. A concurrent creation triggers one bounded
  reread, without recursive creation attempts or replacement of the user's file.

Valid empty lists, explicitly boundless rules, equal minimum/maximum limits, Unicode display
names and distinct battery aliases remain accepted. Duplicate detection uses the same transport
topic normalization as engine registration. Fallback snapshots the supplied default list.
Invalid existing files remain unchanged for correction; the warning describes the failure
without echoing raw JSON contents.

## Validation

The five-method baseline reproduced five failures in 26s: reversed bounds, normalized duplicate
keys, a misspelled bound, a blank display name and a failed default-file creation. Evidence:
`ARESLib-Kotlin/build/audit-pass36-before.log` and `ARESLib-Kotlin/build/audit-pass36-before.xml`.

There are 18 added methods: five engine integration cases and 13 direct loader cases. They cover
whole-file rejection after a valid prefix, valid boundary configurations, UTF-8/BOM handling,
bounded real filesystem reads, exclusive creation, concurrent creation/reread failures,
permission and I/O errors, invalid paths and propagation of fatal errors. The focused alert
selection passed 111 methods in 1m 20s (`ARESLib-Kotlin/build/audit-pass36-focused.log`).

The existing headless Compose persistence test additionally renders the configuration warning.
The 480x300 opaque panel image was inspected: the warning and empty-alert message are readable
without clipping. Image: `ARES-Analytics/app/build/diagnostics/alert-persistence-audit/configuration.png`.
This does not establish visible Studio window or physical hardware behavior.

The full Studio gate passed in 4m 57s (`ARESLib-Kotlin/build/audit-pass36-studio.log`):

- 1,731 ordinary methods passed, with six existing opt-in methods skipped. The 56 dashboard
  smoke methods and the performance-baseline method passed. Unchanged shared/gateway test
  tasks were up-to-date; the app suite executed. Kover verification, release alignment and
  production Kotlin file-size checks passed.
- All 18 added methods passed. Final XML: `ARESLib-Kotlin/build/audit-pass36-final-xml`.
- The loader source covered 45/46 executable lines and 29/34 branches; the engine covered
  180/182 lines and 109/134 branches; the panel covered 58/128 lines and 15/50 branches.
  Snapshot: `ARESLib-Kotlin/build/audit-pass36-kover.xml`. These measurements do not close
  the remaining engine/panel contracts or prove every failure interleaving.
- Dashboard replay load was 29.4903 ms, scrub p95 30.356 ms and rapid-seek burst 6.709 ms.
  The fixture persisted/restored 12,000 frames without drops. Snapshot:
  `ARESLib-Kotlin/build/audit-pass36-dashboard-smoke.json`. These are desktop measurements,
  not isolated configuration-loader benchmarks or physical robot loop-time measurements.
- Validation used unchanged isolated candidate `17.0.3-rc.b81c0156add9`. Library and robot
  consumers were not rebuilt for these Studio-only changes.

Repository policy passed (`ARESLib-Kotlin/build/audit-pass36-policy.log`), including shared
guidance and local links in 197 current documents. Inventory: 2,473 tracked files, 172 fully
reviewed, 61 partially reviewed and 2,240 pending, with no stale or orphaned records. Review
accounting remains separate from executed tests and measured line/branch coverage.

## Limits and next work

Loading remains synchronous and occurs once at startup. A byte limit does not bound filesystem
latency. Exclusive creation prevents replacement of an existing file, but writing a newly
created default file is not crash-atomic: an interrupted write can leave a partial new file.
The current engine reports failure and uses defaults; a subsequent startup rejects malformed
contents. This pass does not claim crash durability or live configuration reload.

Validation covers structural rule consistency. Specialized temporal loop/motor configuration,
cross-policy contradictions such as an XRP minimum above a retained custom maximum, historical
policy identity and transport/context ownership remain open. The engine and alert panel remain
partially reviewed. Global retention, whole-application shutdown, existing intermittent tests,
opt-in tests and physical loop/jitter measurements also remain open. All changes remain local;
the full monorepo audit goal stays active.
