# Driver profile persistence audit

Pass 248 completes the remaining profile-storage boundary of `DriverAnalysisService`.
The numerical driver-analysis behavior reviewed in [pass 247](recorded-driver-analysis-audit.md)
is retained. This pass changes desktop persistence only; profile values remain manually chosen
input-shaping parameters, and no robot tuning or motion command is issued.

Studio source tree: `8bd1a273adf5e54af8c21fa3a4c77a6ef1ae42ce`.
Unchanged library source tree: `526a048d8dfb1092e89be95c8e906e9fbc516027`.
Consumer validation uses the existing local candidate `17.0.42-rc.526a048d8dfb`.

## Confirmed defects

Seven regression cases failed against the previous implementation:

- Save and delete changed memory before persistence succeeded. A failed operation therefore
  appeared successful to readers while the file retained different data.
- A directory at the profile path was classified as corrupt JSON and moved aside. Other read
  failures were also caught by the same recovery path rather than propagated as IO failures.
- Independent service instances persisted stale private maps, losing another instance's saves
  or resurrecting its deleted profiles.
- Negative spectral frequency/amplitude metadata was accepted.
- Duplicate persisted names silently selected one profile instead of reporting invalid input.

## Corrected behavior and efficiency

`DriverProfileStore` owns one shared snapshot and commit lock per canonical file in this process.
Its weak registry does not retain unused stores. Readers access the latest committed immutable
snapshot without disk IO or waiting for a writer; previously returned lists remain unchanged.
Construction still performs a synchronous initial load, accurately described in the service API.
The application registry already shares a lazy service; no new background worker is introduced.

Save/delete run on `Dispatchers.IO`. Under the file's lock, each mutation checks cancellation,
reads and validates the current file, incorporates already completed external edits, and writes
through the existing unique-temp, flushed, atomic-replacement helper. Memory changes only after
successful replacement. A semantically unchanged save or absent-name delete avoids replacement.
Canonical path aliases and concurrent service instances share this boundary.

Profiles require a nonblank name of at most 256 characters, finite positive response parameters,
and finite nonnegative spectral metadata. Files are bounded to 1 MiB and 256 profiles; these
limits reject excessive input without overwriting it. Finite `Double.MAX_VALUE` slew values and
explicit empty lists retain their existing meaning. Parsing uses strict UTF-8 and unique names.

Only malformed/invalid contents trigger initial recovery. The exact original bytes are backed
up before defaults replace the main file. Backup failure preserves the original; replacement
failure retains both the original and completed backup. Directory, permission/read and budget
failures propagate without quarantine. Invalid data encountered during a mutation is preserved
and reported, rather than silently replaced by defaults.

Cancellation is checked before reading and immediately before starting replacement. A native
synchronous write already in progress is not interruptible: if it commits after cancellation,
the shared snapshot must still reflect the committed file even though the caller is canceled.
The injected pre-replacement regression verifies this consistency explicitly.

## Validation

Focused validation passed 63 tests across four suites: 23 new persistence regressions,
seven existing driver-service cases, 28 recorded-driver analysis cases and five summary cases.
All seven preserved failing baseline methods pass in both focused and full validation.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,406 | 6 |

Full Studio validation has 2,455 passing results, zero failures/errors and six unchanged opt-in
skips. Focused tests are not counted twice. Tests ran against the unchanged local library
candidate; all 410 candidate file hashes were rechecked. Monorepo policy passed, including
409 current-document links and 38 explicitly historical records.

Two earlier attempts failed from system memory pressure before tests could complete. The
successful runs use a local init script with a 32 MiB initial test heap and one worker; maximum
heaps and assertions are unchanged. No shared build settings or unrelated processes were changed.

## Coverage and limits

The ledger accounts for 3,079 tracked files: 1,406 reviewed, 195 partially reviewed and
1,478 pending, with zero stale or orphaned records. There are 1,673 files still requiring full
review completion. The newly added helper, test and report are included; completion of the
existing service removes one file from the prior partial backlog. These are file-review and
appropriate-validation counts, not universal executable test coverage.

DriverAnalysisService and the extracted store are reviewed within the documented process-local
ownership contract. Reads do not watch external file changes, and this lock cannot serialize
simultaneous writes from another process. Synchronous construction and native IO interruption
are documented behavior, not promises of asynchronous startup or rollback after commit. Atomic
move support remains required; directory fsync remains best-effort on platforms such as Windows.

Tests use owned temporary profile paths and mock the unrelated database/solver dependencies.
Failure injection and controlled concurrency establish persistence behavior, not physical disk
power-loss durability or measured elapsed-time speedups. No library/version/archive change,
rendered UI, robot-loop timing, hardware/HIL, remote CI, push, merge, release or deployment is
claimed. Local baseline/focused/full XML, logs, source identity and candidate checks are under
`ARESLib-Kotlin/build/audit-pass248-verified-evidence/`.

The overall monorepo goal remains active. Independent remaining boundaries include SysId
session-load cancellation and acknowledgement provenance, summary ownership/atomicity, and
history/widget lifecycle behavior.
