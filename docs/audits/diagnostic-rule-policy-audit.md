# Diagnostic rule policy audit

Pass 37 follows configuration loading into Studio's derived motor and temporal loop diagnostics.
The scope is desktop observation and alerting; robot control laws and actuator limits are unchanged.

## Configuration contract

Motor Stall and Disconnected rules compare a derived binary value against configured bounds:
one means the condition is observed and zero means complete fresh feedback disproves it.
Missing/invalid feedback remains unknown and cannot create or resolve an occurrence. The default
maximum 0.5 alerts on one; a maximum of one includes both values; a minimum can intentionally
alert on zero. A rule with neither bound disables its alert. These bounds do not configure the
underlying current, duty, velocity or freshness thresholds.

Loop detection retains the established temporal policy: three periods greater than 25 ms in an
inclusive source-time second, or a current period at least 100 ms. The accepted enabled rule has
maximum 25 and no minimum; a rule with neither bound disables that source. Other loop bounds
cannot describe this detector and are rejected visibly during whole-file validation. No implicit
severe-threshold scaling or single-sample scalar override is introduced. Configurable temporal
parameters would require their own explicit policy contract.

Loop aliases inherit the canonical rule only when they lack an explicit rule. Therefore an
explicitly enabled alias can coexist with a disabled canonical source. Each alias still owns its
own timing evidence and occurrence. Configured transport keys, display names and audio flags
remain attached to that source's rule.

## Findings and changes

- Motor transitions used the detector's boolean directly while peak calculations used configured
  scalar bounds. Boundless and inclusive-maximum rules still alerted; lower-bound rules behaved
  backwards. Ordinary scalars and derived motor values now share one inclusive-bound comparator.
- Loop bounds were accepted but ignored. Unsupported bounds now reject the entire configuration
  with an explanation, using the loader's visible built-in fallback. Explicitly disabled loop
  rules return before allocating or updating a temporal window. Default loop behavior is unchanged.
- Incoming telemetry on the twelve locally derived Stall/Disconnected keys could fabricate or
  clear a motor diagnosis independently of complete feedback. These keys now belong exclusively
  to the local six-motor detector. No current repository robot producer was found publishing
  these derived keys. Other motor names and custom topics remain ordinary configured scalars.
- Loop alert records discarded a configured leading slash even though ordinary rules preserved
  their configured key. Loop transitions now use the configured rule key while retaining
  normalized source identity for chronology and independent alias windows.
- Ordinary evaluation normalized each topic again and repeated its leading-slash loop check.
  It now receives the collector's normalized key and performs one loop-key membership check.

## Validation

The nine-method baseline reproduced eight failures in 1m 31s. Evidence:
`ARESLib-Kotlin/build/audit-pass37-before.log` and `ARESLib-Kotlin/build/audit-pass37-before.xml`.
The one passing case originally sent a raw derived topic before its rule existed; the final
fixture explicitly configures that rule so it also exercises the vulnerable registered path.

There are 16 added methods: twelve engine cases and four direct semantics cases. They cover
binary bounds, disabling, complete/unknown feedback, raw/derived ownership, both directions of
explicit alias precedence, unsupported loop policy, configured source keys, occurrence peaks,
all catalog loop aliases and unrelated custom topics. The focused alert/diagnostic selection
passed 181 methods without skips in 3m 9s (`ARESLib-Kotlin/build/audit-pass37-focused.log`).
The full Studio gate passed in 3m 56s (`ARESLib-Kotlin/build/audit-pass37-studio.log`):

- 1,747 ordinary methods passed, with six existing opt-in methods skipped. The 56 dashboard
  smoke methods and performance-baseline method passed. Unchanged shared/gateway tests were
  up-to-date; the app suite executed. Coverage verification, release alignment and production
  Kotlin file-size checks passed.
- All 16 added methods passed. Final XML: `ARESLib-Kotlin/build/audit-pass37-final-xml`.
- Rule semantics covered 6/6 executable lines and 26/26 branches; the loader covered 46/47 lines
  and 31/36 branches; the engine covered 183/185 lines and 102/126 branches. Snapshot:
  `ARESLib-Kotlin/build/audit-pass37-kover.xml`. This does not close the engine's remaining
  contracts or establish every possible concurrent interleaving.
- A sixth headless AlertPanel scene uses the actual longer loop-policy error. Its opaque
  480x300 image was inspected: all corrective text and the empty-alert message are readable
  without clipping. Image: `ARES-Analytics/app/build/diagnostics/alert-persistence-audit/loop-configuration.png`.
- Dashboard replay load was 28.003 ms, scrub p95 26.7104 ms and rapid-seek burst 7.7783 ms.
  The fixture persisted/restored 12,000 frames without drops. Snapshot:
  `ARESLib-Kotlin/build/audit-pass37-dashboard-smoke.json`. These desktop measurements do not
  isolate the comparator's performance or measure physical robot loop timing.
- Validation used unchanged isolated candidate `17.0.3-rc.b81c0156add9`. Library and robot
  consumers were not rebuilt for Studio-only changes.

Repository policy passed (`ARESLib-Kotlin/build/audit-pass37-policy.log`), including shared
guidance and local links in 198 current documents. Inventory: 2,477 tracked files, 176 fully
reviewed, 61 partially reviewed and 2,240 pending, with no stale or orphaned records. File-level
review accounting remains separate from executed tests and measured line/branch coverage.

## Limits and next work

This pass makes the existing specialized policy explicit; it does not add configurable temporal
or physical motor detector parameters. Unsupported loop settings now visibly select built-in
rules for the whole file. Existing files are preserved and can be corrected to the accepted
fixed or disabled form. Users with independent device-authored Stall/Disconnected signals for
the six reserved motor names need a distinct configured telemetry key.

Configuration is loaded once, with the synchronous IO and default-file durability limitations
recorded in pass 36. Platform/context consistency, immutable historical policy identity,
concurrent lifecycle, global retention, audio resources, whole-application shutdown and remaining
transport/store behavior still need review. The engine remains partial. Existing intermittent
and opt-in tests remain open. No live audio, visible Studio window or physical robot timing is
claimed. All changes remain local; the full monorepo audit goal stays active.
