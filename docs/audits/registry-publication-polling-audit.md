# Registry publication, polling and identity audit

Pass 217 audits the remaining registry publication and polling boundaries, including
hardware identity, cached power feedback and numeric counters. Ten regressions reproduced
failures against unchanged production code. The fixes preserve public API descriptors and
the previous shutdown/refresh behavior while isolating each telemetry producer and each
polling generation.

## Confirmed findings and fixes

- **One broken producer hid healthy telemetry.** The old catch surrounded the entire
  publishing loop, so a device failure or failed heartbeat write skipped later producers.
  Exceptions, device-origin index errors and serious errors are now isolated per entry.
  A failed device receives no success heartbeat; a failed sink write cannot prevent the
  next producer from being attempted.
- **Registration changes mixed frames.** Device, prefix and heartbeat came from separate
  mutable lists. A callback replacing itself could acquire the replacement's heartbeat;
  replacing a later device changed the current pass midway through publication. Each pass
  now captures one immutable array of device/prefix/heartbeat entries. Changes appear on
  the next pass, including changes from another thread. Three parallel metadata lists,
  including an unused names list, were removed.
- **A retired worker read replacement hardware.** If a primary poll ignored interruption,
  returned after bounded close, and a new round-robin device had been registered, the old
  worker entered the new secondary lane before checking its generation. Both lane arrays
  are now captured together, and generation checks occur before polling and between lanes.
  The deterministic baseline observed one replacement read from the retired worker; the
  fixed test observes zero.
- **Equality collapsed distinct physical owners.** Auxiliary closeables, polling lists,
  motor lists and current-source lists used value equality for deduplication or removal.
  Distinct but equal objects could miss reads/closure and disappear from power feedback.
  Identity comparisons now preserve every physical object while suppressing repeat
  registration of the same object within each ownership list. Cached motor/current
  removal also uses identity. Removing the winning raw/normalized motor-name alias
  restores the surviving named motor rather than losing its lookup.
- **Unbounded counters could lose validity.** A Long heartbeat converted to Double stops
  changing on every increment past the exact-integer precision boundary. Heartbeats now
  wrap from 2^53 - 1 to one. Polling cursors wrap within the captured array length rather
  than allowing a lifetime Int counter to overflow. Consecutive failure counts saturate
  at Long.MAX_VALUE and reset after a successful read.

Polling uses immutable registration arrays and worker-owned primitive failure counters,
removing a concurrent-map lookup on every successful read and boxed-counter updates on
failure. The same device in both explicit polling lanes retains a shared failure streak;
each lane remains independently scheduled. Close discards those entries, so a late retired
worker cannot mutate replacement counters. This is not cancellation of an in-flight
physical SDK call.

## Tests, review and limits

The unchanged-production evidence contains eleven distinct test methods: ten failures
and one passing allocation control. The heartbeat probe initially sampled three values,
which missed a round-to-even duplicate; the corrected four-value probe reproduced it.
The final suite has thirteen new methods plus twenty-two existing registry methods,
all passing. It includes ordinary/serious device errors, heartbeat sink failure, reentrant
and concurrent registration, both polling lanes, blocked-generation handoff, equal
physical objects, current totals, named alias restoration and live cached-view cleanup.

Seeded tests cover the Double precision boundary, the exact heartbeat wrap, negative
and Long.MAX_VALUE seeds, and failure-count saturation/recovery. Reflection seeds actual
counter state while the worker is blocked behind latches; it does not substitute a model
for the production path. Cursor bounds are additionally established by source review:
the cursor starts at zero, is checked against the captured size, and increments only when
strictly below the last valid index. It therefore cannot reach a negative overflow value.

The existing registry test file received a complete fixture/body review. Two fixed-sleep
waits were replaced with observed polling events and joins. Round-robin fairness is checked
after the registered population is stable; deliberately blocked workers are always released
and joined, including assertion-failure cleanup.

After warmup, the final focused desktop JVM measurements were 832 bytes for 10,000 publish
passes over 32 telemetry aliases and zero bytes for 10,000 refresh/safety pairs over 32
aliases. The original publication control measured 896 bytes. These support bounded host
allocation, not a timing improvement claim or physical robot loop deadlines. Registration,
real telemetry sinks and exponentially limited failure diagnostics may allocate.

HardwareRegistry remains partially reviewed. The combined passes cover refresh/shutdown
identity, current/motor aliases, telemetry snapshots, regular/round-robin polling and tested
generation handoff. Topology metadata replacement/atomicity, concurrent cache-view mutation
across external power-manager iterations, and unusual worker termination/restart boundaries
still need a separate pass. Owners must quiesce registration and foreground callbacks before
close, as documented. A bounded join cannot cancel an unresponsive vendor call; Java tests
do not establish physical device quiescence, sensor timing or motor safety.

## Candidate validation

Source commit: `6c771860e9a82b51cbdd00ce3399d4bf41f8e308`.
Library tree: `e7609c4def7fe6d8b96b387f252da2b6b7ed92ac`.
Candidate: `17.0.24-rc.e7609c4def7f`.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,542 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,020 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |

The suites account for 5,351 passing results, zero failures/errors and six existing Studio
skips. These cover three opt-in starter integration scenarios, native file chooser, dashboard
performance baseline and physical dashboard validation. FTC/starter generated-project checks
and APK assembly passed; FRC/starter generated-project checks passed. All 410 candidate files
were hashed and reverified after consumer validation. Monorepo policy passed, including
source/version/archive identity, shared guidance and links in 378 current documents, with
38 explicitly historical records skipped. Four rebuilt archives differ only in version properties.

Evidence directory: `ARESLib-Kotlin/build/audit-pass217-verified-evidence/`, including
baseline/focused/full XML, logs, counter diagnostics, candidate hashes and archive comparisons.
Cached/up-to-date suite results are included; focused tests are not counted twice.

The ledger accounts for 2,952 tracked files: 1,183 fully reviewed, 168 partially reviewed
and 1,601 pending, with zero stale or orphaned records. This pass adds the regression file
and this report and completes the existing registry test-file review. HardwareRegistry remains
partial for the broader metadata/lifecycle boundaries described above; no whole-hardware claim.

No physical robot, target MicroPython runtime, rendered Studio window, remote workflow,
deployment or public release was exercised. This pass found ARES registry issues, not a
verified WPILib or device-vendor defect. The repository-wide audit goal remains open.
