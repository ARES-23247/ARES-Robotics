# Desktop drive diagnostics and topology contract audit

Pass 218 completes the file review of the shared desktop drive protocol, the canonical
hardware topology codec and their two original test files. Adjacent simulator receiver
and bridge changes reuse the shared wire definitions. Eighteen new test methods produced
eight failures against unchanged production code; all eighteen now pass.

## Confirmed findings and fixes

- **Expired acknowledgements reported receiver uptime as frame age.** Copying an
  acknowledgement first disarmed an expired command, clearing its acceptance timestamp.
  A frame accepted at 5010 ms and inspected at 5210 ms therefore reported 5210 ms rather
  than 200 ms. Acceptance history now has separate storage from the live lease. Clock
  rewinds report zero diagnostic age and still require neutral rearming.
- **Acknowledgement identity described frames that had not been accepted.** A first
  nonneutral frame reported a session and sequence despite failing the neutral handshake.
  Malformed input also erased the last successful identity. Acknowledgements now retain
  the last accepted session, sequence and time through rejection, new unarmed sessions
  and expiry. Before any acceptance, identity and age are -1. This matches the desktop
  sender's use of accepted sequence numbers to distinguish receipt from queued transmission.
- **Fresh frames boxed numeric metadata on the hot path.** The shared gate parsed session,
  sequence, sender time and flags as nullable Long values. The probe using three values
  outside the boxed cache allocated 720,000 bytes over 10,000 fresh-frame/acknowledgement
  pairs on the desktop JVM. A primitive Long parser with an invalid -1 sentinel preserves
  finite, integral, nonnegative/positive and exact-Double range checks. The same final
  focused probe measured zero bytes. This is allocation evidence, not a measured loop-time
  speedup or a target-device deadline guarantee.
- **Rejection counters could overflow negative.** Both receivers incremented an unbounded
  Long diagnostic counter. Both now saturate at Long.MAX_VALUE. Tests seed the real private
  counters at MAX_VALUE - 1 and submit three malformed frames. Existing Double transport
  rounding for Long diagnostic ages/counts is preserved; exact-integer limits still apply
  to incoming session, sequence, sender time and flags.
- **Topology identities were not validated at the JSON boundary.** The codec accepted blank
  robot/node IDs and repeated node IDs, which cannot uniquely identify dashboard hardware.
  Encode and decode now require nonblank robot and node IDs and unique node IDs. The
  baseline identity method failed at its first blank-robot encode assertion; static review
  established the remaining missing checks, and the final test executes all six encode/decode
  cases. Parent nodes may remain absent to represent partial discovery.

The simulator receiver and NT4 bridge now alias canonical frame sizes, indices, flags,
limits, acknowledgement constants and receiver statuses. This removes duplicate wire
definitions without combining the two receiver implementations.

## Review and meaningful tests

The 28 focused results consist of all 21 telemetry-schema tests and seven core receiver
tests. Eighteen methods are new; ten are existing. Coverage includes all 1,024 known flag
combinations, independent neutral/motion masks, malformed payload lengths, metadata
NaN/infinities/fractions/negative values/2^53, maximum exact identifiers, zero and rewound
receiver time, positive clock rollback during observe, retained-frame expiry, same-sequence
mutation, sequence/sender-clock rollback, neutral rearming and short acknowledgement buffers.
Configured speed limits cannot exceed the global per-axis caps; next-representable values
above both signs of the translation/angular caps are rejected even with huge configured limits.

Topology tests preserve the original golden JSON, all declared hardware categories and
optional metadata fields, escaped strings, empty discovery, absent parents and unknown
additive fields. Unsupported schema versions, unknown hardware types and malformed required
fields remain rejected. This does not introduce platform-specific port/CAN limits or require
a complete tree. The codec does not promise deep ownership of caller-supplied lists/maps.

Source review traced the FTC remote-drive receiver, FRC dashboard-drive receiver, simulator
bridge, desktop frame sender/acknowledgement consumer, command mapper and topology consumer.
The shared gate retains its serialized-owner, nonnegative-clock and exclusive timeout
contract. The core receiver retains synchronized immutable command ownership, signed
receiver clocks, null meaning no new payload and an inclusive 500 ms lease. Existing tests
exercise that exact lease edge and Long elapsed-time saturation. Translation limits remain
per axis, consistent with the desktop mapper; no change to motion authorization is intended.

The topology dashboard's handling of nested controller rows, registry topology metadata
replacement/atomicity, and desktop sender session/sequence lifecycle remain separate audit
scopes. This pass validates identity at the canonical codec; it does not establish correct
rendering for every possible parent relationship. No rendered UI or hardware test was run.

## Candidate validation

Source commit: `7c9c55bbbfea14e9c00d515a2d2b5280da4f53dc`.
Library tree: `9de91b51866db1217c4a221681e9157cd1398100`.
Candidate: `17.0.25-rc.9de91b51866d`.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,560 | 0 |
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

The suites account for 5,369 passing results, zero failures/errors and six existing Studio
skips. These cover three opt-in starter integration scenarios, native file chooser, dashboard
performance baseline and physical dashboard validation. FTC/starter generated-project checks
and APK assembly passed; FRC/starter generated-project checks passed. All 410 candidate files
were hashed and reverified after consumer validation. Monorepo policy passed, including
source/version/archive identity, shared guidance and links in 379 current documents, with
38 explicitly historical records skipped. Four rebuilt archives differ only in version properties.

Evidence directory: `ARESLib-Kotlin/build/audit-pass218-verified-evidence/`, including
baseline/focused/full XML, build logs, allocation diagnostics, candidate hashes and archive
comparisons. Cached/up-to-date suite results are included; focused tests are not counted twice.

The ledger accounts for 2,956 tracked files: 1,191 fully reviewed, 168 partially reviewed
and 1,597 pending, with zero stale or orphaned records. This pass adds three regression files
and this report and completes review of two production and two original test files.
Coverage describes file review and appropriate validation, not universal executable coverage.

No physical robot, target MicroPython runtime, rendered Studio window, remote workflow,
deployment or public release was exercised. These are ARES-owned defects; no WPILib defect
was established. The repository-wide audit remains open.
