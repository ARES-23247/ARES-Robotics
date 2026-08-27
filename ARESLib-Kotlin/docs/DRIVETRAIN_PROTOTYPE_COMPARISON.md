# Drivebase contract prototype comparison

## Before

FTC mecanum values were split across season hardcoded motor names/inversions, library constructor
defaults, Redux tuning defaults, and simulator geometry/encoder constants. FRC CTRE source was
vendor-generated but its provenance and read-only ownership were not represented in ARES authoring.
Recursive tuning persistence treated arbitrary runtime state as the schema. A first controller
update could therefore replace constructor gains with different Redux defaults.

## Prototype architecture

| Concern | New owner |
|---|---|
| Physical topology and module grouping | `.aresdrivetrain` |
| Geometry/localization/safety/simulation | `.aresdrivetrain` |
| Parameter meaning/type/policy | Consuming component declaration |
| Named authoritative values | Checked-in project profile |
| Robot-local experiments | Ignored local overlay directory |
| CTRE constants source | Vendor-owned Java, referenced by path/hash/class only |
| Runtime/config plumbing | Deterministic Gradle generated source |

### File ownership and customization points

For one representative robot, the prototype has **three user/vendor-owned inputs** and **two
generated Kotlin outputs**:

| Count | Ownership | File or responsibility | Customization point |
|---:|---|---|---|
| 1 | USER-OWNED | `.ares/drivetrains/<id>.aresdrivetrain` | Topology, geometry, localization, control, safety, simulation, provenance |
| 1+ | USER-OWNED | `.ares/tuning/*.arestuning` | Robot-owned named canonical values and one-level composition |
| 0 or 1 | VENDOR-OWNED / READ-ONLY | `TunerConstants.java` for CTRE swerve | Regenerate only with CTRE Tuner; ARES records path/hash/class |
| 1 | GENERATED - DO NOT EDIT | `GeneratedAresDrivebaseConfig.kt` | Mechanical typed structural and drivebase constants |
| 1 | GENERATED - DO NOT EDIT | `GeneratedAresTuningConfig.kt` | Project-wide declarations, canonical values, metadata, runtime factory |

The generated manifest is mechanical ownership bookkeeping, not a customization point. FTC
mecanum therefore has two editable canonical document types and two generated Kotlin files; FRC
CTRE swerve additionally retains its one vendor-owned Java input. The prototype creates no editable
Kotlin starter and never copies or modifies `TunerConstants.java`.

### Safety and test evidence

Validation covers fail-closed startup configuration, fresh/cached feedback, safe disabled output,
explicit neutral mode, output-fault latching and neutral recovery, current-reading availability
separately from optional enforced current limits, CCW-positive localization, calibration evidence,
and simulator geometry/profile parity. Focused tests cover strict codec round trips, deterministic
generation, FTC four-motor topology, CTRE module associations/vendor ownership, typed bounds,
profile composition/cycles/missing parents/duplicate assignments, policy-gated runtime updates,
stale request nonces, atomic local overlays, and zero-document stale-output cleanup. Full robot,
simulator, and consumer suites remain the promotion gate; physical validation is explicitly pending.

### Regeneration and build integration

Gradle discovers only checked-in `.ares/drivetrains`, `.ares/tuning`, subsystem declarations, and
project tuning-component declarations. It writes deterministic plumbing under `build/generated`,
checks a generated ownership manifest, and removes only files previously named by that manifest.
It never overwrites user/vendor source and never reads `.ares/local/tuning`. With no drivebase
documents, stale generated drivebase files and the manifest are removed deterministically.

## Migration and evidence gate

Migration cost is **moderate to high per existing robot** because constructor defaults, immutable
Redux initial state, controllers, and simulation must all switch to the generated baseline in one
change; partial migration is unsafe because it can reintroduce a first-update value jump. The
benefit is material: one initial-value source, explicit vendor ownership, visible units/policies,
deterministic simulation parity, and no runtime mutation of canonical data. Migrate one FTC mecanum
robot and one FRC CTRE swerve robot first. Verify unit, simulator, generation/check-mode,
no-first-update-jump, and binary consumer builds before broader drivebase migration. Physical robot
validation remains pending until hardware is available.
