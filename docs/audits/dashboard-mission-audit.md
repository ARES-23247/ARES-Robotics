# Dashboard mission and alert presentation audit

Pass 28 reviews selected replay identity, mission summaries, diagnostic labels and critical
alert presentation. The screen integration is only partially reviewed. This is desktop
presentation behavior; robot controls and the library candidate are unchanged.

## Confirmed findings and changes

- The dashboard accepted any replay frame whenever replay mode was active. During a primary
  recording switch it could expose the previous recording, and live rewind could borrow an
  unrelated archive. The shared selection gate now requires exact, nonblank session identity;
  live rewind expects the live telemetry session. Loading or mismatched frames remain absent.
- Historical data could be described as live/fresh, disconnected live rewind as offline,
  and a selected recording with no matching frame as already replaying. Historical freshness
  and waiting-for-evidence summaries now distinguish these states. Simulator telemetry is
  labeled SIMULATED, without claiming all values are ground truth or all simulators use dyn4j.
  Hardware explanations allow both measured and estimated values.
- Missing overrun/brownout counters defaulted to zero, invalid numeric values could enter
  nominal summaries, and an active alert could coexist with an all-systems-nominal statement.
  Validated battery, period/frequency and counter domains preserve unknown values. Positive
  counts, active alerts and battery/loop warnings take precedence; otherwise incomplete
  evidence stays neutral. The healthy summary is limited to observed metrics. Unknown battery
  voltage is described as unknown, rather than asserted low during a brownout warning.
- Resolved alerts and other recordings could appear as current live alarms; alert selection
  depended on input order. Current status now requires connection, live mode, matching session
  and no resolution timestamp. Triage is distinct from resolution. Priority selects critical
  categories first, then newest source timestamp and lexical ID, without negating Long values.
  CAN matching respects token boundaries instead of matching cancelled, mechanical_warning
  or scan_complete. Other existing category recognition is preserved.
- Popup state retained old record copies and dismissal could be lost on updates. The actual
  Compose hook derives current records and retains only active dismissed IDs within a live
  source/mode scope. Resolved records disappear; reactivation after an observed resolution
  can appear again. A bounded-height lazy list renders visible popups and allows scrolling.
  Mission values and priority are computed once per immutable snapshot; priority scans once
  with cached best-category state. Redundant replay-state collection was removed.
- Diagnostics claimed fixed network ports, displayed source timestamps as wall-clock times,
  and could format invalid values. Transport labels now reflect NT4 or XRP without invented
  endpoints; historical diagnostics omit current endpoints. Alert details explicitly use
  source milliseconds and finite peaks. Store ingestion uses frames/s, missing values use --,
  and warning/unknown summaries no longer receive healthy styling. Diagnostic fields wrap.

## Validation

The baseline reproduced 17 failures in a 19-method selection; critical-before-routine
priority and the live 500 ms freshness boundary already passed. Evidence is preserved in
`ARESLib-Kotlin/build/audit-pass28-before.log` and `audit-pass28-before.xml`.

There are 27 added methods: 19 mission/selection cases, six alert presentation cases and
two headless render fixtures. Alert cases include Long endpoints, owned filtered snapshots,
triage versus resolution, source-time formatting, and an actual virtual-time Compose
lifecycle across dismissal, peak update, resolution, reactivation, source change and disable.
Existing mission assertions were updated to require explicit observed counters and session
identity. The focused selection passed 51 methods (`audit-pass28-focused.log`).

The full Studio gate passed in 4m 41s (`ARESLib-Kotlin/build/audit-pass28-studio.log`):

- 1,562 ordinary methods passed, with six opt-in skips, plus 56 dashboard smoke methods and
  the performance-baseline method. Kover verification, release alignment and production
  Kotlin file-size checks passed.
- Kover source counters: replay selection 3/3 lines and 12/12 branches; mission models
  107/108 lines and 112/124 branches; alert presentation 38/38 lines and 57/68 branches;
  mission header/details 204/233 lines and 94/152 branches; popup stack 36/37 lines and
  4/12 branches. Counts are retained in `ARESLib-Kotlin/build/audit-pass28-kover.xml`.
  Default/null alternatives and UI navigation, modal interaction and callback paths remain
  outside this executed coverage. Full file review does not mean every branch executed.
- Dashboard load was 21.7813 ms, scrub p95 24.3215 ms and rapid-seek burst 5.4588 ms.
  The fixture persisted/restored 12,000 frames without drops; this is desktop fixture timing,
  not physical loop timing. Snapshot: `ARESLib-Kotlin/build/audit-pass28-dashboard-smoke.json`.
- Validation used unchanged isolated candidate `17.0.3-rc.b81c0156add9` from the local release
  repository. Robot consumers were not rebuilt for these Studio-only changes.

The initial transparent-canvas PNGs obscured translucent chips. The fixture was corrected
to use the app's opaque background; its two methods passed again in 31s
(`ARESLib-Kotlin/build/audit-pass28-render-final.log`). All six final PNGs under
`ARES-Analytics/app/build/diagnostics/mission-audit/` were inspected: simulator, rewind,
missing evidence, low battery, XRP and a 20-alert bounded stack. Source/freshness labels,
unknown metrics, warning styling and source milliseconds are readable. This test-only
background change followed the full gate; production code did not change afterward.

Repository policy passed (`ARESLib-Kotlin/build/audit-pass28-policy.log`), including shared
guidance and local links in 189 current documents. The staged inventory contains 2,439 files:
137 fully reviewed, 54 partially reviewed and 2,248 pending, with no stale or orphaned
records. These accounting states do not establish universal line or branch coverage.

## Limits and remaining scope

Session identity does not establish a target epoch when upstream live targets reuse the
same session ID. Alert-engine source resets, windows, timestamps, peak/reopen behavior and
recording lifecycle still need review. A conflated resolution/reactivation with the same ID
cannot be distinguished if the UI never observes the resolved state. The popup state is
bounded by current active IDs, not a new global engine history cap.

Timeline metadata and readiness still read their own replay-engine state; this pass gates
the selected frame consumed by mission health and the widget host. Remaining timeline and
widget behavior is open. Source badges identify transport context, not an attestation of
every frame's provenance. Headless scenes do not establish visible Studio operation, modal
interaction, device behavior or physical electrical safety. Prior intermittent checks,
opt-in tests, database/transport scopes and hardware validation remain open. Changes are local.
