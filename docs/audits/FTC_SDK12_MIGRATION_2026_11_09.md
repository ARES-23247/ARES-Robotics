# FTC SDK 12 migration checkpoint

Date: 2026-11-09. Branch: `codex/ftc-sdk12-20261109`.
Baseline: `f29afd9df` (database cleanup and initial FTC compatibility findings).
All work and validation use the isolated audit worktree; the original dirty checkout is excluded.

## Result and compatibility boundary

The framework, FTC season project, generic FTC starter, and their bundled examples now target
official FTC SDK 12.0.0. The official controller source/manifest and matching Gradle wrapper were
updated in both FTC products. The toolchain is Gradle 9.1.0, AGP 8.13.2, Kotlin 2.4.10, and the
documented Google D8/R8 9.1.29 override. No robot deployment or public release was performed.

The SDK change and mock/localization API changes use a new major library identity:

| Input | Final value |
|---|---|
| ARES release pin | 20.0.0 |
| Validated local candidate | `20.0.0-rc.sdk12.20261109.3` |
| Library source tree | `f5084d63e87180b9895d9227ee37ae12e8f66f54` |
| Studio | 7.0.66 |
| FTC / FRC starter bundles | 20.0.0 / 20.0.1 |
| XRP starter bundle | 3.0.65 |
| Lightbot / BIOBUZZ bundles | 3.0.69 / 1.1.7 |

The first local candidate (`.1`) was superseded after reviewing the maintained FTC field's tag
rotation convention. Candidate `.2` exposed a generated homing-test clock defect during fresh
representative-project acceptance. All final consumer checks use `.3` from this worktree's isolated
`ARESLib-Kotlin/build/release-repository`. No candidate was published externally. Unrelated
FRC/XRP behavior was preserved; their new archive versions carry the new shared dependency manifest.
Existing release/archive bytes were not overwritten under their previous versions.

## Corrections

Production `ftc-hardware` now compiles against the real RobotCore, Hardware, FtcCommon, and Vision
12.0.0 AAR classes instead of desktop mocks. These SDK classes and Android compile stubs are
compile-only inputs. Android consumers provide the SDK; desktop execution provides mocks.
The new bytecode regression checks referenced fields/methods, JVM descriptors, static members,
interface/class shape, and inherited-member resolution against those mocks.

This exposed cache-wrapper Java-method overrides, the hardware manufacturer type, protected Lynx
message-number access, an incorrect VectorF constructor, OTOS return types, and other missing mock
members. Those paths were aligned without enabling experimental Photon/proxy modes. OTOS now
rejects initialization when the actual SDK reports failed scalar configuration or IMU calibration.

VisionPortal now handles SDK singleton and cluster subtypes directly. A cluster is a camera-relative
aiming observation; it never supplies an absolute field pose. A cluster frame latches its source
into targeting-only operation, including mixed frames. Absolute localization requires explicit
reviewed stationary IDs and measured robot-to-camera mounting; defaults publish no absolute pose.
Missing/future/stale captures and non-finite/implausible observations are discarded, and successful
polls without a new frame clear observations while retaining connectivity.

Distance units come from each detection; output angle units are explicit. Capture time is converted
once into RobotClock time. Unavailable ambiguity is marked unavailable. Fixed workspaces cap a frame
at 32 observations without aliasing. SDK cluster member counts are conservatively bounded at one;
no covariance or multi-tag accuracy is inferred from the SDK's percentage alone.

The maintained DECODE map stores FTC optical tag rotations. `FtcTagPoseConvention.FTC_OPTICAL`
preserves those values, including their nonzero roll, and converts once inside pose reconstruction.
`WPILIB_NWU` supports explicitly reviewed outward/left/up tag rotations. Both the SDK library
factory and adapter accept the convention. The factory also emits inch-based metadata, matching
the real SDK robot-pose solver. Field documents and their verified positions were not rewritten.
Camera/body axes, mounting, tilted transforms, CCW heading, and target-space yaw have regressions.
See the [runtime migration guide](../../ARESLib-Kotlin/docs/ftc-sdk12.md).

## Observed verification

- Full library matrix: **3,109 tests, zero failures/errors/skips**, plus API checks and size ratchet.
  The final tree was published once under candidate `.3` for downstream validation.
- Bytecode compatibility checks inspect hundreds of real SDK member instructions. The evidence
  summary records the final count from JUnit output.
- Adapter allocation probes observed **0 bytes over 20,000 polls** for each tested stationary and
  cluster fixture after 30,000 warmup polls. This measures adapter code with stable fixture frames;
  it does not measure SDK camera processing, controller timing, or physical vision accuracy.
- FTC and FTC starter: generation, project verification, unit tests, simulator tests, and Android
  debug APK assembly passed against `.3`. FRC and its starter passed generation, verification, and
  tests against the same candidate.
- Two additional FTC consumer tests use actual SDK 12 detection/metadata objects and processor
  signatures: a stationary singleton reaches normal Redux/EKF fusion, while a mixed cluster frame
  publishes only targeting state and cannot initialize localization.

