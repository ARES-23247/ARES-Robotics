# Studio hardware topology presentation audit

Pass 219 audits the complete hardware topology dashboard card, its original five-test
fixture and the Studio topology wire-compatibility test. The work changes only Studio
application code and tests. The library, robot consumers, release versions and starter
archives remain identical to the candidate validated in pass 218.

## Findings and changes

- **Nested controllers could crash the card.** Controllers were included both as top-level
  rows and as children, with the same LazyColumn key. Rendering a control hub, a child
  expansion hub and its motor reproduced a duplicate-key exception for the expansion hub.
  A single indexed preorder traversal now emits each identity once at its actual depth.
  Missing parents become roots. Disconnected cycles are opened deterministically, and old
  cached payloads with duplicate IDs use the first definition. Deep traversal is iterative.
- **Motor readings could remain frozen.** Each row read a ConcurrentHashMap that does not
  invalidate Compose. A second rendered regression changed current from 1 A to 35 A while
  leaving topology unchanged; the old card image did not change. One owned observer now
  collects relevant motor publications and samples presentation at 10 Hz. Storage is bounded
  by topology keys. Unrelated publications do not trigger additional presentation samples.
- **Telemetry identity, validity and units needed correction.** A topology ID such as
  Motors/arm was incorrectly expanded to Hardware/Motors/Motors/arm. Canonical identity now
  precedes the legacy display-name alias, and non-motor rows do not borrow motor readings.
  Numeric placeholders for strings, NaN and infinities are unknown. An explicit invalid
  canonical sample is not masked by a friendly-name alias. The old radians-per-second
  suffix was unsupported: MotorIO and its producers publish encoder-native velocity.
  The display now says encoder units/s; it does not invent a conversion to radians.
- **Live values could appear in a saved-session view.** Saved selections now disable live
  telemetry observation immediately. Their map state belongs to the database/session key,
  so switching sessions clears the previous robot before asynchronous loading completes.
  Missing or blank robot identity does not query an empty cache key. Storage failures and
  mismatched cached robot identity produce an unavailable state; cancellation remains
  cancellation. Late completion from a retired selection cannot replace the live view.
  The UI calls these cached maps because the database stores the latest topology per robot,
  not a historical topology snapshot at every session timestamp.
- **Exports and field layout had presentation defects.** Markdown cells now escape pipes,
  line breaks, markup and HTML characters. Repeated copy actions restart feedback expiry.
  Render inspection found the existing 42 dp search field clipped its text; a 56 dp minimum
  now shows the complete placeholder.

Motor observations use desktop monotonic receipt time, not robot timestamps. Each metric
expires independently after the inclusive two-second freshness interval. Target epoch
changes, clock rewind and stale data clear the reading. Retained fan-out publications have
no reliable receipt age and cannot establish freshness when the card is reopened. Disabling,
disconnecting, changing topology or selecting a saved session disposes the corresponding
observer. These rules affect display only; robot actuation and leases were not changed.

The previous hierarchy code also rescanned root controllers for each orphan and assembled
separate overlapping row lists. The replacement has expected linear work in node count,
with a hash index, adjacency lists and a visited set. Filtering uses one exhaustive category
classification instead of constructing category lists for every node. This is source-based
complexity evidence, not a measured robot-loop timing improvement.

## Tests and rendered evidence

Two tests against unchanged production code failed independently: the duplicate-key render
and the unchanged-image telemetry update. Original XML and exception messages are retained.
The other corrections came from source tracing and rendered inspection; their new boundary
tests are not described as additional unchanged-production reproductions.

The final focused run passed 29 methods: 23 new methods and six existing methods. Tests cover
nested and out-of-order parents, missing parents, self-links, cycles, old duplicate IDs, a
20,000-node chain, all eighteen hardware categories, combined search/category filters,
literal Markdown cells, canonical/legacy telemetry precedence, invalid numeric/string data,
independent expiry, receiver clock rewind, target changes, snapshot ownership, retained
frames, bounded observation frequency, observer cancellation and saved-session transitions.
A simulated storage call deliberately completes after cancellation to check selection ownership.

The five original card tests now call the production filter and row builder. Previously,
they copied logic that differed from the actual card. Only their service-flow test now opens
a database, and both the NT4 service and database are closed before the fixture is removed.

Four final offscreen Compose captures document nested hierarchy, current before/after update
and cached-session display. Inspection confirmed visible search text, increasing child
indentation, 35 A live output and a cached row without the unrelated 77 A live reading.
These are headless component renders, not a launched Studio HWND or a physical robot test.
Native clipboard interaction was not exercised; export content was tested directly.

## Validation and coverage

Source commit: `0607c7b8c6f7daa3427e47ed10bf075e918c8041`.
Unchanged library tree: `9de91b51866db1217c4a221681e9157cd1398100`.
Reused local library candidate: `17.0.25-rc.9de91b51866d`.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,043 | 6 |

The full affected-product validation contains 2,092 passing results, zero failures/errors and
six unchanged Studio skips: three opt-in starter integration scenarios, native file chooser,
dashboard performance baseline and physical dashboard validation. The 410 unchanged library
candidate files were rehashed after Studio validation. The dependency source tree and release
pins remain unchanged; the pass 218 robot/library/tooling results are retained evidence, not
additional pass 219 execution. Monorepo policy passed, including links in 380 current documents
and 38 explicitly historical skips.

The ledger contains 2,964 tracked files: 1,202 fully reviewed, 168 partial and 1,594 pending,
with zero stale or orphaned records. Three previously pending files are complete. This pass
adds three production helpers, four regression files and this report. File review and scoped
testing do not claim every native or physical path was executed.

Evidence directory: `ARESLib-Kotlin/build/audit-pass219-verified-evidence/`, including
baseline/focused/full XML, build logs, image hashes and original/final rendered captures.
Focused results are not counted twice. Gradle cache/up-to-date results are identified as
such in the logs. No remote workflow, push, merge, deployment or public release occurred.
The repository-wide audit goal remains open.
