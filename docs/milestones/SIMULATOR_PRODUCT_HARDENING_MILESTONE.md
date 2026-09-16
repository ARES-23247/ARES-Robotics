# Simulator Product Hardening Milestone

## Decision

ARES keeps two simulator products, not one universal simulator:

- `ftc.desktop-opmode` owns FTC OpMode and virtual Driver Station lifecycle, Control Hub device
  doubles, mecanum physics, FTC field behavior, and FTC season integration.
- `frc.wpilib-desktop` owns WPILib/HAL and TimedRobot lifecycle, FRC Driver Station state, CTRE
  swerve behavior, FRC field behavior, and FRC season integration.

The shared `simulation-foundation` module owns only stable selection, capability, compatibility,
clock, and fault-timeline contracts. It imports neither FTC nor FRC lifecycle APIs and cannot become
a generic physics fallback.

## Runtime flow

```text
.ares documents
  -> RobotProjectAssembler
  -> EffectiveRobotProject + SimulationProjectPlan
  -> RobotProjectCompiler IR + verification manifest
  -> Robot Studio readiness
  -> concrete SimulationProductId
  -> FTC :TeamCode:runSim OR FRC simulateJava
  -> league-owned physics/lifecycle/device adapters
  -> NT4 telemetry and run evidence
```

Studio no longer passes a raw `League` to the process manager. The process manager accepts a
concrete product selected from the canonical project. A stale FTC fat jar is eligible only for the
FTC product and cannot override an FRC launch.

## Compatibility rules

- FTC Control Hub pairs only with the FTC desktop OpMode product.
- FRC RoboRIO pairs only with the FRC WPILib desktop product.
- FTC mecanum requires the FTC mecanum physics capability.
- CTRE swerve requires the FRC swerve physics capability.
- Differential and advanced/custom drivebases fail closed until a real adapter exists.
- Generated subsystem mocks and declared hand-authored adapters are explicit capabilities.
- A subsystem declaring simulation unavailable blocks simulation; ARES does not invent a device.
- Compatibility failure blocks simulation but does not block physical code generation or claim a
  hardware fault. Advanced extension points remain buildable while their missing simulator adapter
  stays explicit.

## Fault injection foundation

`SimulationFaultTimeline` provides deterministic, RobotClock-owned intervals for stale, invalid,
frozen, disconnected, rejected-write, bus, and brownout scenarios. It is an adapter contract, not a
claim that every current device model implements every fault. Product capability declarations must
be extended only when a league simulator consumes and verifies the corresponding behavior.

## Ownership

| Concern | Owner |
|---|---|
| Stable controller/simulator identity | ARESLib `project-schema` |
| Product selection and compatibility | ARESLib `simulation-foundation` |
| Whole-project derived plan | ARESLib `project-model` |
| Product identity in generated evidence | ARESLib `project-compiler` |
| FTC OpMode, devices, field, physics | ARESLib FTC simulator + FTC season/starter |
| FRC TimedRobot/HAL, devices, field, physics | FRC season/starter |
| Launch command, readiness, visible errors | ARES Robotics Studio |

## Validation evidence

Candidate: `10.0.0-rc.sim-product.1`

- ARESLib full tests, API check, and isolated publication passed.
- Lightbot/ARES-FTC generation, project verification, TeamCode tests, simulator tests, and APK
  assembly passed against the isolated candidate.
- FTC Starter completed the same matrix.
- ARES-FRC and FRC Starter generation, verification, full tests, and builds passed.
- Studio full tests passed against the isolated candidate.
- Consumer architecture tests prohibit FTC lifecycle imports in FRC simulation source and FRC
  lifecycle/vendor imports in FTC simulator source.
- A real 1440 x 900 Studio window rendered Lightbot and its dashboard successfully from the
  isolated candidate. After the capture, the window disappeared while its JVM remained alive; no
  new AWT crash log was produced, so the scoped `killExisting` cleanup was used. This milestone
  records that desktop-lifecycle observation rather than treating it as a graceful-close pass.

No result in this milestone represents physical-robot validation.

## Rollback and remaining debt

All work is isolated on `codex/simulator-product-hardening`. Reverting the milestone restores the
previous raw-league launch switch without changing canonical robot documents.

Remaining work is intentionally explicit:

- connect `SimulationFaultTimeline` to specific FTC and FRC device adapters before advertising a
  product-level fault-injection capability;
- add a supported differential-drive product capability only with compiled behavioral parity tests;
- separate the historical FTC `simulator` artifact name in a future major coordinate migration if
  the benefit outweighs starter/release churn;
- perform physical validation separately when hardware is available.
- reproduce or rule out the post-capture desktop-window disappearance in the dedicated desktop
  lifecycle hardening track; it is not attributed to simulator selection without evidence.
