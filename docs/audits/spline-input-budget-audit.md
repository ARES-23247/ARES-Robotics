# Spline input and allocation budget audit

Pass 96 covers spline construction boundaries, cumulative sample planning and named
PathPlanner command decoding. It follows the polynomial audit without repeating its
independent control-point math checks as new coverage.

## Confirmed defects and fixes

- Direct ParsedPathData and Hermite calls bypassed the facade's finite geometry and
  motion-limit checks. Shared validation now checks waypoint/metadata counts, bounded
  coordinates, finite headings, positive motion limits and valid metadata intervals
  before constructing samples. Existing empty/short direct-input conventions remain.
- The facade checked normalized Rotation2d.radians, which turns non-finite angles into
  zero. Validation now checks rawRadians, rejecting NaN and either infinity before
  normalization. The global angle-normalization contract is unchanged.
- Spline builders had no cumulative sample limit. Five 1,000 m segments produced
  100,001 samples despite the trajectory subsystem's existing 100,000-sample budget.
  A shared preflight counts the initial sample and every segment using Long arithmetic,
  rejecting overflow before path allocation or natural-control generation. Segment
  counts are cached and result lists pre-sized, avoiding repeated calculations and
  backing-array growth. Existing chord-based resolution and minimum ten steps remain.
- Named commands with `data.name` were rejected. The parser now supports this nested
  representation and legacy direct `name` fields. Malformed data and conflicting names
  fail explicitly. PathPlanner's [official example](https://github.com/mjansen4857/pathplanner/blob/main/examples/java/src/main/deploy/pathplanner/paths/Pickup.path)
  contains nested named commands. This does not add compound-command execution:
  sequential, parallel and other command trees remain unsupported.

Validation constants are shared with the JSON parser, and duplicate facade checks were
removed. JSON still validates types and input size before building its DTO. Direct DTO
endpoint speeds retain their existing ceiling semantics and can be clamped below the
requested value; the JSON parser's existing stricter global-speed check is unchanged.

## Regression evidence

Twelve new test methods cover cumulative overflow, preflight count ordering, invalid
direct inputs and raw headings, nested command compatibility/conflicts, exact budget
arithmetic, metadata bounds, speed ceilings and ordinary/degenerate paths. Seven distinct
methods have preserved failure-before evidence across the initial and heading runs.

The initial constant-curve fixture incorrectly required exact zero instead of allowing
floating-point Bernstein rounding. Its tolerance was corrected; that initial failure
is not counted as a product defect. Exception assertions now discard returned Paths to
avoid printing hundreds of thousands of samples when an expected rejection fails.

## Remaining scope

SplineMotionProfiler remains partial: chord-based sampling accuracy, long control
handles, curvature approximation and stationary/cusp behavior need separate review.
The JSON parser's broader schema/runtime-feature parity also remains partial; supported
named-command decoding does not imply complete PathPlanner compatibility. Input lists
must not be mutated during construction. No physical loop-time, JIT allocation-rate or
hardware result is claimed.

## Final validation

Pending frozen-candidate validation.
