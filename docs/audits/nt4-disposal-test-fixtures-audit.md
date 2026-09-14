# NT4 disposal and integration fixture audit

Pass 251 follows [preview isolation](sysid-preview-isolation-audit.md) with terminal NT4
resource ownership and the existing alert/field integration fixtures. ARESLib is read-only.

Studio source tree: `bb34066f7c934fe08cf426fe1836aad28917a9d1`.
Unchanged ARESLib source tree: `526a048d8dfb1092e89be95c8e906e9fbc516027`.
Validation candidate: `17.0.42-rc.526a048d8dfb`.

## Confirmed findings

Four of five initial disposal regressions failed. Normal terminal disposal, failed final
persistence, caller cancellation and repeated disposal all left the private service owner
active. The positive ordinary-stop case passed. The tests inspect the actual owned coroutine
job and clean it up even when a baseline assertion fails.

`Nt4ClientService.disposeAndJoin` stopped connection jobs but did not cancel or join its
service scope. That scope also owns raw telemetry observation, the coalesced UI worker and
drive-frame analytics. Terminal disposal now joins it in a noncancellable `finally` block.
Ordinary `stop` keeps its reusable scope. Failed persistence still reports failure and retains
retry frames; an explicit later flush can save them. Tests also hold a worker's cleanup and
verify that disposal waits until it finishes.

## Fixture efficiency and evidence

The seven pre-existing alert scenarios constructed real databases and NT4 clients even though
they only asserted rule evaluation. Their timers also used real scheduler waits; default audible
rules could invoke the host audio device. A shared test fixture now uses the actual TelemetryStore,
rule engine and persistence worker with a controlled dispatcher, recording database mock and
silent audio callback. Configuration files live in one owned temporary directory. Cleanup drains
accepted writes and removes the files even after a test-body failure. Existing alert scenarios
and threshold assertions remain, with additional negative-evidence and persistence assertions.

The seven field integration fixtures keep the real NT4 decoder and subscribers while replacing
unrelated database construction with a mock. Each owns a child coroutine scope and joins it
before terminally disposing its NT4 client. All ten original field test methods and pose,
alliance, replay-gate, game-piece and topic-filter assertions remain.

The local NT4 transport test previously used fixed port 5818, an unclosed database, an unjoined
subscriber scope and success-only server cleanup. It sent commands and drive frames without
asserting their receipt. It now uses an OS-assigned loopback port, condition-based readiness,
actual server readback of INIT/START and both neutral/nonzero drive frames, and nested cleanup
for all owned resources. The unused pose-history sampler is removed from this test. Its
prepublished estimate and motor-power assertions are retained. The test exercises a local
server and field consumer; no robot, OpMode, physics engine or rendered Studio window runs.

In total, 15 pre-existing tests no longer construct real database instances. The alert scenarios
remove 1.4 seconds of configured fixed sleeps and the transport test removes 1.2 seconds of fixed
post-action waits, replacing them with observed conditions where real transport is involved.
Those source-level reductions are not a wall-clock benchmark or a physical loop-time result.

## Validation

Focused validation passed 81 tests across nine suites: six terminal-disposal cases,
28 existing NT4 client cases, eight revised alert cases, 14 alert lifecycle cases, eight alert
persistence cases, ten field subscriber cases, six XRP link cases and one local NT4 integration
case. All four original disposal failures and the reusable-stop positive case pass in focused
and full validation. Every original method from the four revised test files remains present.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,459 | 6 |

Full Studio validation has 2,508 passing results, zero failures/errors and six unchanged opt-in
skips. Focused results are not counted twice. Seven test methods are added; no methods are
removed. All 410 unchanged library candidate file hashes were rechecked. Monorepo policy
passed, including 413 current-document links and 38 historical records. Evidence-local test
startup heap remains 32 MiB with one worker; maximum heaps and shared settings are unchanged.

## Coverage and limits

The ledger accounts for 3,087 tracked files: 1,419 reviewed, 195 partially reviewed and
1,473 pending, with zero stale or orphaned records. There are 1,668 files still requiring full
review completion, down from 1,672. Three previously partial fixtures and one pending transport
test are now reviewed. The new helper, disposal regressions and report are also reviewed.
These are file-review counts, not universal executable line coverage or hardware validation.

The four existing test files and new test helper/regressions are reviewed as test artifacts;
that does not claim every production branch they touch has coverage. NT4 source retains
partial scope for broader ingress, recording, concurrent control publication and connection
lifecycle review. This pass does not add hardware/HIL evidence, change release versions, push,
merge or release anything.
