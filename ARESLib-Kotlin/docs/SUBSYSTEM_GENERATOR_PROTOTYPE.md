# Subsystem generator v2: evidence and architecture decision

This review compares the old eight-artifact output with one generated FTC
`HOMED_MECHANISM` prototype. The goal is safer authoring and regeneration—not fewer files.

## What is actually editable

Inspection of the production FTC intake and flywheel code showed that state, the IO contract,
controller, lifecycle, physical adapter, and simulator adapter contain real mechanism policy.
They remain separate **GENERATED STARTER** files and become team-owned customization points.

The declarative definition mirror, registry/action wiring, and generated contract test are mechanical.
They are deterministic **GENERATED - DO NOT EDIT** build output under `build/generated/ares`.
The canonical `.aressubsystem` document remains editable project data.

## Representative prototype

The prototype is a homed position mechanism with one motor, position and current snapshots, a home
switch, soft target limits, checked configuration, a 250 ms feedback lease, a zero-voltage neutral,
write-fault latching, and explicit neutral recovery.

| Evidence | Previous output | Revised prototype |
|---|---:|---:|
| Conceptual artifacts per subsystem | 8 | 8 |
| Editable/user-owned starters | 8 | 6 |
| Mechanical generated files | 0 | 2, plus one shared registry |
| Cached signals per device | One | Many (position, velocity/current, switches) |
| Explicit freshness/config/homing/current state | No | Yes |
| Declared neutral for every actuator, including positional servos | Partial | Yes |
| Failed-write latch + explicit neutral recovery | No | Yes |
| Hardware/mock failure controls | Minimal | Matching refresh/write/health controls |
| Generated lifecycle contract tests | Startup only | Startup, write latch/recovery, invalid feedback, cleanup |
| Silent starter overwrite | Possible in old FTC scanner | Forbidden; structured diff + exact token |

## Customization and runtime flow

The retained boundaries follow:

```text
Input -> Redux action/reducer -> immutable state -> controller -> IO contract
                                                               |-> FTC/FRC adapter
                                                               `-> simulated adapter
```

Each robot owns one `HardwareRegistry`. Its composition root explicitly registers generated and
hand-authored IO, and the registry refreshes those cached inputs once per platform loop. Generated IO
never mutates process-global state or self-registers from a constructor. The lifecycle only transfers
the cached snapshot into immutable Redux state; output writing never reads hardware. Teams customize
units, physical mappings, controller gains, homing/calibration policy, mechanism interlocks,
simulation dynamics, and additional verification in their six starter files.

## Regeneration and build integration

Preview is read-only and groups artifacts by responsibility, ownership, module, and destination.
Missing starters may be created explicitly. A differing `GENERATED STARTER` requires its displayed
structured diff and content-bound confirmation token. `USER-OWNED` and unknown existing source are
protected collisions and are never replacement candidates. Mechanical sources are recreated in
Gradle output directories and are not committed.

The build compiles generated main/test directories, but ordinary compilation never creates or
replaces editable source. A missing starter fails with an actionable generation command.

## Migration cost and decision

Moving mechanical files costs Gradle source-set/task wiring and makes clean builds depend on the
canonical documents. Migrating editable season subsystems would require reviewing real hardware
configuration, interlocks, units, and HIL behavior; automatic replacement would be unsafe.

The prototype is materially better in ownership safety, cached-input modeling, fail-closed output
behavior, simulation controls, generated verification, and UI discoverability. Therefore ARES adopts
the **hybrid eight-responsibility architecture** and developer-experience improvements. Existing
production subsystems are not bulk-migrated. New mechanisms may adopt it one at a time after review,
simulation, and restrained hardware validation.
