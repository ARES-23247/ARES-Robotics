# SysId preview isolation audit

Pass 250 follows the [control authority audit](sysid-control-authority-audit.md) with
the active teaching preview and dormant Studio SysId entry points. No library or robot
controller code changes in this pass.

Studio source tree: `29c85ff64dd5d57ea38b240b93fa3a1c5c3c6dda`.
Unchanged ARESLib source tree: `526a048d8dfb1092e89be95c8e906e9fbc516027`.
Validation candidate: `17.0.42-rc.526a048d8dfb`.

## Reproduced findings

All six baseline regression methods failed against the original preview implementation:

- Changing mechanism while a preview was queued could publish the old mechanism's result.
- Duplicate pending requests and superseded queued requests both performed duplicate work.
- The real teaching simulation called the publishing analysis API and replaced the existing
  measured recommendation with a recommendation whose source was the digital twin.
- Preview exceptions escaped the view-model scope, including errors from obsolete requests.

The recommendation test uses the real teaching plant and regression service with a mocked
database and transport. It observes a changed recommendation; it does not claim that synthetic
gains reached a robot or bypassed the separate promotion checks.

## Corrected preview ownership

`SysIdSimulationPreview` owns one cancellable preview job, mechanism and generation. Repeated
pending requests for the same mechanism share that work. A mechanism change cancels queued
work and invalidates obsolete completion; reselecting the same mechanism preserves the job.
Successful or failed lessons can be retried. Exceptions become preview status messages, and
owner cancellation releases the preview busy state.

The computation calls `computeSampleAnalysis`, which returns analysis without publishing
measured tuning state. A lesson preserves both the current measured recommendation and its
apply state. An offline lesson still returns teaching evidence without sending commands.

Preview progress has its own `isSimulationRunning` state. Starting an authorized measured
routine or calibration cancels pending teaching work; previews are blocked while live
collection or analysis is busy. The lesson button reflects this state and prevents duplicate
clicks. Tests exercise queued and in-computation selection changes through a controlled
dispatcher. Cancellation prevents queued computation and discards obsolete results; it cannot
immediately interrupt synchronous math that has already started on a worker thread.

## Redundant entry points

Source call-site review found no production callers of the old SysId session/file/source-edit
intents or their state fields. Their handlers and unused database, regression and driver-analysis
constructor dependencies are removed. The active file-import services and reviewed tuning
proposal flows retain their existing ownership. In particular, constructing this view model
no longer requests the driver-analysis service solely for a dormant history branch.

The unused collector parser forwarder and its one forwarding-only test are removed; all 20
strict parser regressions remain. This also removes that test's unrelated database fixture.

`Nt4ClientService.emitReplayFrame` also had no production callers. Current dashboard replay
consumes immutable `ReplayFrame` snapshots selected by `selectDashboardReplayFrame`. The
unused hook is removed and its existing alert/field tests inject into their owned TelemetryStore
directly. This is API cleanup, not evidence that current dashboard replay was injecting control
frames. Broader telemetry source provenance remains a separate review boundary.

## Validation

Focused validation passed 92 tests across eight suites: 16 new preview regressions,
31 acknowledgement cases, 20 strict parser cases, five digital-twin cases, two alert cases,
ten field subscriber cases, six replay field snapshot cases and two replay dashboard cases.
All six baseline failures pass in both focused and full validation.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,452 | 6 |

Full Studio validation has 2,501 passing results, zero failures/errors and six unchanged opt-in
skips. Focused results are not counted twice. The change adds 16 tests and removes one test of
a retired forwarding API. All 410 unchanged library candidate file hashes were rechecked.
Monorepo policy passed, including 412 current-document links and 38 historical records.
Evidence-local test startup heap remains 32 MiB with one worker; maximum heaps, assertions
and shared build settings are unchanged.

## Coverage and limits

The ledger accounts for 3,084 tracked files: 1,412 reviewed, 198 partially reviewed and
1,474 pending, with zero stale or orphaned records. There are still 1,672 files requiring full
review completion. Three previously pending files received a partial review; the new helper,
regression file and report are fully reviewed. New files increase the denominator and do not
count as completing old partial runtime files. These are file-review counts, not executable
line coverage or universal hardware validation.

The view model, generator, collector, NT4 client, workspace graph, tuning screen and existing
alert/field integration fixtures retain partial scope. Outstanding work includes configuration
and proposal races, in-flight wire ownership, broader telemetry lifecycle, fixture cleanup,
and rendered UI verification. There was no rendered-window inspection, physical hardware/HIL,
robot-loop timing measurement, external CI run, push, merge or release in this pass.
