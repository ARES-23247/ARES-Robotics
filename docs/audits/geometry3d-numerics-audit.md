# 3D geometry numerical and allocation audit

Pass 69 reviews `ARESLib-Kotlin/core/src/main/kotlin/com/areslib/math/geometry/Geometry3d.kt`
and its complete existing and new geometry fixtures. Source commits: `e8077354` and `d3e6f2e9`.
Isolated local candidate: `17.0.3-rc.16845252488e`, library tree
`16845252488e783efdf6b4b0ea4bf120f9fa1194`. Nothing was pushed or released remotely.

## Confirmed defects and corrections

1. **Finite vector lengths overflowed or vanished.** Squaring components before taking
   a square root returned infinity for `(3e200, 4e200, 0)` and zero for tiny vectors.
   Ordinary positive normal squared lengths retain the existing square-root path;
   extreme inputs use nested `hypot`. NaN retains precedence over infinity, matching
   the previous arithmetic contract. A genuinely unrepresentable length remains infinite.
2. **Valid quaternion normalization could become identity.** The same squared-norm
   problem discarded orientations at large or subnormal scales. The fallback now divides
   by the largest absolute component before squaring. The ordinary normal-length path
   remains direct. Neither path mutates its input. Zero and nonfinite inputs retain the
   documented legacy identity fallback; that is not a valid-feedback signal.
