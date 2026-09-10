# AprilTag interchange numerical and input audit

Pass 70 reviews the complete `AprilTagMapCodec.kt`, its existing five-method fixture,
and the new nineteen-method boundary fixture. Local source commits: `11053dc7` and `54f14f85`.
Candidate: `17.0.3-rc.3753101451c6`; library tree:
`3753101451c65933d5ba32870d491d661dee7c71`. Nothing was pushed or released remotely.

## Confirmed issues and fixes

- Duplicate quaternion normalization overflowed or underflowed, losing valid orientation.
  Duplicate Euler extraction also lost rotations at pitch singularities. WPILib-format
  conversion now reuses the audited shared `Quaternion` and `Rotation3d` implementations.
  Explicit finite/nonzero checks precede normalization, so its legacy identity fallback
  cannot turn invalid file feedback into an accepted tag.
- Default wire objects accepted unrelated JSON as an empty layout and missing pose parts
  as identity/zero. Foreign layouts now require their arrays, complete nested poses, and
  numeric components. ARES import requires the explicit current schema version.
- Gson coercion truncated/wrapped numeric IDs and accepted strings as numeric inputs.
  IDs now require exact positive 32-bit integers and uniqueness. Floating-point fields
  require finite JSON numbers; metadata strings cannot be numbers, booleans, or null.
- Limelight matrices could contain scale, shear, reflection, or an invalid homogeneous row.
  Imports check sixteen finite entries, the homogeneous row, row orthonormality and a
  positive unit determinant. Decimal-rounding tolerances are explicit: `1e-9` bottom
  row, `1e-5` row Gram entries and `3e-5` determinant. Euler pitch uses `hypot`/`atan2`.
- Invalid field dimensions could silently select defaults. Only zero canonical ARES
  dimensions select league defaults; other dimensions must be finite and positive.
  Coordinate shifts reject unrepresentable results. Invalid explicit tag sizes fail;
  meter/millimeter conversion cannot silently underflow to zero or overflow to infinity.
- Limelight field dimensions were discarded despite vendor support. Optional `fieldlength`
  and `fieldwidth` now survive import and field-aware export. The list-only export omits
  unknown dimensions. Missing family/size stay unspecified and produce preview metadata
  warnings; no substitute family or size is invented.

The first eight regression methods produced seven failures and one valid control before
implementation. Separate regressions then reproduced lost vendor dimensions and metadata
string coercion. Baseline XML/logs remain under the ignored `audit-pass70-` build prefix.

## Mathematical, ownership, and efficiency evidence

Final focused validation: **42 passed**, comprising 19 new boundary tests, five existing
codec tests, and 18 shared geometry tests. The new fixture exercises all three league
origin shifts for field-aware Limelight interchange, both pitch singularities, quaternion
scales from the smallest subnormal through maximum finite double, malformed JSON/types,
exact IDs, missing/invalid metadata, dimension defaults, shift overflow and sorted output.
Five hundred deterministic cases (seed 7001) compare both interchange rotations with
independently multiplied X/Y/Z matrices; six-decimal rounded matrices remain accepted.

Final focused Kover reports **232/233 lines, 208/216 branches and 22/22 methods** for the
codec object, including the FTC-compatible parser correction. Missed branches are not described
as exercised. Coverage does not prove every floating-point input or complete interchange
compatibility. The fixture and its helper/reference calculations were read in full.

Sorting does not mutate caller lists; separate imports own their result lists. Immutable
values can be shared. Reused normalization/Euler math removes redundant implementations,
and canonical tag field-name arrays are created once instead of once per parsed tag.
Parsing, sorting and returned geometry still allocate outside periodic robot loops. No
before/after latency, whole-robot deadline, jitter or physical hardware result is claimed.

