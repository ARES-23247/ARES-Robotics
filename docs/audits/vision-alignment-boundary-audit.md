# Vision alignment and core control boundaries

Pass 90 reviews the remaining core control files `VisionAlignController.kt`,
`PIDFCoefficients.kt`, and `SysIdMechanismIO.kt`. This is a local source/host audit,
not a physical alignment or characterization trial.

## Confirmed issues and corrections

- Switching the requested tag retained the previous tag's yaw rejection, EMA,
  derivative and integral history. Target identity now resets all tracking/search state.
- Losing a tag cleared derivative/filter state but retained integral effort. Reacquisition
  now starts without prior integral effort or an invented elapsed first-sample interval.
- Search beginning at timestamp zero could restart its timer. An explicit active flag
  replaces the sentinel. Forward elapsed overflow exhausts search; clock rewind starts
  a new bounded interval. Changing tags and releasing alignment reset search direction.
- Future timestamps could pass freshness checks when signed subtraction wrapped.
  Timestamp ordering is now checked before accepting the bounded age.
- Repeated timestamps invented 1 ms of integration and differentiation; rewinds could
  likewise produce a derivative spike. Zero elapsed time has neither term; rewind clears
  history. Positive time steps retain the existing 200 ms cap.
- `clampTranslationY` was unused. The existing translation magnitude cap remains, with
  an additional lateral cap applied through a common scale that preserves direction.
  A zero lateral limit therefore stops a vector requiring lateral motion.
- Finite gains could overflow multiplication or combine opposing infinities into NaN.
  Nonfinite translation products or angular composition now neutralize the complete
  command and clear history before recovery.

Ten regression methods failed on the original source. The new suite also checks
freshness edges, release/restart, search-duration sum saturation, coordinate rotation,
EMA recurrence, yaw-jump rejection, wrapped heading derivative, and retained action
ownership. The initial wrapped-derivative fixture incorrectly used an Euler pitch
outside its principal range; it was corrected to combine valid +/-90-degree pitch
with target bearing. This was a test-fixture defect, not a controller regression.

## Efficiency and interface review

Alignment reads RobotClock once per calculation and supplies that same timestamp to
the returned action. Sine/cosine of the same angle are each computed once. The public
returning API still allocates an independently owned action; reusing a mutable action
would change retention semantics. No zero-allocation or physical deadline claim is made
for this API. The generic zero-GC regression suite is included in validation.

`FlywheelSysIdAdapter` retains the existing forward-only 0..12 V characterization policy.
Both FTC and FRC executors were traced: their managers generate forward routines, and
hardware authorization remains with the owner. Validity now checks finite cached RPM
directly, avoiding a redundant unit conversion; the RPM-to-rad/s factor is finite and
less than one. Three new methods verify signed unit conversion, cached feedback validity,
voltage limits/nonfinite neutralization, stop, and absence of refresh side effects.

`PIDFCoefficients` is a data container, not an actuator validation boundary. Its formula
already uses setpoint-proportional kF; documentation incorrectly called it static friction
feedforward. That wording is corrected without adding constructor rejection that would
interfere with proposal validation. PID-only consumers and season velocity configuration
were inspected. No runtime calculation is implemented in this container.

## Limits and retained behavior

The controller continues to use the first fresh usable matching observation; this pass
does not invent an ordering priority across camera sources. Maximum-range rejection,
finite tuning fallbacks, planar target-space convention, implicit heading integral gain,
and configured search speed remain existing policy. KDoc now accurately describes
vertical-positive Y, the PID term and the exclusive 250 ms freshness boundary.
Very large coordinates whose squared range overflows remain rejected as unusable;
this is outside the configured physical camera range. Full 3D facing recovery, camera
calibration, tuning suitability, motor response and whole-loop timing require separate
physical evidence. FTC facade lifecycle beyond the direct alignment call remains a
separate scope; file review here does not claim complete caller lifecycle coverage.

## Final validation

Pending the frozen candidate matrix. Focused tests: 52 passed, including 21 new methods;
API checks passed with no public signature changes. Full evidence will replace this
section after library and consumer validation completes.
