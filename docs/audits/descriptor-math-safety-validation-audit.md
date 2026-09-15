# Descriptor mathematics and safety validation audit

Pass 226 reviews subsystem descriptor decoding, identity/path policy, units, implementation
and safety validation, and cross-subsystem interlock validation. Source commit
`3a6dbd9ae4b98597d28e06d586ff4b2b8e9f0163` is validated as local candidate
`17.0.33-rc.074ee5429e75`, with library tree
`074ee5429e759939a87fac7a9530e08c85bbf289`. No remote release or deployment occurred.

## Confirmed findings

- **Wrong defaults while decoding.** Omitted required-startup/hardware flags became false;
  omitted primitive revision, measurement scale and controller limits/filter values became
  zero because Gson bypassed constructors. The codec now applies declared model defaults
  before decoding. Explicit false and zero retain their meaning.
- **Wrong linkage center of mass.** With a declared 0.8 m link and omitted center of mass,
  decoding retained 0.175 m from the default constructor. It now derives 0.4 m from the
  declared length and preserves explicit center-of-mass values.
- **Safety intent lost through coercion/defaulting.** A string such as `"flase"` became false,
  unknown enum names were replaced by defaults, and missing tuning policy became LIVE_SAFE.
  Typed scalar readers now reject coercion, unknown enums and nonfinite numbers. Required
  device/state/control identity enums and tuning type/default/apply-policy declarations
  cannot be guessed. Nullable connection/mounting metadata remains supported.
- **Simulation ownership contradictions erased.** Explicit simulator support and adapter
  declarations were overwritten before validation. They now reach validation unchanged;
  omitted inferred metadata retains its documented generated/hand-authored behavior.
- **Numerical scale errors.** Multiplying two finite factors could overflow or underflow
  before computing a representable motor conversion. Power-of-two decomposition now avoids
  those intermediate failures and rejects an unrepresentable final scale.
- **Invalid numerical configuration accepted.** Linkage joints now require distinct,
  explicitly radian measurement fields. Simulator interaction dimensions, rates and trigger
  values must be finite. Absolute homing comparisons reject negative magnitude thresholds,
  which could otherwise be always true or never true. Integer defaults now obey numeric
  limits, including fractional and one-sided bounds.
- **Ambiguous identities and paths.** Different subsystem UIDs could share one generated
  document ID. Project validation now rejects this collision. Source/document paths reject
  Windows drive qualifications, colons/control characters and trailing dot/space segments.
  This is syntactic path validation, not filesystem or symlink containment verification.
- **Interlock form disagreed with runtime.** The form said “permit movement” for conditions
  that block movement, and numeric equality edited a text value the runtime ignores.
  It now says “block movement” and edits the numeric threshold for every numeric comparison.
  Subsystem choices include document IDs so repeated display names remain distinguishable.
- **Unused interlock overrides.** Generated runtimes ignored `safeFallbackValue`. Generated
  descriptors now reject that override and use each actuator's configured safe output.
  The form offers an explicit clear action for existing overrides. Custom hand-authored
  metadata remains available.

## Efficiency and evidence

The codec parses JSON once and deserializes the parsed tree. Integer/long decoding checks
exact values because Gson's tree reader would otherwise truncate fractional or out-of-range
numbers; an intermediate regression here was caught and fixed before source freeze.
The original streaming decoder already rejected the fractional cases, so this is preserved
behavior, not an original defect being claimed.

Three duplicate-key helper implementations were consolidated, removing their intermediate
filtered lists. The interlock form builds its target choices once per composition rather
than once per rule. These are setup/editor improvements; no robot periodic callback,
hardware read or control-loop workload was added. No performance benchmark is claimed.

The final focused run passed **135 tests**, including **26 new methods**: 14 codec, seven
numerical and five identity/interlock tests, with a shared fixture. A seeded 500-case
BigDecimal reference comparison exercises the scale calculation across the double exponent
range. Two pre-existing linkage test fixtures now explicitly declare their radian fields;
their plant and failure assertions are preserved.

Retained failing evidence contains **19 cases with 16 failures**: the initial 15 cases
produced 12 failures, followed by separate one-case reproductions for ignored interlock
fallbacks, integer defaults, dependent center-of-mass defaults and omitted tuning policy.
The initial linkage fixture lacked its second control loop; that fixture error was corrected
before the retained baseline and is not a production finding. The form mismatches were
established by source tracing, not a rendered UI test.

## Validation

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,706 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,043 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |

The suites account for 5,538 passing results, zero failures/errors and six unchanged Studio
opt-in skips. These cover three starter integration scenarios, native file chooser, dashboard
performance baseline and physical dashboard validation. Gradle results may be executed,
up-to-date or restored from cache; focused results are not counted twice.
FTC/starter generated-project checks and APK assembly passed; FRC/starter generated-project
checks passed. All 410 candidate file hashes were reverified after consumers finished.
Monorepo policy passed, including source/version/archive identity, shared guidance and links
in 388 current documents, with 38 explicitly historical records skipped. Four normalized
starter archive comparisons differ only in release version properties.

## File accounting and limits

The ledger accounts for 2,994 tracked files: 1,275 fully reviewed, 173 partially reviewed
and 1,546 pending, with zero stale or orphaned records. This is file review and appropriate
validation accounting, not universal executable coverage.

The six selected descriptor helper/validator files, the new scalar adapter, the four new
test/fixture files, and the two existing project-schema test files are fully accounted for.
The large SubsystemValidation and SubsystemStateflowSection files retain partial status
for the specific numeric-default/shared-helper and interlock-form sections reviewed here.
Previously partial model and generator-test reviews remain partial; earlier completed
linkage-test review is preserved.

All claims are limited to the inspected boundaries and observed tests. Other codecs and
the shared parser retain their existing review scope. No native Studio window, physical
robot loop timing, hardware-in-the-loop behavior or actuator response was observed.
The monorepo audit remains active and incomplete.

Evidence: `ARESLib-Kotlin/build/audit-pass226-verified-evidence/` contains baseline/focused
XML, full suite XML, command logs, candidate hashes, archive comparisons and summary.json.
