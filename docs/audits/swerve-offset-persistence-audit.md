# Swerve offset persistence and recovery

Pass 91 reviews `SwerveOffsetData.kt`, `SwerveOffsetManager.kt`, and the existing
`SwerveOffsetManagerTest.kt`. New core tests exercise storage failure boundaries,
recovery, retention, and serialization. All work is local; no robot calibration ran.

## Findings and fixes

Three tests failed on the original source:

- Explicit backup recovery selected only the newest nonempty path. A corrupt newest
  backup hid older valid backups. Recovery now tries ordinary backup files newest-first
  until one parses. Startup still uses only a valid runtime overlay or required canonical
  offsets; recovery does not silently install a backup.
- Saves within one robot-clock second reused the same backup path, losing an earlier
  recovery snapshot. Timestamped names now include independent UUIDs, preserving repeated
  saves even at a fixed simulation timestamp.
- Retention counted matching directory names as backup files and could delete empty
  directories. Discovery and pruning now include ordinary files only. Unrelated names
  remain untouched. Failed deletion propagates instead of silently reporting successful
  retention while files continue accumulating.

File acquisition previously called `readText()` before the parser's 16,384-character
limit. The reader now checks that same limit during acquisition, consuming at most one
character beyond it into the application buffer. A small read buffer and bounded builder
replace the unbounded input string; buffered decoding may read ahead internally. This
bound follows from code review and exact-limit tests, not a measured heap benchmark.

Each save captures its storage root once. Backup modification times are captured once
per discovered file before sorting, avoiding repeated filesystem queries inside comparison
calls and keeping each sort's ordering stable. These are occasional filesystem operations,
not a zero-allocation or deadline-bounded robot-loop API.

## Verified contracts

Twelve new methods plus five existing methods cover corrupt/newest/empty recovery,
same-timestamp snapshots, directory/unrelated-file retention, the newest-ten policy,
oversized input, exact size limits, strict JSON syntax and escaped duplicate keys,
nonfinite values in every component, locale-independent seven-decimal serialization,
temporary-file cleanup after a failed runtime replacement, and backup/telemetry failures.
Three of the new methods have preserved failure-before XML. The original test fixture
now restores a preexisting storage-root property after its tests.

Runtime replacement precedes backup publication and telemetry. If either later stage
fails, the exception propagates and the runtime file remains installed. A failed initial
runtime write creates no backup and publishes no success telemetry. Telemetry failure
does not undo either committed file. Operators/callers must reconcile installed runtime
state after a partial save failure; this operation is not an atomic multi-file transaction.

Backup ordering uses filesystem modification time and filename order for ties. Equal-time
ordering is deterministic, but UUID tie-breaking cannot establish the actual order of saves
on a filesystem with coarse timestamps. Legacy timestamp-only backup names remain accepted.
Retention counts matching ordinary files whether or not their JSON is valid; explicit recovery
validates their contents. External/concurrent directory mutation and cross-process calibration
serialization are not established by these tests.

Offsets remain finite signed rotations; seven-decimal formatting intentionally quantizes
them. No speculative unit-range clamp or constructor-policy change was introduced. Strict
parsing continues to require exactly four unique numeric keys and no trailing document.

## Physical and integration limits

The FRC calibration action listener was traced through fresh cached encoder positions,
offset subtraction, and this persistence API. Full action authorization/replay lifecycle
is a separate scope. No physical wheel indexing, CANcoder response, flash power-loss
durability, or whole-loop deadline was tested. The existing atomic-move fallback remains;
host successful replacement does not prove crash durability on controller filesystems.
Automatic controller-root detection, the unsupported-atomic-move fallback, and failures
of cleanup/deletion itself were reviewed but were not forced by this host test suite.

## Final validation

The final source passed 17 focused tests and all library API checks before freezing. Twelve new methods include three failure-before regressions. Public API signatures are unchanged.

Source `e2599f98053447154717a974408705604f8ca685`; library tree `af9e9c981c88fe2a6744accdd2f924d4317a18a1`.
Local candidate `17.0.3-rc.af9e9c981c88`.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| library | 2076 | 0 |
| ftc | 111 | 0 |
| frc | 148 | 0 |
| ftc-starter | 14 | 0 |
| frc-starter | 34 | 0 |
| studio | 1790 | 6 |

All groups have zero failures/errors. Library API/Kover/local publication, generated-project verification, FTC assembly, Studio Kover/version/file-size gates and monorepo policy passed. Gradle reused valid unchanged outputs; counts do not imply every test was freshly executed. Conditional Studio skips remain recorded in copied XML.

Copied XML, per-file hashes, build logs and candidate BOM identity are recorded under `ARESLib-Kotlin/build/audit-pass91-verified-evidence/summary.json`. No physical calibration, flash crash-durability, loop deadline or usable Studio-window result is claimed.
