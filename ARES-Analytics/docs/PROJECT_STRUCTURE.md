# Clean zero-code robot project structure

ARES robot projects keep editable intent, generated mechanics, local build evidence, and physical
evidence in separate places. This boundary lets a student build a robot entirely in Studio without
turning generated Kotlin into a second source of truth.

## Canonical, version-controlled inputs

| Location | Owner | Purpose |
| --- | --- | --- |
| `.ares/project.json` | Robot Studio | The single project, team, season, robot, display-name, league, coordinate, footprint, and runtime-options identity document. |
| `.ares/drivetrains/*.aresdrivetrain` | Drivebase Builder | Hardware names, geometry, localization, control modes, limits, and safety policy. |
| `.ares/subsystems/*.aressubsystem` | Subsystem Builder | Mechanism state, hardware, controls, safety, simulation, telemetry, and generated verification intent. |
| `.ares/superstructures/*.aressuperstructure` | Superstructure Studio | Cross-mechanism presets, typed targets, guarded transitions, interlocks, and fallback policy. |
| `.ares/controls/*.arescontrols` | Controls editor | Controller bindings to catalogued drive, routine, and subsystem actions. |
| `.ares/routines/*.aresroutine` plus catalogs | Autonomous editor | Deterministic routine graphs, selectable autonomous entries, and named capabilities. |
| `.ares/tuning/*.arestuning` | Tuning tools | Typed, bounded runtime parameters and reviewed values. |

New projects never create `.ares-robot.json`. Studio can migrate a legacy project through a
reviewed diff, preserves both original files under `.ares/recovery/identity/`, then retires the
duplicate identity file.

The desktop `workspaces.json` file keeps a display cache so Studio can list projects before opening
them, but it is not another identity authority. On load and save, Studio refreshes that cache from
`.ares/project.json`. Profile settings make canonical identity read-only and direct identity edits
to Robot Studio.

## Source ownership

The subsystem descriptor declares one of three ownership modes:

- `DECLARATIVE_GENERATED`: Studio owns the complete mechanism definition. Runtime adapters,
  registration, and tests are regenerated below Gradle `build/generated`; students edit only the
  form and canonical document.
- `GENERATED_STARTER`: Studio may create a documented teaching starter once. A later replacement
  requires a structured diff and exact confirmation token. The resulting source is an explicit
  customization point.
- `HAND_AUTHORED`: an advanced team owns the source. Studio registers and validates it but never
  rewrites it.

Source headers make the boundary visible: `USER-OWNED`, `GENERATED STARTER`, and
`GENERATED - DO NOT EDIT`. A package name or filename never overrides the declared ownership.

## Disposable generated output

FTC and FRC builds regenerate the project bridge, subsystem registry, typed drivebase plumbing,
superstructure adapters, and generated contract tests below their module's `build/generated/ares/`
tree. These files:

- derive deterministically from canonical `.ares` documents and the pinned ARES generator;
- are never committed or edited;
- are removed safely by Gradle `clean`; and
- are compiled and tested during **Verify & build**.

Platform adapters that express real FTC/FRC lifecycle or vendor behavior remain explicit source.
ARES does not copy library implementations into a robot project.

## Evidence and local state

Evidence is not canonical robot configuration:

- `.ares/evidence/hardware/configuration/` contains append-only, inventory-hash-bound human review
  records.
- `.ares/evidence/hardware/physical/` contains separate append-only supervised physical-validation
  records. A build or simulation cannot create one.
- `.ares/local/verification/<run-id>/report.json` contains one normalized local build/test report
  with the canonical content hash, dependency and generator versions, Git revision, exact command,
  timestamps, and exit result. `.ares/local/` is ignored by Git.
- `.ares/history/` and `.ares/recovery/` preserve reviewed replacements and migrations.

Changing a drivetrain or subsystem invalidates matching evidence by content hash; ARES retains the
old record as history and does not silently relabel it as current.

## Dependency boundary

Normal projects resolve one pinned immutable ARES version from the GitHub-hosted ARES Maven
repository and Maven Central. Local development must be explicit:

- `-ParesUseSiblingLib=true` selects the sibling source composite.
- `-ParesRepository=file:///.../build/release-repository` plus the matching candidate version
  selects an isolated validation repository.

Ambient `mavenLocal()` resolution is intentionally unsupported. It can return different bytes for
the same version depending on workstation state; use the sibling composite or isolated candidate
repository so dependency provenance remains explicit.

## What to commit

Commit canonical `.ares` documents, user-owned/approved starter source, platform adapters, and
independent platform tests. Do not commit Gradle build output, run-scoped local verification,
credentials, caches, or generated runtime plumbing.

Next: [Robot Studio](learn/ROBOT_STUDIO.md), [Subsystem Builder](SUBSYSTEM_BUILDER.md), and
[Hardware Setup](start/HARDWARE_SETUP.md).