- Studio validation covers **2,789 app tests: 2,785 passed and four explicit opt-in skips**; shared 37 and gateway 18 tests passed. The full `.3` run had one failing representative test; its two FRC child failures were diagnosed and corrected, then both representative and official-template acceptance passed in a focused recovery run. All other full-suite results were retained, not rerun or relabeled. Fresh bundled FTC/FRC/XRP projects passed generation, build/unit/simulation checks and applicable Android/headless acceptance. Representative FTC and FRC descriptor-generated robots also passed with candidate `.3`. The four skips cover separate generic-template, native-chooser, performance-baseline, and hardware-dashboard opt-ins; their exact names are in the evidence summary.

- Two isolated Studio launch/capture/graceful-close cycles passed against `.3`. Exact owned window captures were inspected. Both settled normally, disposed services, exited their PIDs, released loopback ports, and removed runtime snapshots. No AWT crash files, shutdown watchdog halt, critical fault, or closed-database shutdown error was observed. These are rendered startup/cleanup checks; fresh-project behavior is established by the service/build acceptance above.

- Source policy, tracked guidance/link checks, six guidance unit tests, seven starter-export unit tests, and both changed canonical skills' validators passed. The maintainability ledger contains 1,116 production Kotlin files with zero size violations; limits were unchanged.

The first real-SDK production compile failed at 11 API mismatches. The initial mock-linkage check
found 37 mismatches; the final check passes. Earlier failures remain in local evidence, including
the pre-fix D8 metadata diagnostics. Although its first APK build returned success, AGP's bundled
R8 8.13.19 reported unsupported Kotlin metadata. Google's compatibility table requires R8 9.1.29
for Kotlin 2.4. The supported settings-classloader override loads that exact compiler, and final
APK builds contain no metadata-rewrite diagnostics. The artifact was obtained from Google's
documented public R8 repository after the same coordinate was unavailable in Google Maven.

Archive generation uses deterministic ordering/timestamps and canonical text. Source and artifact
versions, workflow URLs/hashes, bundled resources, and catalog preflight were aligned. A later
Lightbot version includes the real-SDK integration fixtures; the other four final archives remained
byte-identical during that addition. Old archive fingerprints are retained in the evidence and Git.

Running the previously opt-in representative-project acceptance exposed stale source-copy fixture
assumptions: the standalone shared build policy/runtime were missing and mechanical outputs were
asserted under editable source paths. The fixture now installs the actual standalone inputs and
checks normal generation under `build/generated/ares`, including absence of accidental editable
mechanical source. Per-phase forced dependency refreshes were removed for immutable candidates.
A missing cached public Kotlin plugin POM was restored through Gradle before retrying; no assertions
or dependency checks were bypassed.

The resulting generated tests caught a real generator defect: the homing dwell test sampled IO on
the system clock, switched to a mock clock, and reused its stale timestamp. Generated tests now
select the mock clock before refreshing IO and carry each fresh sample's timestamp into state.
Before-dwell and at-dwell assertions remain, and production homing/freshness/output gates are
unchanged. Deterministic artifact digests were updated for the corrected test output.

The next representative run confirmed the generated homing fix on both platforms, then exposed
two FRC starter tuning tests that assumed the first generated declaration had default `2.0`.
Adding subsystem declarations changed the ordering. Those fixtures now select the intended
acceleration-limit declaration by stable ID; consumer acknowledgement, current-value preservation,
duplicate-owner rejection, and fault-latch assertions remain intact. FRC starter 20.0.1 contains
the corrected tests; the other four final archives stayed byte-identical.

## Evidence, guidance, and limits

Local evidence is retained under `build/sdk12-migration/`: SDK source/API probes, baseline/final
Gradle logs, JUnit results, loaded R8 identity, APKs, deterministic archives, fresh projects, and
native captures/cleanup records. The [tracked evidence summary](FTC_SDK12_MIGRATION_2026_11_09_EVIDENCE.json)
binds final source/artifact hashes, commands, counts, skips, and limitations. The
[file review ledger](file-reviews.json) records this delta, with partial review for large/upstream
files rather than claiming repository-wide or full-line coverage.

The canonical build skill now covers SDK source/toolchain updates, real binary compatibility,
tag-frame selection, moving-target separation, fresh-project consumption, and distinguishing
controller tests from desktop evidence. The workspace toolchain table was updated.

No physical Control Hub, Driver Station pairing, camera image processing, calibration accuracy,
motor/servo actuation, or on-controller performance was tested. Those commissioning steps remain
required before robot use. No protected-main merge, attested public release, or installation into
the original dirty checkout is claimed. The previous [compatibility findings](INITIALIZATION_CLEANUP_FTC_COMPATIBILITY_2026_11_09.md)
describe the old baseline; this checkpoint supersedes their open software migration status.

Primary references: [FIRST SDK 12 release](https://github.com/FIRST-Tech-Challenge/FtcRobotController/releases/tag/v12.0),
[FIRST cluster semantics](https://ftc-docs.firstinspires.org/apriltag-clusters),
[Android Kotlin/D8/R8 compatibility](https://developer.android.com/build/kotlin-support), and
[Google's R8 replacement instructions](https://r8.googlesource.com/r8/+/refs/heads/main/README.md#replacing-r8-in-android-gradle-plugin).
