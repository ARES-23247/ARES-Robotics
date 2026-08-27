# Hand-authored subsystem prototype

This evidence note compares the revised hand-authored workflow with the generated-starter workflow.
It uses the existing ARES FTC indicator lights and goBILDA Prism implementations; neither production
subsystem was rewritten for the comparison.

## Result

The hybrid architecture is materially better for existing production code:

- keep domain, control, hardware, simulation, and verification boundaries explicit;
- represent the implementation with a schema-v5 `.aressubsystem` contract;
- validate catalog actions and source ownership without scanning Kotlin;
- generate only deterministic project plumbing and registration reminders; and
- preserve every USER-OWNED Kotlin file.

This does **not** justify migrating Intake or Flywheel yet. They should receive descriptors only
after the lighting prototype has been used by students and its explanations have been refined.

## Comparison

| Evidence | Generated starter prototype | Hand-authored indicator lights | Hand-authored Prism |
|---|---:|---:|---:|
| Canonical descriptor | 1 | 1 | 1 |
| USER-OWNED/starter Kotlin files | 6 generated starters | 1 existing source file | 2 existing source/mock files |
| Generated subsystem files | Definition + contract test + shared registry | Shared registry reminder only | Shared registry reminder only |
| Bindable actions | Derived from target fields | 22 existing named color actions | 12 curated named effect/color actions |
| Simulation | Generated mock | Existing shared indicator mock | USER-OWNED deterministic Prism mock |
| Teaching level | Template-dependent | Beginner | Intermediate |
| Regeneration | Starter diff and confirmation | Never replaces Kotlin | Never replaces Kotlin |

Generated starters still provide the best first experience for a new mechanism. Explicit
hand-authored registration is the better fit when proven Kotlin already exists or the mechanism
needs custom vendor behavior.

## Customization points

### Indicator lights

- Redux target positions and named colors
- optional `indicator` and `indicator2` hardware names
- rainbow timing behavior
- controller/autonomous binding selection

### Prism

- named effect/preset mapping
- optional PWM or I2C adapter
- configured maximum brightness
- power-aware brightness scaling
- controller/autonomous binding selection

The UI presents these files as USER-OWNED and displays their module, runtime classes, simulation
support, teaching level, and catalog action keys. It does not claim that regeneration owns them.

## Safety and test coverage

The prototype verifies offline that:

- missing optional lighting hardware does not advertise executable runtime commands;
- registered actions dispatch through `NamedCommands` into immutable Redux state;
- catalog keys referenced by a hand-authored descriptor must exist;
- generated code never emits or replaces hand-authored source paths;
- deterministic mock adapters observe the same requested light output; and
- generated registry output contains only external-registration reminders for these descriptors.

No physical-light color or electrical behavior is claimed by these tests. Those checks remain part
of a future hardware validation session.

## Build integration and migration cost

Schema and catalog validation live in ARESLib. Analytics consumes the same descriptor model. FTC
Gradle generates the shared registry and project capabilities before compilation. A descriptor is a
small migration compared with rewriting the production subsystem, but it still requires accurately
documenting source classes, simulation support, actions, and safety responsibilities.

Recommended next migration after student feedback: add descriptors for Intake and Flywheel without
rewriting their implementations. Use their existing tests as the acceptance contract.
