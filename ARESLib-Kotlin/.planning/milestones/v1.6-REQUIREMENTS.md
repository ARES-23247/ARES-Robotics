# Requirements: Advanced Path Generation (v1.6)

## Scope
Extend the existing `ARESLib` pure logic `core` module to generate mathematical splines and motion profiles from JSON waypoints. This transforms the linear pathing into smooth, time-parameterized curves while maintaining the library's 100% offline, immutable, functional paradigms. Support for triggering `RobotAction`s via PathPlanner event markers must also be implemented.

## Core Features

1. **Quintic Hermite Spline Interpolation**
   - Given a list of waypoints (with coordinates and tangent vectors), calculate the polynomial coefficients to generate a continuous, smooth `X(t)` and `Y(t)` curve.
   - Support `sampleAtDistance(d)` by approximating the curve arc length.

2. **Motion Profiling (Trapezoidal / S-Curve)**
   - Calculate maximum achievable velocities and accelerations along the curve.
   - Output a time-parameterized trajectory that respects maximum bounds (e.g., stopping exactly at a specific waypoint, or slowing down for sharp turns).

3. **Event Marker Integration**
   - Parse `eventMarkers` from PathPlanner JSON files.
   - Provide a mechanism inside the `Path` model to query what events should fire at the current distance or time.
   - The FRC/FTC bridge or `DesktopSimLauncher` will poll this and dispatch a `RobotAction.PathEvent(name)` to the central reducer.

4. **Desktop Simulation Validation**
   - The `DesktopSimLauncher` must successfully render the advanced curved splines using AdvantageScope's trajectory rendering topics.
   - The simulated physics rigid body must accurately trace the curves using the `HolonomicDriveController`.

## Acceptance Criteria
- [ ] Spline generation code produces curves mathematically equivalent to PathPlanner GUI rendering.
- [ ] Motion Profile halts the simulated robot perfectly at commanded stop points without drifting.
- [ ] `RobotAction.PathEvent` is dispatched accurately at the requested distance threshold.
- [ ] The full logic operates with zero hardware dependencies (`com.qualcomm`, `edu.wpi.first.wpilibj`) and passes `core:test`.
