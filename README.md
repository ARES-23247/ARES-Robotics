# ARES Robotics

This is the authoritative source monorepo for the ARES multi-league robotics platform and
**ARES Robotics Studio**. It preserves the independent FTC, FRC, library, starter, simulator, and
desktop toolchains while giving them one reviewed source history and one release policy.

## Repository map

| Directory | Responsibility |
|---|---|
| `ARESLib-Kotlin/` | Shared project schema/model/compiler, Redux and control runtime, hardware contracts, code generation, and deterministic simulation foundations |
| `ARES-FTC/` | GUI-authored Lightbot reference robot, FTC season lifecycle, Control Hub adapters, and FTC desktop simulator product |
| `ARES-FRC/` | FRC season lifecycle, roboRIO/vendor adapters, and WPILib/HAL simulator product |
| `ARES-Analytics/` | ARES Robotics Studio, local analytics database, telemetry/replay, cloud-optional services, and gateway |
| `ARES-FTC-Starter/` | Canonical source for the generated public FTC starter mirror |
| `ARES-FRC-Starter/` | Canonical source for the generated public FRC starter mirror |
| `ARES-XRP-Starter/` | Python-native XRP starter, deterministic `.ares` compiler, simulator, and Pico W deployment tooling |
| `templates/` | Monorepo-owned mechanical runtime templates that must not be copied into editable robot source |
| `build-logic/`, `release/` | Shared dependency and immutable release identity policy |

The Gradle builds remain isolated because Android/FTC, GradleRIO/WPILib, Compose Desktop, and the
published library use different toolchains. A source monorepo is not a universal robot runtime:
FTC and FRC retain separate lifecycles, controller targets, device adapters, and simulators.

## First checkout

On Windows:

```powershell
.\setup.ps1
.\build.ps1 -Task Test
```

On macOS or Linux:

```bash
./setup.sh
```

Studio can then be launched from `ARES-Analytics` with its Gradle wrapper. See
[`AGENTS.md`](AGENTS.md) for the exact dependency, simulator, visible-window, and release-validation
contracts.

## Canonical project flow

```text
.ares documents
  -> project-schema
  -> RobotProjectSnapshot / EffectiveRobotProject
  -> typed project compiler IR
  -> generated main source + generated safety tests + verification manifest
  -> FTC or FRC league runtime
  -> controller/device adapter
  -> matching league simulator or physical hardware
```

ARES Robotics Studio uses one application-scoped `ProjectSession` and the same effective project
assembler for authoring, generation, verification, simulation, and deployment authorization.
Generated mechanical source belongs in Gradle generated-source directories. User-owned extension
points are explicit and are never silently overwritten.

## Dependency and release policy

`release/ares-versions.properties` is the canonical dependency and product-version identity, while
`release/ares-source-tree.txt` binds that ARES version to the exact `ARESLib-Kotlin` Git tree. A
packaging retry reuses an already-published version byte-for-byte; changing that tree requires a new
ARES version instead of rebuilding different artifacts under an existing coordinate.
`release/starter-artifacts.properties` records the integrity hashes of Studio's immutable starter
archives without copying a self-referential archive hash into standalone projects. Ordinary
consumers use immutable `org.aresfirst.ares` artifacts. Cross-project changes use one explicit
prerelease version and one isolated validation repository; ambient `mavenLocal()` resolution is
forbidden.

Public FTC, FRC, and XRP starter repositories are release mirrors. Their bytes are exported with
`scripts/export-starter-mirrors.ps1`, and reproducible release archives are built with
`scripts/build-starter-archives.ps1`; manual drift or checksum mismatch is rejected by CI.

Protected pull requests run:

- ARESLib tests, API checks, and isolated candidate publication
- FTC, FRC, both starter, simulator, and Studio/gateway tests against that exact candidate
- deterministic starter-mirror verification
- dashboard performance validation and CodeQL
- real Windows MSI and native macOS DMG packaging, including packaged-project loading

The same required checks run for merge-queue commits. They do not rerun after the reviewed tree is
merged to `main`; scheduled and manual workflows remain available for independent health checks.

The packaging run seals those exact outputs into an attested release candidate. After merge, the
protected promotion workflow accepts only a candidate whose complete Git tree equals `main`, whose
originating run and workflow are trusted, and whose file hashes and canonical versions still match.
It publishes the verified Maven, starter, MSI, and DMG bytes without rerunning compilation or tests.
See [`RELEASE_TRANSITION.md`](RELEASE_TRANSITION.md) for the fail-closed promotion sequence.

Simulation and compilation evidence never claim physical wiring or robot validation.

## Migration and rollback

Every former component repository history is retained as an ancestor of this repository. The
pre-migration commits and immutable release boundaries are recorded in
[`MONOREPO_MIGRATION_BASELINE.md`](MONOREPO_MIGRATION_BASELINE.md). Legacy component repositories
remain available as rollback and release-compatibility points until the generated-mirror and
monorepo release process has completed its protected transition period.

See [`RELEASE_TRANSITION.md`](RELEASE_TRANSITION.md) for the current dependency-ordered publication,
legacy-repository retirement, and non-destructive local-checkout migration policy.
