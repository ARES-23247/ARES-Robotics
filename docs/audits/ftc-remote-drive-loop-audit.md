# FTC remote-drive loop audit - pass 174

Read the remote-drive OpMode and the complete existing safety test. Those tests exercise
DesktopDriveFrameGate directly; they did not cover the OpMode's catch path. A new real
loop test reproduced resumption of a retained moving frame immediately after a telemetry
exception: the catch sent zero but left the gate authorized.

The catch now invalidates the gate before dispatching zero. A later nonzero frame cannot
resume motion until a neutral handshake and subsequent sequence are accepted. The test
checks initial movement, injected telemetry failure, retained movement remaining zero,
and successful neutral/rearm. Status publication also now runs on the first loop at time
zero; a separate regression failed before that correction. Rewind/overflow status timing
is handled without changing the gate's stricter receiver-clock validation.

The new fixture runs the actual OpMode loop, input adapters and frame gate. It installs
an unstarted NT4Server solely for in-memory topic publication/copy, rejects an existing
server, and clears its own registry/instance afterward. It opens no socket and constructs
no hardware. Robot/runtime boundaries are mocked. Mutable drive actions are copied at
dispatch time in the fixture to avoid asserting against their later mutated values.
The test also verifies that the original action object is reused across loop dispatches.

Further cases cover retained nonzero startup, neutral handshake, robot-relative flag,
exact 200-ms lease expiry and an oversized array whose otherwise-valid prefix must not
be accepted. All nine existing protocol gate tests are retained and run alongside these
four loop tests; their claims remain gate-level rather than physical receiver evidence.

The OpMode remains partial: failure of the zero-dispatch/error-reporting fallback itself
and resulting shared SDK shutdown behavior need explicit tests. No claim of physical
motor neutralization or network transport fault recovery follows from the mock dispatch
assertions. Status strings remain rate-limited; buffer and command reuse are preserved,
and no loop-time benchmark was performed.

The library candidate is unchanged at `17.0.3-rc.100852e472fb`.
Evidence: `ARESLib-Kotlin/build/audit-pass174-verified-evidence/`.
No release, push, physical robot operation or socket server startup occurred.

Validation: 13 focused tests and all 139 TeamCode plus six simulator tests pass without
skips. Debug APK assembly, monorepo policy, documentation links and staged whitespace
checks pass. Unchanged library and other product suites were not rerun.
