# Platform battery alert policy audit

Pass 35 reviews XRP project minimums, battery alias handling, policy publication, cached rule
construction, configuration/evidence interaction and battery observation domains in Studio.
This is desktop diagnostic policy, not an actuator brownout controller.

## Findings and changes

- Every configureRobotContext call removed battery alerts, including unchanged-context active
  faults and resolved/acknowledged history. Configuration now preserves those records. Only
  subsequent valid observations re-evaluate them under the new policy; target resets still
  discard the previous target's in-memory evidence through the existing epoch mechanism.
- Context updates were outside the transition mutex. The method is now suspending and serialized
  with telemetry evaluation, so an engine transition cannot straddle a policy change. All
  current callers are coroutine-based and compile against the updated method. Disposed engines
  ignore later configuration calls.
- Two independent volatile fields held league and minimum, and effectiveRule read the minimum
  separately for text and arithmetic. One published context now owns both immutable values and
  its cache. Each lookup captures one context, including when UI name queries overlap updates.
- XRP rules were copied and formatted on every lookup. A context has one cache slot for each
  catalog battery key; each slot retains only the current configured/effective pair. Repeated
  settings preserve the context and cache. Replacement rules or changed settings invalidate
  the relevant identity. Unrelated signals and non-XRP contexts return the configured object.
- Configured battery aliases bypassed the XRP project minimum despite sharing the catalog's
  battery-voltage dimension. All catalog battery keys now receive that minimum while retaining
  their configured source keys, upper bounds and audio flags. This does not auto-register rules
  for previously unconfigured aliases.
- A two-sided XRP rule retained its upper bound but was labeled solely as low battery. Its
  display now includes both limits. The existing two-decimal low-only text is preserved;
  formatting happens during cache construction and does not round the actual comparison value.
- Negative battery voltage could create a fault or replace its peak. Negative observations are
  unknown for all catalog battery keys. Zero remains a valid low-voltage measurement; no
  arbitrary positive voltage ceiling is introduced. The upstream nonfinite/text and raw
  source-order gates remain in force.

The project minimum remains restricted to [3.0,6.0] V with a 4.3 V fallback for missing/invalid
input. Saved rule objects are unchanged. A user upper bound is preserved, not silently weakened
when the project minimum changes. General threshold-file validation remains a separate scope.

## Validation

The six-method baseline reproduced six failures in 26s: cache reuse, upper-limit description,
battery aliases and three evidence-loss cases. Evidence: `ARESLib-Kotlin/build/audit-pass35-before.log`
and `ARESLib-Kotlin/build/audit-pass35-before.xml`. An earlier five-method version failed in 27s
before the alias regression was added.

The focused selection passed 88 methods in 1m 20s (`ARESLib-Kotlin/build/audit-pass35-focused.log`).
A second eight-method baseline reproduced the negative-voltage failure while the other seven
cases passed, including measured zero (`ARESLib-Kotlin/build/audit-pass35-voltage-before.log/xml`, 26s).

There are 19 added methods: 11 policy/engine integration cases and eight direct policy cases.
Tests cover project endpoints/fallbacks, immutable configured fields, per-alias cache reuse,
changed rule/settings invalidation, unrelated/non-XRP identity, context/evidence preservation,
alias faults, target resets, disposal and voltage validity. A two-thread stress check executes
20,000 configuration changes alongside 20,000 lookups and checks each returned threshold/name
pair. This is executed concurrency evidence, not a proof of every scheduler interleaving.

The steady lookup allocation test measures two consecutive 10,000-lookup windows after warmup.
It measures only effectiveRule with a stable normalized key and cached configured rule, not
configuration changes, telemetry normalization, engine transitions, persistence, UI or hardware.

The final Studio gate passed in 3m 23s (`ARESLib-Kotlin/build/audit-pass35-studio.log`):

- 1,713 ordinary methods passed, six existing opt-in methods skipped, plus 56 dashboard smoke
  methods and the performance-baseline method passed. Kover verification, release alignment
  and production Kotlin file-size checks passed.
- Policy source covered 27/27 executable lines and 35/36 branches. Engine source covered 189/191
  lines and 114/140 branches. The remaining engine contracts are not closed by high line
  coverage. Snapshot: `ARESLib-Kotlin/build/audit-pass35-kover.xml`.
- All 19 added methods passed. The concurrency check executed, and two consecutive 10,000-lookup
  windows again measured zero allocated bytes after warmup; the allocation case was not skipped.
  Final XML: `ARESLib-Kotlin/build/audit-pass35-final-xml`.
- Dashboard load was 17.6506 ms, scrub p95 13.9116 ms and rapid-seek burst 4.2682 ms. The fixture
  persisted/restored 12,000 frames without drops. These desktop measurements do not isolate
  this policy's speedup or measure physical robot loop timing.
  Snapshot: `ARESLib-Kotlin/build/audit-pass35-dashboard-smoke.json`.
- Validation used unchanged isolated candidate `17.0.3-rc.b81c0156add9`. Library and robot
  consumers were not rebuilt for Studio-only changes.

Repository policy passed (`ARESLib-Kotlin/build/audit-pass35-policy.log`), including shared
guidance and local links in 196 current documents. Inventory: 2,469 tracked files, 168 fully
reviewed, 61 partially reviewed and 2,240 pending, with no stale or orphaned records. Review
accounting remains separate from executed tests and line/branch coverage.

## Limits and next work

Platform policy still comes from the selected robot context. Coordinating that context with
transport/session ownership and applying correct metadata to arbitrary replay sessions remains
open. Stored alerts do not capture an immutable threshold-policy version; UI display names use
the current rule. This pass preserves observed records across policy changes rather than
fabricating immediate physical recovery.

The engine remains partially reviewed. Temporal loop/motor overrides, general threshold-file
validation, concurrent start/stop ownership, global retention and remaining transport/store
behavior need further passes. Existing intermittent and opt-in tests remain open. No visible
Studio window, live audio, physical battery threshold or robot loop-time validation is claimed.
All changes remain local; the full monorepo audit goal stays active.
