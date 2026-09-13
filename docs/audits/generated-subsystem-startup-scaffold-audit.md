# Generated subsystem startup and scaffold audit

Pass 225 reviews generated registry startup, hardware scaffolding and verification contract
derivation. The source change is committed locally as
`6b49d6b0b5343edef8868972e18d30c03662cb98`. Validation uses candidate
`17.0.32-rc.15bb5db96df3`, bound to library tree
`15bb5db96df380f2befdc057a48a8b239492eee8`. No remote release or deployment occurred.

## Confirmed issues and changes

Generated startup previously caught only ordinary exceptions. A required or optional factory
throwing a serious error such as `LinkageError` abandoned earlier installed subsystems.
Rollback now attempts their closes in reverse order and propagates the original serious error.

A serious error from one close previously stopped rollback and replaced the startup failure.
All closes are now attempted, the incomplete registry is cleared, and cleanup failures are
attached to the startup failure. Identity checks prevent self-suppression and repeated entries
when several operations throw the same object.

A factory result could also become unreachable if insertion into the registry failed.
Insertion failure now aborts startup and closes both the returned subsystem and earlier
resources. A list returning false is treated as failed insertion. If insertion stores the
instance and then throws, it is closed once. Clearing failures are attached to the original
startup failure.

Ordinary optional factory exceptions still skip only that factory; optional null retains
earlier resources and required null rolls them back. Successful installation preserves order
and transfers ownership without closing. Factory code remains responsible for partial
construction before it returns a subsystem. Callers must exclusively own the mutable list,
and callbacks must not mutate it during startup. This is best-effort cleanup during errors,
not a guarantee that a JVM with exhausted resources or a failing physical device can recover.

## Review and test evidence

The original production source failed five of ten initial regression cases. The initial test
fixture compile error and missing sensor-only safety settings were corrected before that
baseline; neither was a production finding. Baseline XML is retained separately.

The final focused run passed 25 tests, including 15 new methods. Ten new startup methods cover
the failures above, null results, successful buildList ownership, rejected/partially completed
insertions, repeated throwable identity and failing clear. Existing required/optional tests
remain compatible.

Three new scaffold methods validate all 34 supported hardware/platform combinations with
caller-owned safety configuration, typed measurement/control references and neutral output
bounds. They check encoder quarter-turn/two-turn-per-second conversion to radians/radians per
second, FRC A/B addressing, and XRP built-in addressing and absent current feedback. The full
362-line scaffold implementation and its five existing tests were reviewed. No confirmed
mathematical defect or useful periodic optimization was found there; calibration, addressing,
mechanical zero and measurement bounds remain explicit descriptor responsibilities.

Two new codegen tests join every FTC/FRC template verification entry to exactly one emitted
method and check that an editable starter without generated tests promises no generated
evidence. The complete core verification contract and its three existing tests were reviewed,
including conditionals for feedback, homing, calibration, recovery, actions and allocation.
Allocation policy remains compile-level evidence rather than a measured byte-allocation
result. The new binding test checks generated names; existing full generator and consumer
suites provide compilation/execution evidence. XRP report translation and platform adapters
were traced only as context, not claimed as newly completed file reviews.

The change adds work only to initialization failure handling. It adds no periodic callbacks,
hardware reads, control-loop allocation or repeated mathematical calculation. No physical
loop timing, native UI, hardware-in-the-loop or actuator neutralization was measured.

## Validation

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,680 | 0 |
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

The suites account for 5,512 passing results, zero failures/errors and six unchanged Studio
opt-in skips. These cover three starter integration scenarios, native file chooser, dashboard
performance baseline and physical dashboard validation. Gradle results may be executed,
up-to-date or restored from cache; focused results are not counted twice.
FTC/starter generated-project checks and APK assembly passed; FRC/starter generated-project
checks passed. All 410 candidate file hashes were reverified after consumers finished.
Monorepo policy passed, including source/version/archive identity, shared guidance and links
in 387 current documents, with 38 explicitly historical records skipped. Four normalized
starter archive comparisons differ only in release version properties.

## File accounting

The ledger accounts for 2,988 tracked files: 1,261 fully reviewed, 171 partially reviewed
and 1,556 pending, with zero stale or orphaned records. This is file review and appropriate
validation accounting, not universal executable coverage.

Newly completed production reviews: GeneratedSubsystemRegistrySupport.kt,
SubsystemHardwareScaffolding.kt and SubsystemVerificationContract.kt. Their three existing
test files and the three new test files are also fully accounted for. Other files read only
to trace callers and generation boundaries retain their previous ledger status.

Evidence is retained in `ARESLib-Kotlin/build/audit-pass225-verified-evidence/`, including
baseline/focused XML, full suite XML, candidate hashes, archive comparisons, command logs
and summary.json. The monorepo audit remains active and incomplete.
