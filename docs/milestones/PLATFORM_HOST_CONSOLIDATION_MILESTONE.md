# Platform Host Consolidation Milestone

Status: implemented and validated locally on `codex/platform-host-consolidation`

Validation candidate: `10.0.0-rc.platform-host.2`

Release status: no final artifact published, merged, or pushed

## Outcome

ARES now has one platform-neutral generated control scheduler, one FTC-specific generated
autonomous lifecycle host, and one vendor-neutral FRC generated-control runtime. FTC and FRC do
not share a lifecycle, hardware layer, deployment model, or physics simulator.

This removes copied mechanical control and OpMode behavior without turning league-specific
behavior into a lowest-common-denominator abstraction. Canonical `.ares` documents remain the
editable source of truth and generated code remains in Gradle generated-source directories.

## Before and after

| Responsibility | Before | After |
|---|---|---|
| Generated controller/task/routine scheduling | Reimplemented in FTC, FTC starter, FRC, and FRC starter hosts | `GeneratedProjectControlRuntime` in ARESLib `core`; consumers supply typed generated definitions and capabilities |
| FTC autonomous OpMode lifecycle | Two nearly identical checked-in files of about 470 lines | `FtcGeneratedAutonomousOpMode` in `ftc-hardware`; Lightbot and starter adapters are 52 and 51 lines |
| FTC INIT selector and alliance pose resolution | Copied between FTC and FTC starter | One tested implementation in `ftc-hardware` |
| FRC generated controller sampling | Copied season/starter implementations of roughly 139 and 185 lines | `FrcGeneratedProjectControlsRuntime` in vendor-neutral `frc-runtime`; season/starter adapters are 37 and 38 lines |
| FRC WPILib input conversion | Located in vendor-specific `frc-hardware` | Moved to `frc-runtime`; no CTRE/REV dependency is required |
| Dependency provenance | Opt-in `mavenLocal()` paths could shadow immutable releases | No consumer build/settings file contains `mavenLocal()`; sibling source and isolated candidate repositories remain explicit |

The FTC generated drive/routine adapter remains larger because it owns genuine FTC mecanum,
field-envelope, path-sweep, action-capability, and safety behavior. That code was not moved into
the cross-platform scheduler and was not shared with FRC.

## Ownership map

| Repository/module | Owns | Does not own |
|---|---|---|
| ARESLib `core` | Generated control bindings, typed controller factories, one-shot tasks, routine scheduling, Redux dispatch | League lifecycle, hardware, deployment, simulation |
| ARESLib `ftc-hardware` | FTC OpMode autonomous lifecycle, INIT selection, deadlines, safe cancellation, pose handoff, FTC alliance pose resolution | Lightbot catalog, field bounds, season robot facade, FTC simulator |
| ARESLib `frc-runtime` | WPILib controller sampling and generated controller scheduling | TimedRobot/FMS lifecycle, vendor motors, RoboRIO deployment, FRC physics |
| ARESLib `frc-hardware` | Vendor-capable FRC hardware and shared FRC robot facilities | Season mechanism policy and season simulator |
| ARESLib `codegen` | Deterministic `runtimeDefinition`, typed action/controller factories, generated main/test plumbing | User-owned season code |
| ARES-FTC / FTC starter | FTC mecanum path/safety adapter, robot facade, field contract, hardware extensions, OpMode declarations, FTC simulator | Copied scheduler or autonomous lifecycle mechanics |
| ARES-FRC / FRC starter | TimedRobot/FMS composition, season hardware/extensions, FRC simulator | Copied controller scheduler |
| ARES Analytics | Canonical authoring, compiler queries, verification UX, explicit candidate resolution | Robot lifecycle or hardware behavior |

## Runtime flow

```text
GUI-authored .ares documents
  -> typed project compiler
  -> deterministic generated runtimeDefinition and generated tests
  -> league adapter (FTC OpMode or FRC TimedRobot)
  -> GeneratedProjectControlRuntime
  -> generated typed controller/action capabilities
  -> Redux action/reducer -> immutable RobotState
  -> league controller -> IO contract
  -> FTC/FRC hardware adapter OR that league's simulator adapter
```

The generated scheduler has no FTC SDK, WPILib, CTRE, REV, Android, or simulator dependency.
Controller targets enter through generated typed factories, not reflection or runtime classpath
scanning. Device adapters still enter through platform-generated subsystem registries and
explicit platform capabilities.

## Separate platform boundaries

- FTC keeps `OpMode`, Android/Control Hub deployment, mecanum/Pinpoint behavior, FTC field
  geometry, FTC mocks, and the FTC desktop simulator.
- FRC keeps `TimedRobot`, Driver Station/FMS state, RoboRIO deployment, WPILib HAL, swerve/vendor
  hardware, and the FRC dyn4j/WPILib simulation bridge.
- `frc-runtime` depends on WPILib but not a motor-vendor SDK. A future FRC controller target can
  implement a new hardware adapter without changing the scheduler.
- A future FTC controller target can implement FTC IO and lifecycle adapters without inheriting
  FRC assumptions. No speculative Systemcore API was added.

## Validation evidence

All consumers used the same explicit isolated repository:

```text
file:///C:/Users/david/dev/robotics/ares/ARESLib-Kotlin/build/release-repository
org.aresfirst.ares:*:10.0.0-rc.platform-host.2
```

Passed locally:

- ARESLib: `apiDump test apiCheck publishReleaseValidation`
- ARES-FTC: generation, stale-output verification, TeamCode tests, simulator tests, APK assembly
- ARES-FTC-Starter: generation, stale-output verification, TeamCode tests, simulator tests, APK assembly
- ARES-FRC: generation, stale-output verification, full tests including season simulation
- ARES-FRC-Starter: generation, stale-output verification, full tests including Studio simulation bridge
- ARES Analytics: full `test` matrix and release-version alignment against the candidate
- Architecture checks: thin host delegation, no checked-in generated package, no copied direct
  task executor, and no `mavenLocal()` in consumer build/settings files
- Visible desktop evidence: exact Compose HWND `788470`, PID `39028`, `1440x900`, visible,
  non-minimized, non-hung, on the current desktop, rendering the selected Lightbot workspace;
  graceful `WM_CLOSE` completed and the isolated runtime snapshot was removed

The desktop automation environment did not reliably deliver navigation clicks to Compose after
capturing the exact HWND. Therefore visible rendering/shutdown evidence is intentionally reported
separately from the generated-project, build, and simulator matrices. No physical robot evidence
is claimed.

## Migration and rollback

1. Keep the rollback branch until this milestone receives review.
2. Publish any future release under a new immutable version; never reuse either validation
   candidate as a final version.
3. Merge ARESLib first, then validate FTC, FRC, both starters, and Analytics against the exact
   release candidate repository before merging consumers.
4. Existing `.ares` documents do not require migration. The generated source contract gains
   `runtimeDefinition`, so stale generated outputs fail verification and regenerate normally.
5. Reverting the milestone branches restores the former checked-in hosts without changing
   canonical project documents.

## Deliberately deferred

- A universal FTC/FRC simulator or lifecycle
- A speculative Systemcore target before its real SDK and deployment constraints exist
- Moving FTC-specific field/path/safety behavior into platform-neutral `core`
- Replacing explicit season hardware extension points with generated vendor guesses
- Final release publication or physical-validation claims
