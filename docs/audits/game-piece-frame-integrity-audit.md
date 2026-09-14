# Game-piece frame integrity audit

Pass 252 follows [NT4 disposal and fixture ownership](nt4-disposal-test-fixtures-audit.md)
with the live/replay v2 game-piece decoder. Simulator producers were traced read-only in
`SimGamePieceTelemetryFrame` and the FTC/FRC publishers. Pose and trace reconstruction remain
separate incomplete review boundaries; this pass changes game-piece display data.

Studio source tree: `e19e7136a4e12977be64e87e089ba20dd7b6f2d2`.
Unchanged ARESLib source tree: `526a048d8dfb1092e89be95c8e906e9fbc516027`.
Validation candidate: `17.0.42-rc.526a048d8dfb`.

## Reproduced findings

All 12 baseline methods failed against the original implementation:

- Missing or unsupported version headers could still produce a valid-looking frame.
- Missing coordinates reused previous buffer values, and extra sequence markers republished
  consumed data. A valid prefix followed by trailing payload values was accepted as a frame.
- Nonfinite records, fractional/negative/unsafe integer identities and sequences, unknown
  shapes, invalid colors and nonpositive dimensions were converted into plausible display data.
- Replay checked presence/finiteness but accepted malformed identity/shape metadata.
- A held field consumer lost an entire packed frame when 5,000 unrelated publications displaced
  its flattened elements from the bounded telemetry stream.

These failures concern Studio display/replay integrity. They do not establish physical motion,
an estimator defect, or a flaw in WPILib.

## Corrected boundaries

NT4 now publishes an immutable complete game-piece snapshot before scalar fan-out. It captures
the target epoch and validates the packed payload's exact size. Invalid packed observations
own an empty layer instead of exposing a plausible prefix or reviving legacy remnants.
Existing flattened recording remains intact; the live field layer receives the parent snapshot
independently of scalar-stream overflow. Target changes clear it, and replay uses its existing
immutable ReplayFrame path rather than queued live publications.

The shared desktop decoder follows the producer's v2 layout: two header doubles, nine values
per record and one sequence. Identity keys are positive exact integers within the producer's
53-bit range; sequences permit zero and wrap within that range. Coordinates/headings must be
finite, dimensions positive, shapes circle/box and colors exact RGB integers. Packed live input
retains the existing 4,096-element budget; replay maps retain the previous 10,000-record bound.
Checked sizing prevents invalid-count arithmetic. No heading sign or unit conversion changes.

The scalar fallback requires a complete ordered frame starting with a fresh version/count
header. A gap, malformed value, reset or consumed sequence invalidates staging. Its bounded
array is reused without exposing stale slots. Replay uses the same metadata decoder and also
rejects numeric placeholders whose source value was text, including legacy coordinates/counts.

Records now use wire-order map slots and retain their original GamePiece identity strings.
The display consumes the map values, not the old integer hashes. This removes quadratic linear
probing for colliding integer hashes. Repeated fallback identities emitted by unidentified
simulator bodies remain representable; every record is retained. The decoder owns no mutable
input array. Equivalent records with a new sequence retain the same rendered StateFlow value.

## Validation

Focused validation passed 74 tests across six suites: 27 new frame integrity cases,
ten existing field subscriber cases, six replay field snapshot cases, two replay dashboard
cases, 28 NT4 client cases and one actual loopback transport integration. All 12 baseline
failures pass in both focused and full validation. No pre-existing test method changed.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,486 | 6 |

Full Studio validation has 2,535 passing results, zero failures/errors and six unchanged opt-in
skips. Focused results are not counted twice. All 410 unchanged library candidate file hashes
were rechecked. Monorepo policy passed, including 414 current-document links and 38 historical
records. Evidence-local test startup heap remains 32 MiB with one worker; maximum heaps,
assertions and shared build settings are unchanged.

## Coverage and limits

The ledger accounts for 3,090 tracked files: 1,422 reviewed, 195 partially reviewed and
1,473 pending, with zero stale or orphaned records. There are still 1,668 files requiring full
review completion. The new shared decoder, regression file and report are fully reviewed;
the three larger edited runtime files retain precise partial scope. This pass fixes confirmed
behavior without claiming that unrelated parts of those files are complete. File-review status
is not executable line coverage or hardware validation.

FieldTopicSubscriber remains partial for pose/vision/legacy update behavior and broader
concurrent event ordering. ReplayFieldSnapshot remains partial for pose finiteness, trace
joining/downsampling and other source timing. Nt4ClientService remains partial for broader
ingress and lifecycle. No rendered Studio window, physical robot, physics simulation, hardware
timing benchmark, external CI, library/version change, push, merge or release was involved.