The first candidate passed 1,695 library tests but failed the FTC field-asset test with
`NoClassDefFoundError: com/google/gson/Strictness`. FTC excludes the standalone Gson
artifact and supplies its SDK runtime. The final parser uses a non-lenient `JsonReader`
and the JSON-element adapter directly, avoiding both the unavailable API and the older
`Gson.fromJson` leniency override. It also checks complete input consumption. Two FTC
regressions cover malformed/exact-ID/string input and valid dimensions/large quaternion
inputs on the actual consumer classpath. The initial candidate is superseded, not reused.
Legacy strict reader mode is not a claim of complete RFC JSON conformance.

## Format contracts and remaining integration work

[WPILib's layout documentation](https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/apriltag/AprilTagFieldLayout.html)
specifies meters and a blue-wall corner coordinate frame. Our raw WPILib encoder/decoder
currently preserve stored positions; they do not choose a target league frame. Studio's
`FieldAprilTagTransfer` calls these raw functions for all leagues. **Its FTC import/export
frame mapping needs the next integration pass**, including independent input fixtures,
not just a round trip. Codec ledger status remains partial for that open integration item.
There is also an unresolved XRP frame disagreement: project metadata requires
`CENTER_ORIGIN_CCW`, whereas earlier JVM simulation documentation and the current field-aware
fmap adapter use a corner origin. The next pass must reconcile producers and consumers;
three-league round-trip tests alone cannot resolve that contract conflict.
Studio also replaces dimensions when applying a replacement import, after preview poses
have been converted using the current field dimensions. Different source/target dimensions
need operation-aware verification for replacement versus merge in the next pass.
The inspected Studio preview carries dimensions and omitted-metadata warnings; this pass
does not credit the entire field-editor workflow or physical camera calibration.

[Limelight's map specification](https://docs.limelightvision.io/docs/docs-limelight/pipeline-apriltag/apriltag-map-specification)
describes row-major transforms in SI units and millimeter tag sizes. Its FRC example uses
centered positions, consistent with the vendor
[coordinate-system documentation](https://docs.limelightvision.io/docs/docs-limelight/pipeline-apriltag/apriltag-coordinate-systems). The public [map builder](https://tools.limelightvision.io/map-builder)
export implementation also writes optional field dimensions; its downloaded public bundle
is retained locally as `build/audit-pass70-vendor-map-builder.js`. Existing center/corner
Limelight translation conventions are preserved. No vendor application or camera upload
was performed.

ARES intentionally requires unique positive tag IDs, even though vendor tools can construct
ID zero or nonunique layouts. Unknown extra metadata such as map type, images and points
of interest is not round-tripped. Duplicate JSON object keys retain Gson behavior. Caller
file-size limits and full canonical field-document validation remain outside this adapter.
This is an audit of ARES interoperability code, not a finding against upstream WPILib.

## Full validation

- Final library: **1,695 passed**, no failures/errors/skips; API checks, core Kover and
  isolated local publication passed in the same invocation (1m 45s).
- FTC: **111 passed**, including both new SDK Gson regressions; FRC: **134 passed**;
  FTC starter: **14 passed**; FRC starter: **34 passed**. Generated-project verification
  passed for all four products; both FTC debug APK assemblies passed.
- Studio: **1,779 passed, six opt-in skips**. Shared, gateway and app tests reran against
  the final candidate. Dashboard smoke **56 passed** and performance **one passed**;
  both tasks reran. Coverage, version alignment and production file-size checks passed.
  The Studio invocation completed in 4m 39s; that is build/test duration, not loop timing.
- Source identity, starter archive hashes, shared guidance and documentation links passed:
  **231 current documents**, with 38 explicitly historical documents excluded.

Per-product JUnit XML, SHA-256 manifests and core Kover are retained under the ignored
`ARESLib-Kotlin/build/audit-pass70-verified-evidence/` directory. Final focused evidence is
under `build/audit-pass70-final-focused-v2-evidence/`; corresponding logs use the
`audit-pass70-` prefix. The initial failed candidate's logs and FTC error XML remain
separate. Every owned build process reached a terminal exit code before this record.

This pass adds four reviewed records (three fixtures and this report) and one partial
production record. The broader audit remains active; source/target field-frame agreement,
operation-dependent import dimensions, six opt-in Studio tests and physical hardware
validation remain explicitly outside the completed evidence.
