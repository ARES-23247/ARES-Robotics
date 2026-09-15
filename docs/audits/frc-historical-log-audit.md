# FRC historical log inventory audit - pass 123

## Scope and method

Accounted for all 58 tracked files under `ARES-FRC/logs`, totaling 33,750,160 bytes. These are
historical data artifacts, not executable source or evidence that the current robot works.
Read every CSV cell and WPILOG record. Original captures remain byte-for-byte unchanged.
Also reviewed the complete FRC `.gitignore` and `.gitattributes` files.

The CSV scan checked UTF-8/CSV structure, unique columns, row widths, timestamp ordering and
finite numeric/pipe-delimited array values. The WPILOG scan checked the version/header, every
record boundary through exact EOF, control/string lengths and UTF-8, entry definitions, typed
payload sizes, booleans, numeric finiteness and per-entry timestamp ordering. All JSON-typed
payloads were parsed with nonfinite JSON constants rejected.

Independently read all eight WPILOGs with the installed WPILib 2026.2.1 `DataLogReader`, invoking
the typed record getters. Its record counts exactly matched the strict byte-boundary scan.
Used `forEach` rather than assuming the iterator alone proves complete EOF consumption.
The source and binary reader dependency hashes are retained with local evidence.

## Results

The 50 CSVs contain four empty files and 46 single-row snapshots. Every nonempty file has the
same 25-column header and the same telemetry values apart from its timestamp. They provide no
time series for assessing loop timing, motion, estimator convergence or repeated hardware behavior.
The empty captures contain no measurement evidence. None were repaired by inventing observations
or deleted merely because they are redundant.

| WPILOG capture | Bytes | Records |
|---|---:|---:|
| FRC_20260517_230312.wpilog | 3,531 | 89 |
| FRC_20260517_230600.wpilog | 19,471 | 820 |
| FRC_20260517_230831.wpilog | 139,935 | 6,724 |
| FRC_20260517_235626.wpilog | 142,677 | 91 |
| FRC_20260518_000001.wpilog | 620,735 | 771 |
| FRC_20260518_000020.wpilog | 2,561,293 | 2,331 |
| FRC_20260518_000217.wpilog | 20,789,125 | 20,848 |
| FRC_20260518_111618.wpilog | 9,439,215 | 11,750 |

All 43,424 records were consumed. Their types are string, JSON, int64, boolean, double and
double array. All 28 JSON records parsed. No malformed/undefined records, nonfinite numeric
values or per-entry timestamp rewinds were found. This establishes readable historical data,
not provenance, completeness of a physical run, or correctness of the observations themselves.

## Housekeeping and validation

Removed the obsolete `.gitattributes` rule targeting the absent source-tree
`src/main/kotlin/com/areslib/frc/generated/GeneratedAresProject.kt`. Current generated plumbing
lives under build outputs. Verified that the Unix Gradle launcher still uses LF and the Windows
launcher CRLF. Verified that new logs, build outputs and `.ares/local` data remain ignored.
Tracked historical captures remain tracked; ignore rules do not remove them retroactively.

Monorepo policy, current documentation links and staged whitespace checks passed. Runtime source
and dependencies did not change, so no additional robot test or allocation suite was run. The
standalone WPILib reader process exited successfully. Per-file content hashes, record summaries,
reader source and output are retained in `ARESLib-Kotlin/build/audit-pass123-verified-evidence/`.

The coverage ledger classifies all captures as reviewed data with explicit limits. Empty CSVs
use not-applicable validation; readable captures use passed structural validation. Neither is
counted as a robot functional test. No push, merge, release or hardware run occurred.
