# Generated output ownership and Kotlin boundary audit

Pass 276 consolidates the planned 23-file code-generation review and adjacent fixes into one
local source freeze and consumer checkpoint. Larger generator/runtime areas retain their recorded
partial scope; this is not a claim that all generated runtime behavior has been audited.

Source commit: `7ef31eda69bcda27e87112d14d093eeddb5238b7`. ARESLib tree: `2d39a27fb81d2f652d0be3f1ab32249c7deefacc`.
Local candidate: `18.0.1-rc.2d39a27fb81d`. No public publication, push, merge, or hardware run occurred.

## Findings and fixes

- Generated subsystem synchronization could overwrite user-owned source and delete obsolete files
  before discovering another ownership conflict. Subsystem, drivetrain, and superstructure source
  sets now share one preflight and synchronization implementation. Manifest membership and an
  explicit generated header authorize replacement; indented older generated headers remain usable.
  Custom verification-manifest destinations also protect unrelated files and recognize a prior
  canonical manifest by its format and digest. Harmless trailing whitespace remains repairable.
- Starter destinations previously compared raw strings. Normalized duplicates and nonrelative/root
  destinations now fail before writes. Existing non-files are protected. Physical containment checks
  reject parent junctions that redirect outside the selected root; contained links remain usable.
  Existing-file parent conflicts and planned file/directory conflicts also fail before cleanup.
- `--check` could apply editable starters. Conflicting modes are rejected during option parsing;
  editable starter replacement still requires the exact current replacement token.
- Underscore-only declarations, keyword package segments, and unchecked registry references could
  produce invalid Kotlin. Shared validation now covers project, subsystem, drivetrain, tuning,
  verification, and superstructure entry points, with CLI layout validation before output creation.
- The project content hash omitted generated subsystem dispatch ownership. Format 9 includes sorted
  generated action keys, distinguishing generated dispatch from a hand-authored implementation of
  the same catalog. Reordered inputs still produce identical output.
- Verification JSON could split a valid surrogate pair across string chunks and fail UTF-8 writing.
  It now uses the shared Kotlin literal escaper. Compiler execution and reflective readback confirm
  exact payload preservation. Verification imports also preserve correct ownership-header indentation.

The shared synchronizer, common literal escaper, and cached source-rendering regexes remove duplicate
build-time work. This pass makes no measured robot-loop speedup claim.

## Verification

| Check | Observed result |
|---|---|
| Full codegen module | 106 tests passed; included in the library total |
| Full ARESLib | 3,012 tests in 470 suites; no failures, errors, or skips |
| Public APIs and source size | All 13 API snapshots match; size check passed |
| FTC / FTC starter | 187 / 17 tests, generated-project verification, and APK assembly passed |
| FRC / FRC starter | 306 / 206 tests and generated-project verification passed |
| Studio | Normal tests stopped at release alignment; all three test-source compilations passed |

The combined library/robot checkpoint covers **3,728 passing test results**. Gradle reused unchanged
outputs where applicable, and prior test identities were retained.
All consumers used the same explicit candidate and local Maven repository, without sibling
substitution. The candidate's 410 artifact files and the preceding candidate's 410 files were
checked for integrity. Detailed commands, XML, hashes, and summaries are in
`ARESLib-Kotlin/build/audit-pass276-verified-evidence/`.

Failing baselines cover names/destinations, dispatch identity, Unicode writing, CLI ownership,
Windows junction writes/deletion, late file/directory conflicts, and custom manifest overwrite.
The final regeneration tests also caught and corrected regressions introduced during this pass:
recognizing old indented verification headers and preserving trailing-whitespace repair. Intermediate
compiler/setup failures are not defect reproductions. Existing test identities and goldens were retained;
the obsolete-drivebase fixture now explicitly marks its disposable file as generated.

## Remaining scope and limits

Studio tests still fail the existing workflow alignment check expecting `ARES_VERSION: 18.0.1`.
Archive/workflow migration remains unapproved; tracked archives, their manifest, workflow versions,
and template references were not changed. Compilation is not Studio runtime or rendered-UI evidence.
Physical Lightbot dimensions and hardware loop/jitter validation remain unresolved.

Source-set preflight and per-file replacement do not provide whole-project rollback, crash durability,
or protection against concurrent external directory replacement. Linux/macOS link behavior was not
executed. The registry's project checks and per-document schema checks are complementary, not
redundant. Schema validation already rejects subsystem IDs that become Kotlin keywords, and the
control compiler already rejects multiple active schemes; those suspected defects were dismissed.
Generated verification complements compiler validation; its autonomous/controller checks alone do
not establish complete project validity. Continue the remaining generated controller lifecycle,
superstructure freshness/numeric boundaries, and larger drivetrain/subsystem renderer review.
