# Phoenix reader binding integration

Pass 51, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Scope and result

The preceding reader audit left the Phoenix source adapter unexecuted by its acquisition
fixtures. This pass closes that mock-integration gap. Seven tests now execute the real source
adapter and the public reader constructor through mocked Phoenix device/signal classes.
No additional production defect was found; no production Kotlin behavior changed.

The source adapter, new fixture and test file were read in full. The FRC Gradle file was also
reviewed in full: library/publication plugins, vendor and test dependency scopes, desktop native
extraction, test JVM selection, native lookup paths and test logging. Its observed execution is
Windows-specific; other host paths and physical deployment are not claimed as executed here.

## Tested boundaries

- All 36 signal positions map to the expected named current, encoder, IMU or drive/steer fault
  source. Each original signal is cloned once; subsequent refresh/value/status/timestamp access
  uses the clone. Tests change every clone's status and timestamp validity independently.
- Configuration requests the reviewed current/encoder/pitch/roll/diagnostic rates, leaves yaw/rate
  frequencies unchanged, and does not request unused steer-current signals. A failed status is
  not hidden by later successful requests. Construction rejects configuration failure; thrown
  vendor configuration errors retain their original identity and do not close borrowed devices.
- Source ages use one captured vendor time. Repeated age reads remain on that reference until
  another capture. Timestamp latency getters, which would read the native clock again, are never
  called. The state path selects getStateCopy, never the shared getState object.
- The public reader refreshes clones once, captures one owning state and one vendor time, then
  serves cached values without more native calls. Refresh/state exceptions revoke previous
  availability, and a complete later refresh can restore measurement validity.

These checks test the adapter's binding choices and failure behavior, not merely a replacement
implementation of the reader algorithm. Mocked final vendor APIs bypass device constructors and
motor commands. Class initialization still loads the desktop CTRE simulation runtime; logs show
the simulated CAN runtime and clean library shutdown. No physical devices were opened or commanded.

## Evidence and limits

The first public-constructor integration test passed in 16s. The expanded seven-method gate
passed in 8s with API checks and Kover. Source-adapter coverage is 34/34 lines, 10/10 methods and
72/82 branches. Remaining generated null/range alternatives are not claimed as exercised.
Mocks validate calls, data ownership and errors; they do not prove firmware behavior, JNI copying,
bus latency, signal delivery, actual clone isolation inside Phoenix or physical stop response.
The prior pinned-SDK source inspection remains the supporting vendor-implementation evidence.

Mockito 5.23.0 is a testImplementation dependency. The generated POM and Gradle module metadata
exclude Mockito and Byte Buddy. Runtime JAR entry contents match the previous candidate exactly;
archive container hashes differ, so this is not a byte-identical archive claim.
Host allocation and real-device timing remain distinct from these interaction
tests; mocked calls are deliberately not used as zero-GC or real-time performance evidence.

The source adapter can now receive full source-review and mock-integration credit. Physical
integration remains an explicit external validation limitation. FRCSwerveHardwareIO and the CTRE
writer still need separate complete audits of output failure, close, vision and estimator behavior.

## Validation checkpoint

Source `817a2ac6` binds candidate `17.0.3-rc.ba1d707014f8` to library tree
`ba1d707014f8d2091e3f918d41c92044c28fe92a`. Full library/API/Kover/local-publication validation
passed in 22s with 1,372 methods and zero failures/errors/skips. FRC tests executed with Mockito;
unchanged products include explicit cache/up-to-date reuse.

FTC, FRC and their starters passed 109, 134, 14 and 34 methods, plus generated-project checks
and FTC debug assembly. Studio passed in 17s: ordinary suites reused 1,779 passing methods and
six existing opt-in skips; 56 dashboard methods and one performance baseline reran. Kover,
release alignment and production file-size gates passed against the same local candidate.

Policy verified exact source identity, unchanged archive hashes and links in 212 current
documents (38 historical records excluded). Logs, invoked XML/hash manifests, Kover and the
production JAR entry comparison are under `ARESLib-Kotlin/build/audit-pass51-*`.
The inventory accounts for 2,551 tracked files: 297 reviewed, 75 partial and 2,179 pending,
with no stale or orphaned records. The goal remains active.
No push, merge, remote publication or physical device action has occurred.