3. **Euler getters disagreed at pitch singularities.** Independently extracting roll and
   yaw near zero matrix denominators could produce angles that reconstructed a different
   rotation. Both getters now share a roundoff-sized singularity decision, choosing roll
   zero and the corresponding combined yaw. Pitch uses `atan2` with the horizontal matrix
   norm, avoiding the precision loss of `asin` near its endpoints. The actual convention
   is `Rz(yaw) Ry(pitch) Rx(roll)`, equivalent to fixed-axis X/Y/Z or intrinsic Z/Y/X.
   Euler angles cannot uniquely separate roll and yaw at these singularities; the returned
   angles must still represent the original orientation. This distinction is also explained
   in the [SciPy Euler conversion documentation](https://scipy.github.io/devdocs/reference/generated/scipy.spatial.transform.Rotation.as_euler.html).
4. **Rigid transforms created redundant temporary quaternions.** A private scalar Hamilton
   sandwich now computes rotated translation directly. It removes four temporary quaternion
   constructions from each transform application/inverse and those four plus the temporary
   translation difference from `relativeTo`. The arithmetic, composition order, and independently
   owned result contract remain. Constructor and in-place Euler updates now share one formula.

5. **Quaternion sandwich intermediates corrupted extreme finite translations.** Final review
   reproduced a cyclic unit rotation that turned a maximal finite vector into infinity; tiny
   vectors could also vanish. Only extreme finite vectors are now scaled before rotation and
   rescaled afterward, reusing the owned result. The single recursion level is bounded because
   the normalized largest component is one. A separate regression exercises maximal and
   subnormal vectors through an exact cyclic rotation.

The initial five-test fixture failed four tests on the old implementation; the existing
invalid-normalization behavior passed as a control. Those XML results are retained in
`ARESLib-Kotlin/build/audit-pass69-before-evidence/` with the original Gradle log. The additional failing cyclic-rotation regression is retained
in `build/audit-pass69-extreme-before.xml` and its log. The first candidate passed full validation
but was superseded by a fresh source identity after this additional correction; its logs remain
in `build/audit-pass69-initial-candidate-logs/`.

## Review and test evidence

All production declarations in `Geometry3d.kt` were read: vector arithmetic/norm, quaternion
normalization/product/conjugate, rotation construction/mutation/composition/inverse/Euler
getters, pose coordinate getters/projection/deep copy, transform inverse/application/relative
coordinates, private helpers, and mutable data-class ownership. Generated data-class machinery
is not separately treated as handwritten numerical code.

The existing four-test `Geometry3dTest` was reviewed in full. Fourteen methods were added:
thirteen in `Geometry3dBoundaryTest` and one in `Geometry3dAllocationTest`. Focused validation
also includes the five existing `ZeroGcRegressionTest` methods: **23 tests passed, no skips**.

- 1,000 vector norms compare against 80-digit `BigDecimal` square roots of exact binary-double
  values across the double exponent range (seed 6901, three-ULP output tolerance).
- 1,000 quaternion normalizations compare each component against an independent decimal
  norm/division oracle (seed 6902, four-ULP tolerance with a one-subnormal-ULP floor).
- 1,000 general 3D transforms compare translation and the composed rotation's Y basis against
  independently multiplied fixed-axis Euler matrices; inverse, relative transform and double
  inverse properties cover all translation coordinates and quaternion components (seed 6903).
- 2,000 Euler setter/getter cases include both pitch singularities and ordinary orientations
  (seed 6904). Separate cases check pitch distances of `1e-4`, `1e-6`, and `1e-8` from singularity.
- Hamilton basis products check right-handed order and noncommutativity. Tests also cover
  source mutation, nested result independence, deep copying, planar projection, vector operators,
  invalid inputs, large finite values, and the smallest subnormal value.

Focused Kover evidence reports all **98/98 lines, 40/40 branches, and 30/30 methods** across
the seven reported classes associated with this source file:

| Class | Lines | Branches | Methods |
|---|---:|---:|---:|
| Translation3d | 6/6 | 12/12 | 4/4 |
| Quaternion | 19/19 | 12/12 | 4/4 |
| Rotation3d | 19/19 | 4/4 | 8/8 |
| Rotation3d companion | 11/11 | 0/0 | 2/2 |
| Pose3d | 6/6 | 0/0 | 5/5 |
| Transform3d | 5/5 | 0/0 | 2/2 |
| Geometry3dKt helpers/extensions | 32/32 | 12/12 | 5/5 |

Coverage is execution evidence, not a proof for every floating-point input. In particular,
ordinary Hamilton products and vector sums/differences can overflow; this pass does not add
arbitrary-precision rigid-body arithmetic or normalize caller-mutated rotation storage.

## Efficiency evidence and limits

The allocation fixture warms up varying in-place Euler updates, scalar Euler reads, normal/extreme
vector norms, and escaping transform results. Five 10,000-iteration scalar batches measured
`[240, 0, 0, 0, 0]` bytes in the focused run. The gate requires a minimum batch at most 256 bytes
and at most 4,096 aggregate bytes; unsupported allocation counters produce an explicit skip.
The 10,000 escaping pose results measured **1,760,000 bytes** as a positive allocation control.

These are host JVM measurements with coverage instrumentation. They do not establish that every
call allocates zero, quantify a before/after latency gain, or measure physical robot loop deadlines.
The removed temporary constructions are a source-level reduction; JIT escape analysis may already
eliminate some of them. Object-returning transform APIs still allocate their owned results.

The `FtcVisionPortalIO` camera/tag chain calls transform inverse/application during acquisition;
that boundary was inspected to verify composition use and remaining result allocation. FTC/FRC
Limelight adapters also call in-place Euler updates. This is caller-boundary evidence, not complete
review credit for those adapters or proof of camera calibration, axis conversion, timing, or hardware.

Mutable constructors/data-class copies continue borrowing their nested values; `Pose3d.deepCopy`
owns every nested mutable object. Callers must preserve unit quaternion invariants and validate
observations before projection/control. `toPose2d` retains `Rotation2d`'s nonfinite heading fallback;
zero/invalid normalization likewise retains identity. Neither compatibility behavior proves validity.

## Full validation

- Final candidate library: **1,676 passed**, no failures/errors/skips; API checks, core Kover,
  and isolated local publication passed in the same successful Gradle invocation (1m 48s).
- FTC: **109 passed**; FRC: **134 passed**; FTC starter: **14 passed**; FRC starter: **34 passed**.
  Both FTC generated-project verifications and debug APK assemblies passed; FRC products also
  passed generated-project verification.
- Studio: **1,779 passed, six opt-in skips**. Shared, gateway and app tests reran against the
  final candidate. Dashboard smoke **56 passed**, dashboard performance **one passed**; these
  tasks reran. Coverage verification, version alignment and production file-size ratchet passed.
  The Studio Gradle invocation completed in 2m 34s; that is build/test duration, not robot timing.
- Monorepo source identity, starter archive checks, shared guidance and Markdown links passed:
  **230 current documents**, with 38 explicitly historical documents excluded from link checking.
- Final full-library scalar allocation windows were `[120, 0, 0, 0, 0]` bytes; 10,000 escaping
  pose results again measured 1,760,000 bytes.

JUnit XML, per-group SHA-256 manifests, and core coverage are saved under
`ARESLib-Kotlin/build/audit-pass69-verified-evidence/`. Final focused XML/coverage is under
`ARESLib-Kotlin/build/audit-pass69-final-focused-evidence/`; the final focused log is
`ARESLib-Kotlin/build/audit-pass69-final-focus-v3.log`. Full product and policy logs use the
`audit-pass69-` prefix in the same build directory. These machine-local artifacts are ignored;
this report and the file-review ledger are tracked.

This pass adds five fully reviewed files to the ledger: one production file, the existing fixture,
two new fixtures and this report. The overall audit remains active. Six opt-in Studio tests,
physical camera calibration, whole-robot loop timing and hardware behavior were not validated.
