# Tuning proposal eligibility and delivery audit - pass 20

This pass follows recommendations from AutoTuner through the local inbox into a loaded
Tuning board. It reviews the inbox and extracted atomic staging helper in full; the larger
AutoTuner, TuningViewModel and SysIdSignalGenerator files remain partially reviewed.
Library source identity and robot consumers are unchanged.

## Findings and changes

- LINEAR/ANGULAR proposals contain only three feedforward coefficients. They were rejected
  when no usable step response existed, despite an identifiable feedforward fit. Their
  eligibility and approval now require the feedforward fit, data quality and applicable
  coefficient envelope. Feedback-model quality and diagnostic feedback gain limits no
  longer reject values that do not include feedback. Flywheel proposals still require a
  usable step model and validate all six proposed coefficients.
- Feedforward-only confidence normalizes the existing relevant weights to 0.75 for fit
  quality and 0.25 for data quality. Flywheel retains the 0.60/0.20/0.20 fit/model/data
  weights. Non-finite confidence becomes zero; non-finite or out-of-range R-squared rejects
  eligibility. READY also requires the documented review-fit threshold of 0.70; the
  0.45 rejection floor and 0.78 ready-confidence threshold remain.
- A SharedFlow with no replay reported successful emission while dropping proposals with
  no subscriber. It also broadcast to concurrent boards and retained caller-owned maps.
  The inbox now owns a FIFO queue of at most eight proposals, snapshots an immutable values
  map, rejects empty/non-finite inputs, and reports capacity exhaustion without dropping
  existing work. Pending-count state wakes receivers after they become ready. Concurrent
  boards compete for delivery rather than each receiving the same proposal.
- Delivery uses a synchronous receiver and removes the queued proposal only after it acknowledges consumption.
  A throwing receiver retains it. The board waits for a selected, fully loaded profile,
  then stages or explicitly rejects each complete proposal. If a project switch makes the
  receiver unavailable before atomic staging, it defers acknowledgement and retains the
  proposal. Reload revisions keep readiness notifications observable even when intermediate
  loading states conflate. The queue survives absent
  boards during the current application process; it is not persisted across application exit.
- The former board staged individual entries before validating the whole proposal. Invalid
  later entries could leave earlier entries staged, and an external proposal could overwrite
  a student edit. The extracted pure helper converts numeric types without truncation,
  checks declaration/range/vendor policy for the whole proposal, and rejects conflicting
  staged values. It stages all accepted values together with their evidence metadata and
  preserves the provenance of identical existing values. Rejection changes no staged value.
- Starting a project load now clears old catalog/profile readiness. A failed load cannot
  consume new proposals against the previous project's catalog under the new path. A load
  generation is assigned atomically at the call site and checked inside state updates,
  preventing an older asynchronous result from replacing a more recent request. Readiness
  is invalidated synchronously when the new load is requested.
- AutoTuner now checks the inbox return value. Full or invalid submission reports failure
  and permits retry; success says queued rather than claiming board review or robot application.
  Driver/calibration producer messages use the same distinction.

Canonical review, evidence checks, confirmation and explicit live-test acknowledgement
remain downstream requirements. Queue acceptance is not promotion, application, or verified
hardware provenance. All changes remain local.

## Validation

New tests cover smooth identifiable excitation without a step; required flywheel models
and feedback envelopes; unused diagnostic feedback gains; finite/poor fits; full-queue
failure/retry; FIFO, absent receiver, immutable ownership, throwing and concurrent receivers;
atomic typed staging, undeclared/range/vendor/non-numeric rejection, conflicts and provenance;
and real board loading/delivery with unchanged canonical files and no NT4 writes.

The initial focused run exposed invalid test fixture keys and an existing test that altered
an unused drivetrain feedback gain. Fixture keys now follow the canonical schema. The existing
safety assertion alters a proposed feedforward coefficient and its matching map entry; a
separate flywheel test checks the actual feedback envelope. Acceptance, recovery and stability
thresholds in the Monte Carlo suite remain unchanged.

All **50 initial focused methods passed**. Ten board methods then passed the targeted
load-order check, including a controlled delayed old load completing after a newer profile.
A further queue deferral regression brings this pass to **22 new regression methods**. The final
Studio gate passed in 3m 26s: **1,392 passing tests and six opt-in skips** (app: 1,357
passing; shared: 17; gateway: 18), plus **56 dashboard smoke tests** and **one performance
baseline**. App tests executed; shared/gateway reused unchanged results. Coverage verification,
release alignment and the production Kotlin file-size ratchet passed. Final monorepo policy,
shared guidance and links in 181 current documents passed
(`ARESLib-Kotlin/build/audit-pass20-policy-final.log`).

| Source | Executed lines | Executed branches |
| --- | ---: | ---: |
| TuningProposalInbox | 24/24 | 14/14 |
| ExternalTuningProposalStaging | 25/25 | 30/30 |
| AutoTunerService | 164/185 | 125/180 |
| TuningViewModel | 119/328 | 74/334 |
| SysIdSignalGenerator | 69/182 | 29/110 |

The larger view models retain substantial uncovered behavior; this pass does not claim
complete behavioral coverage from the new helpers' complete line/branch execution.

Validation used the unchanged local library candidate `17.0.3-rc.b81c0156add9`:

```powershell
.\gradlew.bat :shared:test :gateway:test :app:test :app:koverXmlReport :app:koverVerify verifyReleaseVersionAlignment verifyProductionKotlinFileSizes --no-parallel '-ParesVersion=17.0.3-rc.b81c0156add9' '-ParesRepository=file:///C:/Users/david/dev/robotics/ARES-Robotics/ARESLib-Kotlin/build/release-repository' --console=plain
```

Run from `ARES-Analytics`. Evidence logs are
`ARESLib-Kotlin/build/audit-pass20-focused-final.log`, `audit-pass20-load-order.log` and
`audit-pass20-studio-verified.log`; the
initial failed focused run is retained as `audit-pass20-focused.log`. Kover XML is
`ARES-Analytics/app/build/reports/kover/report.xml`. Robot/library suites were not rerun
for Studio-only changes.

An intermediate complete run passed all app tests but failed the unchanged dashboard
performance baseline: replay-scrub p95 was **105.8996 ms**, above the **100 ms** allowed
limit (10 ms baseline with a 9.0 relative regression allowance). Its cause is unproven;
replay implementation and thresholds were unchanged. The failure is preserved in
`ARESLib-Kotlin/build/audit-pass20-replay-performance-failure.json` and
`audit-pass20-replay-performance-failure.xml`; the run log is `audit-pass20-studio-final.log`.
The final gate remeasured this metric because the queue/load revisions changed after
the intermediate compile. It measured **33.7928 ms** and passed the unchanged baseline
(`ARESLib-Kotlin/build/audit-pass20-replay-performance-final.json`). That later measurement
does not resolve the prior failure.

## Remaining scope

The queue has bounded proposal count, not a byte-size limit or persistent storage. It does
not carry an authenticated robot/project identity; the loaded board's canonical declarations
and explicit review remain necessary. Durable evidence identity, import/simulation provenance,
stale recommendation approval and legacy apply/rollback states still need review. The rest
of TuningViewModel's live nonce/acknowledgement, promotion, profile-switch and telemetry logic,
and SysIdSignalGenerator's arm/lease lifecycle remain open. Integration tests exercise readiness, failed-load isolation and a controlled out-of-order
load completion. Queue tests also verify deferred acknowledgement. These tests do not
exhaust every scheduler interleaving. No visible Studio window, physical robot, loop jitter or device deployment was
validated. Prior timing-sensitive test failures and opt-in tests remain separately recorded.
