# Coordinate mirroring and odometry vector audit

Pass 68, 2026-09-10. Source `df9b95c6`, local candidate `17.0.3-rc.6802fc554f5d`,
bound to `release/ares-source-tree.txt`. This pass reviews all of AllianceMirroring,
CoordinateTransformers (including its internal helpers), and OdometryMath.

## Confirmed issues and changes

Path mirroring added pi to the raw tangent before wrapping it. For large finite values,
that addition rounded away, leaving the path tangent inconsistent with its transformed
pose heading. Pose and tangent now share a helper that validates the raw angle, reduces it
with the existing represented-period wrap convention, and only then applies the symmetry.
Normalizing an invalid raw heading previously turned it into a finite value; Red transforms
now reject that input with IllegalArgumentException before normalization.

Coordinate results must be finite. Used field extents must be finite and positive, and an
unrepresentable translated/reflected position is rejected. Center-origin symmetries do not
use field dimensions; corner reflection uses length only, while corner rotation uses both
length and width. Blue remains an exact object-identity passthrough without validation, so
its result does not establish input validity. Origin translation preserves raw heading values
because it changes neither orientation nor axes.

Three duplicated pose-transform formulas now delegate to CoordinateTransformers. Internal
factories consistently validate the new coordinates, and the shared angle helper prevents
pose/tangent drift. Existing public signatures and constant values are preserved. The two
authored reflection conventions remain explicit: center origin reflects Y across the X axis;
corner origin reflects X across the field's length midpoint. They are not interchangeable
reflections obtained merely by translating the coordinate origin.

Path mirroring previously indexed its source list, causing quadratic linked-list traversal.
It now makes one iterator pass into a pre-sized ArrayList. A 1,000-point linked-list fixture
records 1,000 indexed reads before the fix and zero afterward. This is traversal evidence,
not a CPU timing benchmark. Red paths also reject non-finite speed/curvature/tangent and
non-finite, negative or decreasing distance. Duplicate distances remain supported. New points
and poses are independent of source points; the caller-owned event list remains shared and
is not transformed or validated here. Input must not mutate during the operation.

Coordinate KDoc previously conflated software-specific frames with these simple origin
translations and called an X-midpoint reflection an X-axis reflection. It now specifies
parallel axes and a minimum-X/minimum-Y corner. FRC dimension constants are described as
compatibility defaults; no season dimensions or consumer field configuration were changed.

OdometryMath's formulas were correct for rotation of an existing robot-frame vector, but its
class KDoc incorrectly promised zero allocation for a method returning a fresh Translation2d.
Runtime behavior is unchanged. Documentation now distinguishes scalar methods from owned
vector results, notes repeated trigonometry when requesting scalar X and Y separately, and
states that this helper does not perform SE(2) integration over changing heading. Its existing
IEEE non-finite propagation is preserved; it does not silently manufacture valid zero motion.

## Evidence

The initial seven-test boundary suite produced five failures: huge tangent transformation,
linked-list indexing, invalid raw-heading normalization, invalid extents/unrepresentable
coordinates, and malformed path scalar/distance data. Normal symmetry/involution and Blue
identity controls passed. Baseline XML/logs remain in
`ARESLib-Kotlin/build/audit-pass68-before-evidence` and `audit-pass68-before.log`.

The final focused gate passed 34 tests without failures, errors or skips, including 15 new
methods: ten coordinate boundary tests, four additions to OdometryMathTest and one allocation
test. Cases include every origin/symmetry combination, 800 matrix/heading and involution checks
(seed 6801), 1,500 odometry length/inverse-rotation checks (6802), cardinal directions, non-finite
input positions, maximal/subnormal values, output ownership, raw-heading preservation, empty
and duplicate-distance paths, event-list identity and validation of only used dimensions.

The allocation probe measured [0,0,0,248,0] bytes over five warmed scalar batches, each computing
10,000 X/Y pairs with independently consumed results. Ten thousand escaping Translation2d
results allocated 320,000 bytes. This confirms the documentation defect and provides an allocating
control. The scalar gate requires one batch within 256 bytes and total overhead within 4,096;
it does not promise every invocation is zero-byte. Unsupported counters skip explicitly.

Focused Kover covers AllianceMirroring at 53/53 lines, 36/36 branches and 6/6 methods;
CoordinateTransformers at 34/34 lines, 10/10 branches and 10/10 methods; its internal top-level
helpers at 11/11 lines, 34/34 branches and 4/4 methods; OdometryMath at 7/7 lines and 3/3 methods
(no branches). The three production files, three existing test files and two new test files
were read in full. Enum declarations and their existing serialization annotations were
inspected; generated enum machinery is not independently implemented arithmetic.
Focused XML/Kover remain in `audit-pass68-focused-evidence`.

The full library gate passed 1,662 tests without failures, errors or skips, API checks, core
Kover and isolated candidate publication in 1m49s. Full scalar allocation samples were
[128,0,0,0,0] bytes; the vector control remained 320,000 bytes. Source policy passed, including
source identity, archives, agent guidance and links in 229 current documents (38 historical
exclusions). Candidate consumers passed in dependency order: FTC 109 tests, FRC 134, FTC
starter 14 and FRC starter 34, generated-project verification and both FTC application
assemblies. Studio passed in 3m30s with shared/gateway/app tests rerun: 1,779 passes and six
opt-in skips. Dashboard smoke 56 and performance one reran and passed, along with coverage,
release alignment and production-file-size checks. Final XML, core Kover, logs and SHA-256
manifests remain in `ARESLib-Kotlin/build/audit-pass68-verified-evidence`. Six opt-in skips
remain limitations.

## Limits

Red invalid-input handling now throws; callers must handle invalid configuration/authoring
at their lifecycle boundary. These transformations do not establish actuator enable, safe
feedback, or successful physical localization. Vector rotation is not dead-wheel arc integration.
Mirroring allocates output storage and one source iterator; it is construction work, not an
allocation-free loop API. Caller-owned mutable lists require stable input during traversal.

No physical robot, live Studio window, hosted CI, field survey or loop jitter was tested.
Call sites in generated FTC/FRC autonomous orchestration were inspected for the existing
frame selections; this does not confer full review credit on those callers. The full-monorepo
audit remains active.
