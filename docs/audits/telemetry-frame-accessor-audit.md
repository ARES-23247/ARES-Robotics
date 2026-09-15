# Telemetry frames, input completeness and typed accessors

Pass 232 reviewed shared telemetry publishing, helper functions, topic definitions, gamepad
snapshots and the desktop menu-button adapter. It moves beyond the preceding log-server audit.
The NT4 server and adapter startup/lifecycle remain partial; this pass covers their accessor
boundary. The joystick widget also remains partial outside the changed replay/live menu mapping.
No WPILib, Java-WebSocket or other upstream dependency source was changed.

## Confirmed behavior fixes

The shared publisher and public `logGamepad` helper omitted stick-click, Start/Back and F1-F12
buttons. Both now use one complete mapping of six axes and 35 buttons. Missing gamepads publish
neutral values for every control, preventing retained button states after disconnection. Existing
topic names are preserved and sixteen missing boolean topics are added per gamepad.

The extra topics exposed an existing desktop ambiguity. Replay preferred a present-but-false
Start/Back value over a true Options/Share value, while live updates let whichever spelling arrived
last overwrite the other. The extracted and tested desktop helper now combines each pair using
independent states; either physical button lights the shared visual control. Invalid nonfinite
readings cannot appear pressed. Replay truth tables, live event orders, release transitions and
gamepad-prefix isolation are covered. No change to actual drive inputs or physical button bindings
was made, and no rendered Studio-window result is claimed.

Removed indicator names previously stopped publishing and could remain visibly lit in retained
telemetry. A topology change now publishes zero for removed names once, including replacements
that keep the same map size. Stable frames retain the indexed cached-key path.

Zero, negative, nonfinite or numerically unrepresentable loop periods could retain an old frequency
or emit misleading timing values. All three timing topics now become NaN (unknown) together for
invalid supplied periods. Ordinary seconds-to-milliseconds and reciprocal-Hz conversion is tested.
Omitting an optional diagnostic still preserves caller-owned values. The desktop diagnostic model
already rejects nonfinite/invalid loop timing.

A future vision timestamp could wrap signed subtraction into a small positive age and appear
fresh. The publisher now checks temporal ordering before subtracting, while retaining the inclusive
500-ms freshness boundary. Future, expired and signed-boundary cases are tested.

NT4 publication normalized topic roots more thoroughly than static lookup. Repeated-slash names
could therefore publish successfully but return a default on read. Accessors now use one canonical
registry lookup. Announced placeholders are absent until a value arrives. The `NT4Telemetry`
adapter obeys the typed `ITelemetry` contract: numbers accept integer/float/double values, and
boolean/string getters require the corresponding type. Numeric or boolean text uses the caller's
default at this adapter boundary. The separate static NT4 compatibility getters retain their
existing conversions for published values. Tests cover both contracts and array snapshot/copy
semantics, including absence and wrong-type status codes.

## Efficiency and retained contracts

The public gamepad helper previously formatted every topic on every call. Its baseline allocated
29,120,000 bytes across 20,000 calls. A bounded per-thread cache now retains at most sixteen
prefix mappings; the shared state publisher keeps its constructor-cached arrays. After warming
the same measurement path, two consecutive windows of 20,000 alternating-prefix helper calls
each measured zero bytes while publishing all 41 fields. Tests also check cache capacity and
prefix correctness under churn. This isolates helper formatting; it is not a network/backend,
whole-robot allocation or physical loop-time benchmark.

NT4 normalization no longer builds a redundant slash-prefixed fallback key. Typed adapter reads
avoid converting immutable values through boxed generic objects or cloning incompatible arrays.
Float-array conversion avoids an intermediate boxed list. `putPose2d` reuses the same thread-local
buffer infrastructure as the public pose helper; backend snapshot ownership remains mandatory.
These additional source-level reductions are not separate quantified performance claims.

The existing path identity cache is retained. `PathState` explicitly requires retained paths to
remain stable, so mutating a published path in place would violate its ownership contract.
Replacement/removal, buffer reuse and retained-sample tests verify the supported path behavior.
Pose helpers preserve meters, CCW-positive radians and yaw-only quaternion ordering. Other checks
cover frame-counter wrap before loss of integer precision, catalog revision caching without task
construction, topology/calibration flushing, unknown calibration coordinates, cached motor getter
counts and CAN diagnostic units. Redundant interface documentation was removed.

## Evidence and validation

The original runtime produced eight assertion failures among fourteen new core cases. A separate
one-case allocation baseline failed at 29,120,000 bytes. The desktop's extracted original menu
behavior failed all three cases. These twelve failing scenarios are not twelve independent bugs.
The final focus passed 119 core telemetry/NT4/allocation checks and three Studio menu checks.
This adds 21 library methods and three Studio methods; focused results are not counted twice below.

Two fixture corrections are recorded separately from runtime findings: the first Studio attempt
used a Jupiter annotation unavailable in that product and was corrected to its Kotlin test setup;
an intermediate broad core run encountered another test's retained singleton. The accessor fixture
now restores that prior server reference, never stops its listener, and removes only its own unique
topic namespace. It starts no listener or WebSocket worker. Those fixture failures are preserved
in separate logs and are excluded from the behavior-baseline failure count.

Source commit `9cb026dc636e39ed4344041eec1a624c0e158aea` binds library tree
`b5d79a71a7e9fbdec358c89fbde5681081656dbe`. Candidate `17.0.39-rc.b5d79a71a7e9`
was validated locally. Versions are ARES/FTC/FRC starters 17.0.39, Studio 7.0.39 and
XRP/Lightbot 3.0.38. The initial Studio helper focus used the preceding candidate because it
tests app-owned pure mapping; the full consumer matrix uses the new candidate.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,832 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,046 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |
| Headless browser fixture checks | 9 | 0 |

There are 5,676 passing results, zero failures/errors and six unchanged Studio opt-in skips.
The skips concern three starter integration scenarios, native file chooser, dashboard performance
baseline and physical dashboard validation. Gradle results may be executed, up-to-date or cached.
FTC/starter generated-project checks and APK assembly passed; FRC/starter generation checks passed.
All 410 candidate file hashes were reverified after consumer validation. Monorepo policy passed,
including links in 394 current documents and 38 explicitly historical skips. The four normalized
starter archives differ only in release version properties.

## Coverage and limits

The ledger accounts for 3,025 tracked files: 1,332 fully reviewed, 180 partially reviewed
and 1,513 pending, with zero stale or orphaned records. This is file review and appropriate
validation accounting, not universal executable test coverage.

Complete file reviews include the shared frame publisher, `ITelemetry`, the new gamepad mapping,
`GamepadState`, topic constants/normalizer, robot-status declarations, the telemetry-manager
interface, the four new test classes, two existing publisher/normalizer test classes and the new
desktop menu helper. Data declarations/interfaces are accounted for through source inspection,
their focused behavior checks where applicable and consumer compilation, not invented executable
coverage. NT4 server lifecycle/concurrency and adapter startup/recovery remain partial. The
joystick widget's other input modes, connection transitions and rendered interaction remain partial.

No physical loop timing, CAN/vendor allocation, HIL, live robot network, rendered Studio window
or remote GitHub Actions execution was performed. All changes and candidates are local; nothing
was pushed, merged or remotely released. Evidence is in
`ARESLib-Kotlin/build/audit-pass232-verified-evidence/`: baseline/focused XML, allocation output,
fixture diagnostics, full validation logs, candidate hashes and normalized archive comparisons.
