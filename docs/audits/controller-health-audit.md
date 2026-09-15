# Controller health source and presentation audit - pass 27

This pass covers controller health parsing, bounded live observation, replay selection and
card presentation. The dashboard mission summary and widget card share one observation.
It also reviews the existing platform battery display policy and small health regression
suites. Widget hosting/registry and the larger dashboard screen receive scoped changes and
remain partially reviewed. Library and robot source identities are unchanged.

## Findings and changes

- Live collection and a sequence-keyed replay effect wrote the same card fields. A stream
  update could overwrite the selected recording, a replacement frame reusing a sequence
  could be ignored, and leaving replay/offline retained old values. Source resolution now
  selects one immutable replay snapshot, unknown replay-loading data, fresh live observation
  or an empty offline observation. The Compose collector is cancelled when live is inactive.
- Four substring searches accepted unrelated mechanism/setpoint topics, ignored documented
  Battery/Voltage aliases and chose whichever map entry happened to appear first. Shared
  exact topic definitions now follow the metric catalog and known producer diagnostics.
  Canonical keys precede aliases, normalized duplicate spellings resolve deterministically,
  and a present invalid canonical value remains unknown rather than being hidden by an alias.
- Nonfinite/negative readings, impossible loop-frequency conversions and non-integral or
  out-of-range counters became plausible values, healthy zeroes or saturated integers.
  Shared validation preserves unknowns; genuine zero voltage/counts remain valid evidence.
  Runtime booleans accept exactly zero/one. Missing runtime status no longer means inactive.
- Missing counter labels now show -- with unknown styling. Live logging metrics are hidden
  in replay/offline cards so current robot logs do not appear to describe a recording.
- Separate screen/card stream listeners, repeated parsing and independent state writes are
  removed. One screen-owned observation flows through the widget host/registry. Its storage
  is fixed to recognized health keys and its presentation samples at 10 Hz, regardless of
  unrelated topic bursts. Standalone cards own the same cancellable observation path. The old
  incremental runtime updater is removed; its regression cases now exercise the shared parser.
- Per-topic desktop receipt age prevents unrelated fresh telemetry from keeping old health
  readings current. Each live value becomes unknown after two seconds without a newly
  observed publication. Epoch changes clear cached values, and old queued frames must still
  match the telemetry store's current notified frame. The epoch is captured before validation
  and checked again afterward: a deterministic callback-driven target switch previously let
  an old frame become the new target's observation. A switch after validation stores the old
  captured epoch, which the next snapshot clears. Retained UI history has no receipt-age
  evidence and is skipped when a new live subscription begins. Overall link activity age
  remains distinct from per-topic age and uses monotonic time with ordinary signed wrap.

## Validation

The original 11-method parser selection reproduced nine failures; baseline log/XML are in
`ARESLib-Kotlin/build/audit-pass27-before.log/xml`. Producer counter aliases and missing fields
already passed. The first focused run passed 46 methods and rendered offline/replay cards.
Its headless images were inspected: unknown fields show --, selected replay values retain
their expected units and frequency, and source badges/layout are visible.

A second focused run passed 50 methods, including an actual headless Compose transition:
live -> replay -> loading -> live -> offline, with frame values and exact subscription counts
checked. Reused replay sequence numbers update correctly, retained history does not become a
new live observation, and scene disposal releases its collector. The composition fixture now
uses an explicitly controlled monotonic clock as well as virtual scheduling.

Four additional platform-policy tests cover invalid XRP configuration, inclusive configuration
endpoints, caution ceilings, threshold equality, both twelve-volt platforms and absent/nonfinite
voltage. The first full Studio gate passed in 3m 30s (`audit-pass27-studio.log`). A target-epoch
validation race was then reproduced (`audit-pass27-epoch-before.log/xml`): an old value of
99.0 survived a target change during frame validation. The corrected collector preserves its
captured epoch instead of assigning the frame to a newly read one. The independently clocked
composition case passed in that reproduction run.

Final validation passed:

- 32 added test methods. The final Studio gate passed 1,535 ordinary methods with six opt-in
  skips, plus 56 dashboard smoke methods and the performance-baseline method. Kover
  verification, release alignment and production file-size checks passed in 4m 27s.
  Final log: `ARESLib-Kotlin/build/audit-pass27-studio-final.log`.
- Kover source counters: health models 59/59 lines, 78/82 branches; observation/Compose hook
  53/54 lines, 58/66 branches; replay extraction 13/13 lines, 9/12 branches; platform policy
  27/27 lines, 28/28 branches; card 100/144 lines, 45/114 branches. Missing paths include
  nullable/normalization alternatives, default/lifecycle paths and card branches outside
  the three headless fixtures, including optional dashboard-runtime rows and fallback wiring.
  These are observed coverage counts, not a claim that every UI branch or failure interleaving ran.
- Offline, replay and selected-runtime/unknown-feedback PNGs were inspected under
  `ARES-Analytics/app/build/diagnostics/health-audit/`. Layout, badges, units and unknown labels
  are readable. The composition lifecycle test separately verifies the actual shared hook.
- Dashboard load 15.7467 ms, scrub p95 18.9577 ms and rapid-seek burst 3.5874 ms; 12,000 frames
  persisted/restored without drops in that fixture. Snapshot:
  `ARESLib-Kotlin/build/audit-pass27-dashboard-smoke.json`.
- The unchanged library candidate `17.0.3-rc.b81c0156add9` was validated from the isolated local
  release repository. Robot consumers were not rebuilt for these Studio-only changes.

Repository policy passed, including shared guidance and links in 188 current documents.
The staged inventory contains 2,433 files: 127 fully reviewed, 54 partially reviewed and
2,252 pending, with no stale or orphaned records. Review accounting is not universal
line/branch coverage or complete hardware validation.

## Limits and remaining scope

Receipt freshness proves desktop observation age, not when a robot measured a retained NT4
value. A producer that publishes a static configuration only once will become unknown after
two seconds; the display no longer asserts current confirmation without another observation.
Upstream values are not an atomic multi-topic snapshot; the store/fan-out synchronization and
raw transport deserve their own full audit. Presentation polls at 100 ms, so target changes
can remain visible until the next sample. This read-only display is not a control lease.
Signed elapsed intervals must remain below 2^63 ns; backward-clock freshness is unknown
rather than a fabricated age. Counters outside the current Int display model remain unknown.

Replay snapshots intentionally retain historical values until changed by the recording;
this pass does not infer missing per-topic historical sample ages. Canonical topic priority
is deterministic rather than a cross-source sensor-fusion policy. Card thresholds describe
existing dashboard policies and do not establish robot electrical safety. Rendered fixtures
are headless Compose scenes, not a visible Studio launch or physical hardware validation.
Dashboard selected-session/frame identity and mission semantics outside the replaced health
pipeline, widget registry/host performance, transport freshness and remaining diagnostic/schema/
backup behavior stay open.
Prior intermittent checks and opt-in/hardware validation remain open. All changes stay local.
