# Historical hand-authored lighting design

Lightbot no longer uses this architecture. Its indicator lights and Prism are now canonical Robot
Builder documents described in [GUI-owned lighting](GUI_OWNED_LIGHTING.md). This page remains only
as an advanced comparison for teams maintaining older hand-authored season code; it is not the
official Lightbot workflow.

The team robot's indicator lights and goBILDA Prism are intentionally small production examples of
the ARES subsystem lifecycle. Their Kotlin remains user-owned; `.aressubsystem` documents make the
implementations discoverable to Analytics without asking the app to guess structure from source.

## Choose the example that matches your hardware

| Example | Start here when | Main lesson |
|---|---|---|
| `IndicatorLightSubsystem` | A servo-compatible indicator needs named colors | A simple write-only output still follows Redux and the subsystem lifecycle. |
| `PrismSubsystem` | A goBILDA Prism needs effects and brightness limiting | Vendor effects remain behind an IO contract and nonessential power can be scaled safely. |

Neither implementation is a generated file. ARES may generate registration and verification
plumbing from its descriptor, but regeneration must never replace either Kotlin source.

## Indicator light: follow one command

```text
Controller binding: Primary light: Green
    -> NamedCommands creates a fresh task
    -> RobotAction.SetIndicatorLight("indicator", greenPosition)
    -> SuperstructureReducer updates immutable RobotState
    -> IndicatorLightSubsystem snapshots the requested position in readSensors()
    -> IndicatorLightIO writes the cached position in writeOutputs()
```

The two important teaching points are that the controller binding does not write a servo directly,
and `writeOutputs()` does not read Redux or hardware. This separation keeps controller, autonomous,
simulator, and robot behavior consistent.

Color is never the only cue in the ARES UI. Actions use explicit labels such as **Primary light:
Green** and **Secondary light: Off**, which remain understandable when colors are difficult to
distinguish.

## Prism: follow one effect

```text
Controller binding: Prism: Full rainbow
    -> NamedCommands creates a fresh task only if "prism" was discovered
    -> RobotAction.SetPrismDriver("prism", presetPulseWidthUs)
    -> SuperstructureReducer updates immutable RobotState
    -> PrismSubsystem snapshots the pulse width in readSensors()
    -> PrismDriverIO applies the cached effect with a bounded brightness cap
```

The controller catalog exposes a curated set of common effects instead of raw pulse widths. The
shared driver supports many specialized presets, but a very long flat list makes accidental choices
more likely for new students. Add another preset in `FtcAutoCapabilities`, the project action
catalog, and the descriptor only when the team has a real use for it.

A single parameterized `Set Prism effect` action is deferred because the current `NamedCommands`
registry creates tasks from stable keys and does not accept arguments. Expanding that shared runtime
contract is a separate design change; this example preserves the already-tested
NamedCommands-to-Redux path.

## Safe customization checklist

When adapting either example:

1. Change the descriptor identity, module, and source paths before changing Kotlin.
2. Keep the hardware-map name explicit and fail closed when required hardware is absent.
3. Dispatch a Redux action from every controller/autonomous capability; do not call IO from input
   bindings.
4. Snapshot intent or feedback in `readSensors()` and apply only cached values in `writeOutputs()`.
5. Preserve a clear off or safe-neutral action and verify cleanup is idempotent.
6. Add a mock assertion for the exact output before attempting a restrained hardware test.

Simulation validates discovery, dispatch, Redux state, and mock output behavior. It cannot validate
wiring, perceived LED colors, or the physical Prism brightness, so record those checks for the next
time a robot is available.
