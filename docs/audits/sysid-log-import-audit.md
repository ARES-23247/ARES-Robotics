# SysId log import audit - pass 15

This pass extracts and reviews the complete standalone `SysIdLogParser` and its regression
tests. `SysIdDataCollector.parseLogFile` delegates to it; the live indexed collector remains
partially reviewed with its separate completion, lifecycle and capacity fixes still open.

## Findings and changes

- Flattened JSON used `mapNotNull`, so a malformed position could shift velocity into its
  slot and acceleration into velocity. Parsing now validates each declared position without
  compacting columns. The aliases `SysId/Data`, `SysId_Data` and `sysid_data` remain supported.
- Explicit zero acceleration was interpreted as missing whenever a file contained only
  zeros. Missing and supplied acceleration now remain distinct per row. Missing values use
  a finite velocity derivative with a real timed predecessor; an initial missing value is
  omitted because its acceleration cannot be inferred. Malformed supplied values are rejected.
- CSV timestamps could fall back to row indices, inventing a 1 ms sample period and corrupting
  derivatives and subsequent tuning. Time is now required and finite, nonnegative, in the
  shared supported domain, and representable in milliseconds. No row-number fallback remains.
- All accepted rows are chronological. Identical duplicates count once; conflicting duplicate timestamps are excluded rather
  than allowing conflicting values or zero-duration derivative intervals into the fit.
- CSV supports explicit seconds/microseconds headers, quoted commas and doubled quotes.
  Unqualified time headers and JSON timestamps remain milliseconds. Numeric values are finite;
  malformed rows are isolated after the format is recognized. Unsupported multiline CSV fields
  are rejected, not partially interpreted as measurements.
- The parser consumes lines through one iterator and keeps one ordered timestamp map before
  producing the result. It no longer repeatedly scans for the first line or creates a second
  full list solely to replace every acceleration. This is finite-file processing; it does not
  establish bounded live collection or zero allocation.

## Input contract

CSV requires time, voltage and velocity columns; acceleration is optional. Unqualified
`time`, `timestamp`, `t` or `ts`, and explicit millisecond forms, use milliseconds. Use
`Time (s)`/`time_s` for seconds or `time_us` for microseconds. Fractional milliseconds
cannot be represented by `AlignedDataRow`; they are rejected except for conversion roundoff.
An absent acceleration column or a present blank acceleration field means missing data.
An explicitly supplied zero stays zero. A missing field in a truncated CSV row is malformed.

JSONL accepts the packed `[timeMs, voltage, position, velocity, acceleration]` shape or
individual timestamp/voltage/velocity/acceleration fields and their existing aliases.
Omitted or null acceleration can be derived from an earlier valid timed velocity. Position
in a packed row must be numeric even though this fit consumes velocity. Extra packed fields
do not change the first five positions. A malformed packed row does not fall back to unrelated
scalar fields in the same object.

## Validation

The original thirteen audit methods produced **eleven failures** against the old parser.
Baseline XML is `ARESLib-Kotlin/build/audit-pass15-baseline.xml`. The final suite has
**21 passing methods**. The original thirteen first passed through the existing collector
API. Final tests run directly against the pure parser, with one explicit collector-entry
integration test, avoiding repeated database/service setup for mathematical parsing cases.

The first full run stopped at test compilation because a quoted-CSV fixture accidentally
closed a Kotlin raw string. The fixture was corrected; this run is not counted as passing.
A successful full rerun was followed by a whitespace-after-BOM correction and test extension.
The final full run passed against unchanged candidate `17.0.3-rc.b81c0156add9`:
**1,279 passing Studio tests with six opt-in skips**, plus **56 dashboard smoke tests**
and **one performance-baseline test**. App tests executed; shared/gateway reused up-to-date
results. No library or robot source changed, so robot consumer tests were not rerun.

Kover reports **97/97 executable lines (100%)** and **151/204 branches (74.0%)** in the
parser. This is execution coverage, not proof of every possible log or malformed token.
XML is `ARES-Analytics/app/build/reports/kover/report.xml`; test XML is under
`ARES-Analytics/app/build/test-results`. The configured app coverage gate, production file-size
check, release alignment, monorepo policy, shared guidance and links in 176 current documents
passed. Library identity and starter hashes are unchanged. Logs are
`ARESLib-Kotlin/build/audit-pass15-*.log`, with final execution in `audit-pass15-studio-final.log`.

## Remaining work

Indexed live rows can still be accepted on receipt of their last channel before all channels
exist. Their millisecond-only key can merge distinct microsecond source samples. Capacity
cleanup only occurs on completion, so incomplete rows can grow without that check. Live
string samples also use compaction, and growing history is copied on every completed row.
The next pass addresses that runtime path and run/session transitions. `SysIdService`
regression/FFT and other repository files retain their outstanding coverage. No robot,
physical calibration, rendered Studio window or hardware timing was tested in this pass.
All work remains local; the repository-wide goal stays active.
