# Compiler and library dependency upgrades

Date: 2026-11-10. Source baseline: `00f911441c6043f99def5cb2545398b4ae060fdf`.
Branch: `codex/compiler-library-upgrades-20261110`. The original dirty checkout and all other
worktrees are outside this checkpoint and are preserved. This is a bounded dependency/configuration
review, not a new review of every framework file or a hardware qualification.

## Integrated scope and findings

The original heads of PRs #89, #96, #99, #100, and #101 are preserved as merge parents. They cover
simulator `slf4j-simple` 2.0.19, binary-compatibility-validator 0.18.2, and Kotlin JVM,
serialization, and Compose compiler upgrades proposed at 2.4.20. The integrated Kotlin cohort uses
the subsequent stable bug-fix patch **2.4.21**. Its upstream release notes include reflection and
Windows script-resource fixes; no corresponding ARES production leak is independently claimed.
Primary references: [Kotlin 2.4.21](https://github.com/JetBrains/kotlin/releases/tag/v2.4.21),
[Compose/compiler alignment](https://kotlinlang.org/docs/compose-compiler-migration-guide.html),
[validator 0.18.2](https://github.com/Kotlin/binary-compatibility-validator/releases/tag/0.18.2),
and [SLF4J release history](https://www.slf4j.org/news.html).

The PR-only snapshot `4cfcd2aef5885f6b06b3002445ebed4344cc2c12` left **18 of 23 compiler-related
pins on 2.4.10**, despite selecting 2.4.20 in some plugin declarations. This was confirmed by
extracting its seven configuration files and running the new alignment check. The library Android
plugin, forced standard-library modules, embedded code-generation test compiler, and standalone
FTC/FRC compilers now follow the same 2.4.21 cohort. The standard-library check includes JVM 7/8
and common variants, so a stale forced runtime cannot silently survive a plugin update.

`scripts/verify_kotlin_toolchain.py` checks the 23 active declarations across all six isolated
Gradle products and codegen tests. The monorepo policy invokes it before expensive builds. Nine
focused regression tests cover consumer/plugin/runtime drift, a stale embedded compiler, missing
or duplicate declarations, and commented-out pins. Fifteen monorepo-policy tests also pass,
including proof that a compiler-check failure stops policy validation. Six guidance tests pass.
Gradle 8.14.5's separately embedded Kotlin 2.0.21 is not the project compiler version.

## Reviewed JVM API addition

The initial candidate `20.0.3-rc.compiler.20261110.1` failed `:core:apiCheck`: the upgraded
compiler emitted public zero-argument constructors for `Pose2d` and `SwerveModuleState`, whose
defaulted primary constructors include the `Rotation2d` value class. Old and new `javap` member
sets showed exactly these two additions and **no removed public/protected members for those
types**. Production geometry/kinematics source was unchanged.

A client compiled with the actual Kotlin **2.4.10** compiler against the prior 20.0.2 candidate
ran unchanged with both the old and new library/standard-library pair. A separate Java caller
compiled and ran using the new public constructors. Explicit nonzero inputs and neutral defaults
were checked. Two permanent JVM-reflection tests exercise these exact public no-argument entry
points and verify zero position, heading, wheel speed, and angle. Only the two reviewed constructor
declarations were added to the API snapshot; the baseline was not regenerated wholesale.

The first disposable Java-probe build also exposed an unset source/target compatibility setting
in the probe itself. That fixture was corrected to Java 17 and its original diagnostic is retained;
it is not reported as a framework defect. The original API failure and its partial test XML are
preserved separately from subsequent passing results.

## Release identity and validation checkpoint

The revised library source tree is **`a74925ce35d58170eb870e68ec5d6efea8cdb9e7`**, bound to new
library version **20.0.3**. The successful isolated candidate is
**`20.0.3-rc.compiler.20261110.2`**. Its complete library test/API/publication command passed,
with **3,122 JUnit cases, zero failures/errors/skips**; unchanged-input build-cache results are
retained alongside executed tasks. Consumer commands use this same candidate and absolute file
repository, with automatic sibling substitution and ambient Maven Local disabled.

Studio reserves **7.0.71**. New immutable archives reserve FTC/FRC starters **20.0.4**, XRP
**3.0.68**, Lightbot **3.0.73**, and BioBuzz **1.1.11**. Their canonical manifests, bundled filenames,
workflow URLs, and SHA-256 pins advance together. Old-version bytes are not overwritten. The
library source/API changes after the first candidate do not change archive contents: archives
carry the final library dependency identity, not the library's source tree or validation suffix.
FTC SDK 12, WPILib, Compose runtime, JVM target, safety/enable gates, and hardware-review requirements
remain unchanged.

At this committed checkpoint, full Studio, FTC/FRC, starter, exported-project, and native-desktop
validation proceeds against the successful candidate. The protected integration PR and its
required checks are the final integration gate; library publication alone is not consumer or
window evidence. Subsequent results are recorded in the closeout evidence. No physical robot,
live cloud/project-backup request, or public artifact promotion is part of this task.

The [checkpoint evidence](COMPILER_LIBRARY_UPGRADES_2026_11_10_EVIDENCE.json) records the validated
identities, completed checks, pending consumer gates, and retained failure diagnostics.
The canonical build skill now covers compiler-cohort drift, embedded Gradle Kotlin, and evidence
required before accepting compiler-generated ABI additions. The file ledger records bounded
review and validation separately and preserves prior nested evidence. Raw commands, XML, bytecode,
ABI-probe output, original failures, and archive hashes are retained under the ignored
`build/compiler-upgrade-20261110/` directory.

## Completed consumer, desktop, and protected integration validation

The final source passed the following local checks against the same isolated RC2 candidate:

| Product/task | Cases | Failures/errors | Skips |
|---|---:|---:|---:|
| FTC season unit/simulator | 198 | 0 | 0 |
| FTC starter unit/simulator | 17 | 0 | 0 |
| FRC season | 306 | 0 | 0 |
| FRC starter | 206 | 0 | 0 |
| MicroPython runtime (host) | 132 | 0 | 0 |
| XRP starter generation/verification | 124 | 0 | 0 |
| Studio app | 2,807 | 0 | 6 |
| Studio shared/gateway | 55 | 0 | 0 |
| Studio dashboard smoke | 59 | 0 | 0 |
| Studio performance baseline | 1 | 0 | 0 |
| Studio complete consumer/recovery roundtrips | 4 | 0 | 0 |

Both FTC products also assembled their debug APKs. `studioReleaseVerification` passed its
coverage gate in 14m 26s. Ordinary `app:test` skips the three opt-in official/generic/representative
starter suites, native chooser, dedicated performance baseline, and physical-dashboard test.
The dedicated baseline task actually executed its case. Protected CI separately passed fresh
official FTC/FRC/XRP project creation, generation, verification, simulation and packaging;
native chooser interaction and physical telemetry were not requalified in this compiler pass.
The four local complete roundtrips exercised generic/BioBuzz consumers and recovery after real
code-generation cancellation/intermediate writes. Skips are not counted as passing cases.

Dashboard smoke persisted and restored all 12,000 frames with zero drops. On this local workload,
ingestion was 482,201 frames/s, query p95 11.52 ms, replay scrub p95 27.36 ms, and observed heap
growth 5.13 MiB. All configured limits and the checked-in performance baseline passed. These
are bounded desktop measurements, not robot-loop or long-soak performance claims.

Two same-process exact-window captures were inspected at 1424 × 861 client pixels. Both launches
used `-PskipKill`, a dedicated home, the same candidate/repository as compilation, and isolated
runtime snapshots. Presentation settled with startup topmost state released. Exact native
`WM_CLOSE` closed each window; all observed owned app/launcher JVMs exited, loopback test ports
49381/49382 closed, and both runtime snapshots were deleted. The second launch reused the
isolated database successfully. No crash log, shutdown-watchdog fault, or database-close error
was observed. Other tasks' processes were preserved. Capture hashes and cleanup identities are
in the evidence JSON. All five final archive bytes reproduced their pinned SHA-256 values.

[PR #112](https://github.com/ARES-23247/ARES-Robotics/pull/112) merged through protected main at
`4c82e6968e9787a615afba2c1a09bf34972b528a` after all **17 required checks passed**. The additional CodeQL
analysis/check results were also inspected. CI's source-policy job ran all 117 tooling tests.
The five original dependency heads remain ancestors of main and their PR states/branch cleanup
are recorded in the evidence. Unique feature branches, dirty worktrees, `main`, and `maven` were
preserved. No public artifact release or hardware validation was performed.
