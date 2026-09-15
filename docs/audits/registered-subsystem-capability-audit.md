# Registered subsystem lifecycle and capability audit

Pass 224 continues the non-overlapping audit in the isolated local audit worktree. The new
cohort is the shared AresRobot lifecycle facade, generated subsystem capability derivation,
and their executable tests. Subsystem interface documentation and the inherited FTC shutdown
boundary are included because the new findings cross those contracts.

## Findings and fixes

1. **Duplicate and externally mutable lifecycle membership.** Registering the same subsystem
   twice repeated its sensor, output and close callbacks. The exposed List was the actual
   mutable ArrayList, so a caller could remove a safety participant. Registration is now
   idempotent by identity, preserving distinct equal objects. Cached read-only snapshots stay
   stable across later registration and closure. Membership changes are rejected from lifecycle
   callbacks; initialization remains the mutation phase.

2. **Subsystem closure was incomplete and reversible.** It closed resources without first
   requesting neutral, retained them in the active registry, and closed them again on every call.
   The facade now detaches its registry and marks it terminal before callbacks, attempts neutral
   on every subsystem before closing any, then attempts every close. Further registration/read/
   write calls are rejected. Closing from a write callback prevents later active writes in that
   batch. Concurrent foreground IO still must be quiesced by the platform owner.

3. **Invalid power scale reached subsystem output callbacks.** Finite scale is clamped to zero
   through one; nonfinite scale becomes neutral. Each subsystem must honor its declared neutral
   on scale zero and retain its own configuration, enable/arm and fresh-feedback checks.
   This boundary does not invent a global enable policy or bypass hardware safeguards.

4. **Serious cleanup failures were swallowed.** Subsystem safety and close now retain the first
   non-Exception throwable, attempt the remaining participants, suppress distinct additional
   failures and rethrow the first. Ordinary exceptions retain the existing best-effort behavior.
   A reentrant safeAll leaves one traversal in charge; external registry safety is still attempted.

5. **FTC shutdown omitted inherited registered subsystems.** FtcBaseRobot.close now invokes
   shared subsystem teardown before service/registry shutdown. Its failure accumulator also
   checks throwable identity, so the same failure from safety and subsystem closure cannot
   self-suppress and skip remaining resources. The FTC tests cover neutral/close once and
   preserved primary failure with continued auxiliary-resource cleanup. Existing FRC lifecycle
   integration passes with the shared facade changes.

6. **Duplicate identities bypassed catalog collision checking.** The old merge compared the
   last manual entry for a key, then retained the first entry. A conflicting first entry followed
   by an equal generated descriptor could therefore shadow generated behavior. Duplicate manual
   keys now fail, including equal duplicates, consistently with catalog schema rules. Duplicate
   subsystem IDs also fail before derivation can silently discard one definition. One manual
   descriptor equal to its generated descriptor remains valid; merging remains idempotent.

7. **Lighting defaults could be absent from their own options.** A valid default just above a
   color's lower range bound could match that excluded color within the 1e-9 naming tolerance.
   Derived enum defaults are now chosen only from the filtered options. Empty named ranges retain
   numeric fallback. Cycle descriptions refer to the first/last allowed color, so restricted
   ranges no longer claim to wrap between white and red.

Capability derivation remains an allocating setup operation. Read/write traversal and registry
inspection use indexed access to cached snapshots, with copies only during registration.

## Focused evidence

The initial baseline ran 20 tests and reproduced 15 failing scenarios: nine lifecycle, four
catalog and two FTC shutdown scenarios. These overlap the findings above; they are not fifteen
independent bugs. The completed focus runs 63 tests, including 25 new methods, existing catalog,
hardware-registry safety, allocation and FTC/FRC lifecycle tests. All public API checks pass
without a public API dump change.

New tests exercise identity versus equality, read-only retained membership, scale bounds and
nonfinite values, all-neutral-before-close order, terminal/idempotent closure, serious and shared
failure handling, ordinary-failure continuation, one-state-per-write-batch behavior, reentrant
close/safety, registration during callbacks, store/timestamp/read ordering and steady traversal.
Catalog tests cover typed numeric/boolean/text defaults and units, integer/double Prism defaults,
homing, range-filtered lighting defaults, invalid/duplicate documents and keys, deterministic
ordering, retained manual metadata and idempotent merging. The five existing capability tests
cover explicit recovery/calibration confirmations and hand-authored catalog ownership.

The warmed desktop JVM measured **0 bytes over 10,000 shared robot read/write batches** with
eight registered subsystems and repeated registry inspection. This covers the facade traversal,
not allocation inside arbitrary subsystem callbacks or a whole physical robot loop. Existing
registry refresh/safety and core allocation regressions pass; moving Store/EKF reductions measured
905,560 bytes per 1,000 in the focus.

## Candidate and downstream validation

Implementation commit: `87b237d100d3252a9960c20aa417b4ea55a0cc18`.
Final source/manifest commit: `caa1d0fede9d964a78e5fcdaec05a569ec257fa0`.
Library tree: `23925b619c605a598ec1e94d9548e8c89cfdccbc`.
Local candidate: `17.0.31-rc.23925b619c60`.

Versions are ARES/FTC/FRC starters 17.0.31, Studio 7.0.31 and XRP/Lightbot 3.0.30.
An overlapping text replacement in local audit preparation left three ARES pins at 17.0.30;
the release-manifest check stopped validation before any candidate publication. The pins were
corrected in the final source commit without changing the library tree. The failed gate log is
retained; no published candidate bytes were replaced. Studio's later preflight also caught old
archive filenames in workflow URLs: the strict version-token replacement had excluded versions
followed by ".zip". Exact archive basenames were corrected before Studio tests ran; those
preflight logs are retained separately from the final successful validation.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,665 | 0 |
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

The suites account for 5,497 passing results, zero failures/errors and six existing Studio
skips. The skips cover three opt-in starter integration scenarios, native file chooser,
dashboard performance baseline and physical dashboard validation. Gradle results may be
executed, up-to-date or restored from cache; focused results are not counted twice.
FTC/starter generated-project checks and APK assembly passed; FRC/starter generated-project
checks passed. All 410 candidate file hashes were reverified after consumers finished.
Monorepo policy passed, including source/version/archive identity, shared guidance and links
in 386 current documents, with 38 explicitly historical records skipped. Four normalized
starter archive comparisons differ only in release version properties.

## Coverage and boundaries

The ledger accounts for 2,984 tracked files: 1,251 fully reviewed, 171 partially reviewed
and 1,562 pending, with zero stale or orphaned records. This is file review and appropriate
validation accounting, not universal executable coverage.

The new complete review records cover AresRobot, SubsystemCapabilities, the existing five-test
capability file and all three new regression files. Existing Subsystem interface and architecture
documentation review credit is preserved. FtcBaseRobot remains partial: the inherited registered
shutdown boundary is now covered, while broader robot replacement/shared globals and concurrent
in-flight lifecycle ownership remain separate work. Adjacent schema, generated-code and hardware
implementations keep their own ledger scopes.

The platform owns loop exception handling and must neutralize after failed reads/writes.
Registration and foreground lifecycle calls have one owner; this pass does not claim arbitrary
concurrent registration/close safety. The shared registry owns its subsystem callbacks; separate
hardware registries and custom resource ownership remain platform responsibilities. Physical
neutralization, robot-loop latency, native Studio visibility and HIL were not observed.

This pass is progress toward the active goal. Pending files and partial scopes remain.
