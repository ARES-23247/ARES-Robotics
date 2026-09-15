# FRC simulator inventory audit - pass 118

## Scope

Reviewed simultaneous collection/shooting, physical inventory metadata, virtual detector
observations and subsequent Redux sensor accounting. Traced `ARESRobot.simulationPeriodic`
dispatching simulator events and `MarvinSuperstructure.readSensors` consuming the virtual IO.
This is a delta review of the previously reviewed actions/reducer and simulator tests;
`Dyn4jSimulation.kt` remains partial beyond its accumulated verified scopes.

## Findings and changes

- Collection and shooting both computed counts from the frame's initial Redux count. A frame
  that collected and fired emitted count N+1 followed by N-1, although physical inventory
  remained N. Both events now update a bounded local count, committed once after processing.
- Configured virtual detectors credited collections only through rising edges, missing further
  arrivals while held. Shooting explicitly decremented inventory, but could set the detector
  true and cause the next sensor read to credit the shot back into the hopper.
  The simulator now commits the final count and configured detector observation atomically.
  The reducer synchronizes the trusted edge state so subsequent reads cannot recount events.
  Ordinary count assignments retain existing detector observations. Physical sensor accounting
  remains unchanged; no new detector is inferred for the default simulator configuration.
- Coalescing avoids two inventory actions for simultaneous events. Identical count/detector
  observations reuse nested state; root timestamps still advance. Idle valid frames emit no
  inventory action. This is an observed structural reduction, not a measured loop-time claim.
- Direct invalid state counts are clamped before event arithmetic, keeping the metadata deque
  and Redux count consistent without overflow. The existing 40-piece model capacity and event
  order remain: a full hopper rejects collection before a shot frees space, and a piece newly
  captured into an initially empty hopper cannot fire during that same frame.

`SetInventoryCount` adds an optional virtual-detector snapshot after its timestamp parameter,
preserving existing Kotlin calls and Java overloads. It changes season-local source only.
It does not fabricate a full fresh sensor frame or alter actuator authorization.

## Regression evidence

Eight initial test methods ran against original production source; six failed. The final
eleven methods cover both detector configurations, both prior beam states, empty/partial/full
hoppers, simultaneous events, repeated sensor reads, piece conservation and captured metadata,
Int.MIN_VALUE/Int.MAX_VALUE inputs, unchanged observations, timestamp propagation and slamtake
stopping after a virtual observation. Tests use the actual Store, reducer, simulator IO and
subsystem sensor reader. Every simulator is closed with `use`.

Before XML is retained in `ARESLib-Kotlin/build/audit-pass118-before-evidence/`.
Full FRC validation passed 259 tests with zero failures, errors or skips, including all eleven
new methods. Five core allocation regression methods passed. Generated-project/namespace
verification and monorepo policy passed. Gradle reused valid unchanged outputs. All four
final validation processes reached terminal exit zero. Copied XML/logs, source hashes and
before-run hash are retained in `ARESLib-Kotlin/build/audit-pass118-verified-evidence/summary.json`.

## Limits

These tests establish desktop model and Redux consistency, not physical detector geometry,
hopper capacity, motor timing or hardware safety certification. The virtual detector still
models hopper occupancy; it is not a spatial model of individual pieces crossing a beam.
Complete world lifecycle and remaining simulator integration paths are outside this pass.
No whole-loop allocation or deadline guarantee is inferred from the core regression suite.

Library candidate `17.0.3-rc.100852e472fb` and source tree
`100852e472fbeeba64fdf799665f51b4687f7f1b` are unchanged. All changes remain local;
no push, merge, release or hardware execution occurred.
