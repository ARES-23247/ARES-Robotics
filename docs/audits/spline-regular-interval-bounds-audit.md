# Spline regular-interval curvature bounds audit

Pass 100 reviews interval speed ceilings and numerical range in spline construction. The profiler
remains partially reviewed: this pass does not certify singular-adjacent intervals or arc-length accuracy.

## Confirmed defects and fixes

1. Sampling curvature only at emitted positions could miss a regular interior peak. For controls
   `(0,0), (0,100), (1,80), (1,0)`, an analytical off-grid point has curvature about
   `245.8921574 / m` while the old interpolated speed was `2 m/s`, exceeding the `2 m/s²` ceiling.
   Regular intervals now bound the derivative norm from below and curvature numerator from above,
   then cap both endpoint speeds. Linear speed interpolation cannot exceed those endpoint caps.
2. `sqrt(2 / curvature)` overflowed before taking the square root for tiny positive finite curvature,
   although the resulting speed ceiling was representable. The pointwise calculation now uses
   `sqrt(2) / sqrt(curvature)`. Interval arithmetic also avoids intermediate squared-speed overflow.

The preserved initial XML contains three methods and two failures. The third method confirms that
distinct stationary roots rounding to the same sample position already retain their stop classification;
it is additional coverage, not a newly fixed defect.

## Mathematical and efficiency review

For the exact stored control coordinates, let `q = B'/3` and `N = cross(q,q')`. Both components
of `q` and `N` are quadratic. Exact Bernstein control ranges enclose them on each interval.
The component box gives a conservative lower derivative norm `L`, and the numerator range gives
`Nmax`. Thus `abs(curvature) <= Nmax / (3 L³)` and a sufficient squared speed limit is
`6 L³ / Nmax`. Conversion and square-root steps round downward. Decimal arithmetic preserves
tiny numerators and large squared speeds without binary intermediate overflow.

An unresolved regular derivative box is bisected iteratively. Refinement shares the existing
100,000-sample budget and rejects unrepresentable splits. Both parsed and generated spline paths
use these bounds. Straight curves bypass interval arithmetic. Exact derivative coefficient construction
is shared with stationary-root detection; no duplicate coefficient implementation remains.
The construction adds exact arithmetic per curved edge, may conservatively reduce speeds and may
add samples. It runs during path construction, not follower periodic evaluation. No timing speedup
or measured allocation-rate improvement is claimed.

Known simple stationary stops and exact singular endpoints retain previous pointwise handling.
Their adjacent intervals receive no finite whole-interval curvature certificate. This limitation needs
a separate speed-shape analysis; arbitrary zeroing of regular near misses is not used.

## Regression coverage

Nine new methods cover the two reproduced failures, rounded root coalescence, more than 10,000
independent power-basis curvature evaluations over deterministic random regular intervals, square-root
scaling down to geometry scale `1e-240`, near-stationary refinement without false stops, the shared
sample budget, straight-path bypass and the explicit singular-endpoint limitation.
The focused pathing run passed 228 tests and API checks. Full candidate validation follows the frozen source.

## Remaining scope

Singular-adjacent speed profiles, arc/chord accuracy, sampling with long control handles, point-towards
zone refinement and construction cost under representative robot workloads remain open. Host tests
do not establish physical loop timing, actuator behavior or a usable Studio window.
