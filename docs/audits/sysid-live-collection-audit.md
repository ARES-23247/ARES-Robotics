# SysId live collection audit - pass 16

This pass reviews live row assembly and collection in Studio. The new assembler and its
tests are reviewed completely. The collector remains partially reviewed because downstream
analysis/state publication and stronger run provenance still need their own end-to-end pass.

## Findings and changes

- Receipt of the highest channel index previously completed a row even when earlier
  channels had never arrived. A bit mask now requires every column used by that calibration.
  Out-of-order channels are supported; missing measurements never become default zeros.
- Rows were keyed by milliseconds, merging distinct source packets within a millisecond.
  Assembly now uses session identity and the preserved microsecond source timestamp.
  Duplicate completed packets are ignored. Conflicting duplicate channels invalidate a row;
  conflicting accepted payload timestamps invalidate the run rather than overwrite evidence.
- Incomplete rows bypassed the old capacity cleanup. Pending rows and recent completed-row
  identities each have a 512-entry bound. Eviction uses insertion order without scanning for
  a minimum timestamp. Invalid indices allocate no partial row. Live packed strings preserve
  column positions, validate finite values, and are limited to 1,024 characters before splitting.
- Generic motor, Pinpoint/vision, linear-distance, and track-width rows require 5, 4, 3 and 7
  columns respectively. Geometric measurements no longer appear as motor voltage/velocity.
  Payload time must be nonnegative, finite and representable in the existing millisecond model.
- Live history previously grew indefinitely and was copied twice for each completed sample.
  A run now holds at most 20,000 unique complete rows. Plot previews contain at most 1,000 rows
  and publish at most once per 100 ms after the first row. Completion publishes the full
  accepted run once. The limit is explicit: an extra row faults collection, requests stop once,
  and produces no fit from a silently truncated run. It does not claim robot stop acknowledgement.
- Clearing a run resets assembly/history and advances a generation counter. Session or
  mechanism changes invalidate collection. Replay and idle/disconnected input do not append
  live samples; late active status does not revive a locally stopped run. Starting the collector
  twice creates one subscription. Stop exceptions are surfaced and cancellation is preserved.
- Completion transfers private row ownership and publishes copies of mutable calibration
  arrays. A UI callback cannot change the fitting snapshot. Starting a new run while the stop
  callback is pending prevents the older completed analysis from replacing the new run's state.
  The same generation check contains errors from an older stop callback; mechanism selection
  changing during stop also prevents relabeling an older fit.

`SysIdViewModel` already supplies `signalGenerator.disarm` as the completion callback; this
pass preserves that path and robot-side authorization. Tests use an isolated in-process
telemetry flow and recording callbacks, with no network command or physical deployment.

## Validation

All **eleven initial regression methods failed** against the old collector. Baseline XML is
`ARESLib-Kotlin/build/audit-pass16-baseline.xml`. The final suite adds **32 passing methods**:
26 collector scenarios and six assembler scenarios. Coverage includes every calibration row
shape, out-of-order/missing/duplicate channels, source identity, malformed/oversized strings,
storage eviction, preview cadence/capacity, full completion snapshots, run overflow, mutable
array isolation, status/session/replay guards, cancellation and stop-generation races.

The full Studio gate passed against unchanged local candidate `17.0.3-rc.b81c0156add9`:
**1,311 passing tests with six opt-in skips**, plus **56 dashboard smoke tests** and **one
performance-baseline test**. App tests executed; shared/gateway reused up-to-date results.
Kover reports **53/53 executable lines (100%) and 55/72 branches (76.4%)** for the assembler;
the collector reports **105/109 lines (96.3%) and 100/122 branches (82.0%)**. These are scoped
execution metrics, not evidence of lossless transport or every analysis-service branch.
XML is `ARES-Analytics/app/build/reports/kover/report.xml`; test XML is under the app's
`build/test-results` task directories.

The configured app coverage gate, production Kotlin file-size check, release alignment,
monorepo policy, shared guidance and links in 177 current documents passed. Library source
identity and starter hashes remain unchanged; robot suites were not rerun for these Studio
changes. Logs are `ARESLib-Kotlin/build/audit-pass16-*.log`; final execution is recorded in
`audit-pass16-studio-final.log`. All tests are headless desktop tests, not a visible-window
or physical-robot validation claim.

## Remaining work

The upstream `TelemetryStore` uses a bounded, lossy fan-out. These fixes prevent fabricated
complete rows; they do not certify lossless capture, sensor freshness, or run identity from a
request nonce. Reconnection/acknowledgement provenance must be checked across the transport
and command workflow. Physical latency, jitter and neutral-output acknowledgement remain open.

Completion still performs analysis serially. `SysIdService.analyzeRawData` is called directly
and again inside `AutoTunerService.analyzeSamples`; that redundant fit and the service's mutable
recommendation publication need a coordinated analysis-service pass. Keeping it serialized here
avoids introducing asynchronous result races while changing sample ownership. No allocation or
wall-clock benchmark is claimed. The full repository audit remains active and local.
