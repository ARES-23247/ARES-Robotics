# FRC factory construction cleanup audit - pass 127

Reviewed the complete season hardware factory and its test, and traced simulator
construction and close. Native device constructors and their inaccessible partial
resources remain an explicit gap; factory and simulator ledger entries remain partial.

## Fixes

The simulator's secondary constructor calls the primary constructor, which opens
a NetworkTables field-config subscription, then builds the requested world. If
world construction throws, the factory never receives the simulator and cannot
register it for rollback. The secondary constructor now closes its acquired
subscription on failure, preserves the original exception, and suppresses a
cleanup exception if one occurs. This affects initialization only.

The original factory test left its dashboard publishers/subscribers and simulation
subscription open. Its assertions now run inside a finally-protected scope that
closes both owners, including when assertions or dashboard cleanup fail. Mechanism
IO aliases are not separately closed because they belong to the simulation.

## Validation and review

Expanded the test to cover both the default world and a validated deployed field.
Both return a coherent graph whose six mechanism adapters belong to the returned
simulation. Added invalid configured-world construction and checked that the
original geometry exception propagates instead of a partial graph being returned.

The initial negative-width fixture did not reach the intended failure: raw config
resolution uses the league default for nonpositive dimensions. Changed the fixture
to positive infinity, which reaches the world-wall validation failure. The normal
validated field loader rejects both invalid inputs before this boundary. This was
a fixture correction, not evidence that the cleanup fix originally failed.

The new failure test verifies propagation; subscription release is established by
reviewing the constructor catch/close path, not a measured native handle count.
The six existing startup-resource tests verify reverse-order rollback, identity
tracking, ownership replacement, constructor failure, suppressed errors and commit.
They do not inject failures inside vendor native constructors.

Real-device construction retains ten mechanism Talons, the drivetrain, optional
PDH and independent cameras until successful handoff. Completed adapter constructors
replace their raw-device ownership; cameras remain separate during rollback. CAN
device numbers on different device types are not automatically conflicts. Missing
field metadata leaves real vision disabled. Configured simulation uses seed 42.
The factory allocates during startup, not in the control loop; no hot-loop
optimization was indicated here.

Generated-project verification and the full FRC suite passed: 298 tests, zero
failures/errors/skips. Policy, documentation links and staged whitespace checks
passed. Logs, XML and changed-source hashes are retained under
`ARESLib-Kotlin/build/audit-pass127-verified-evidence/`. No new allocation benchmark
was run because periodic code is unchanged. No physical CAN device, camera,
rendered GUI, push, merge or release was exercised.
