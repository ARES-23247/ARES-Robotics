# FRC starter drive lease and receiver timing audit

Pass 264, 2026-09-14. This continues the [field and telemetry audit](frc-starter-field-telemetry-audit.md)
with the remaining drive-frame gate, controller projection, native queue age, and simulator
mode/fallback branches. Changes remain local.

## Confirmed defects and changes

Seven of eleven initial regression methods failed against pass 263. Motion could renew an expired
lease when no expiry poll occurred between accepted frames. A receiver-clock rewind could likewise
be hidden by a new motion frame. Subtraction across the signed millisecond boundary could make an
old command look fresh. The acknowledgement confused a valid minimum signed mock timestamp with
missing history and reported an overflowing forward age as zero. Finally, controller sampling
could refresh an old command's input-frame timestamp while the bridge update loop had stopped.

The extracted [drive-frame helper](../../ARES-FRC-Starter/src/main/kotlin/org/aresfirst/starter/frc/FrcStudioDriveFrames.kt)
now expires authority before accepting another frame, requires an explicit neutral recovery,
and checks timestamp ordering before elapsed subtraction. Acknowledgement history is identified
by accepted session identity; nonrepresentable forward age saturates instead of looking new.
The rejection counter also saturates. Signed mock timestamps remain supported, and the existing
inclusive 500 ms lease boundary and strict duplicate/out-of-order rejection remain unchanged.

Controller sampling now checks the gate through `RobotClock.currentTimeMillis()` independently of
bridge polling. It disconnects expired input and disables TeleOp simulation. Controller nanoseconds
are used only for the input sample timestamp; they have a different origin from robot milliseconds.
The bridge no longer stores a redundant second command snapshot.

Two additional native queue tests exposed another renewal path: old queued neutral and motion
messages were stamped with the current poll time. The receiver now accounts for the queued value's
age, allowing only the unspent lease duration. Missing, future, expired, or unrepresentable transport
timestamps fail closed. Sub-millisecond age rounds upward so conversion cannot extend authority.

NetworkTables provides a local timestamp on queued values and a microsecond clock for that time
domain. The exact cached 2026.2.1 sources were inspected, alongside the live release documentation:
[TimestampedDoubleArray](https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/networktables/TimestampedDoubleArray.html)
and [NetworkTablesJNI.now](https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/networktables/NetworkTablesJNI.html#now()).
Only an age is translated into RobotClock milliseconds. Transport timestamps can restrict a lease;
they do not authenticate the sender.

Wire constants, acknowledgement constants, and receiver status codes now come from the existing
shared schema. Primitive integer parsing avoids nullable metadata results, and neutral classification
is computed once per accepted frame. The starter retains its own queue-receiver policy: substituting
the shared retained-value gate would change duplicate handling, signed mock time, and timeout-edge
semantics. No library source or public contract changed.

## Validation and efficiency

The initial baseline was **11 tests, 7 failures, zero errors or skips**. The authoritative queue
baseline was **2 tests, 2 failures**, after temporarily restoring only the original queue loop
while retaining the repaired receiver and deterministic native fixtures. Earlier queue fixtures
depended on native uptime when creating old timestamps; those runs are superseded by explicit
positive timestamps and an injected transport clock. Fixed source bytes were restored in a
`finally` block and hash-checked after baseline execution.

Final `verifyAresProject test`: **94 passing tests, zero failures, errors, or skips**.
All 74 prior invocations remain. Twenty added methods cover:

- No-poll expiry, clock rewind, signed wrap, minimum timestamp history, overflowing age, and exact
  inclusive timeout behavior without renewal on repeated reads.
- 196 timestamp pairs checked against independent arbitrary-precision integer arithmetic.
- Malformed lengths/versions/metadata/axes, every actuating flag, finite command limits, exact maximum
  wire identities, strict sequence/client-time ordering, immutable snapshots, and saturating counters.
- Native queued-message age, stopped-update sampling, explicit neutral recovery, disabled/TeleOp/
  autonomous transitions, controller-port fallback ownership, mode flags, and Driver Station enable.
- Controller scaling/clamping, clearing old inputs, invalid scale rejection, and retained-command
  polling/projection/acknowledgement allocation.

After 50,000 warmup iterations, 50,000 retained-command ticks allocated **0 bytes** and took
**5,139,000 ns**, approximately **0.103 microseconds per tick** in this one desktop JVM observation.
The measurement excludes native queue reads and newly accepted immutable command snapshots, which
still allocate. It is not a full robot-loop benchmark or hardware timing guarantee.

All 410 files of candidate `17.0.44-rc.f9e7569ea873` remain byte-identical; the ARESLib tree remains
`f9e7569ea873a08df0477fad1008b6f9ef4575e3`. Shared guidance and current documentation links passed.
Local evidence lives under `ARESLib-Kotlin/build/audit-pass264-verified-evidence/`.

## Coverage and remaining work

The extracted receiver/projection helper, the full serialized bridge orchestration, and both new
fixtures are reviewed and validated with the earlier field/teardown fixtures. Native tests use
isolated local instances and HAL simulation, restoring clock and Driver Station state. They do not
launch a full TimedRobot process, external NT server, Studio window, or physical robot. Constructor
failure injection and arbitrary native backend failures are not claimed by these tests.

The composition root and `StarterRobotRuntime` remain partial. Next review should cover registration
failure, update/disable safety, repeated cleanup failures, and generated/tuning consumer ownership.
The broader monorepo goal remains open.

Studio's release-alignment gate and archive/reference migration remain pending. Automatic approval
review previously rejected that migration as outside the audit's no-release authorization. The old
proposal must be refreshed to include subsequent fixes after approval; no migration, release
reference, protected check, or remote branch was changed or bypassed in this pass.
