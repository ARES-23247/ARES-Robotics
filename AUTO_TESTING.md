# Routines, controls, and autonomous verification

Run one command from the workspace root before committing a routine-builder, controller-editor,
capability, generated-code, trajectory, or robot-runtime change:

```powershell
.\verify-autos.ps1
```

macOS and Linux use the equivalent command:

```bash
./verify-autos.sh
```

The default workflow is a focused cross-repository contract check. Every Gradle invocation is
serialized. Do not run another repository build beside this script: ARESLib publication and Gradle
composite outputs are shared, and concurrent consumers can otherwise observe partially replaced
artifacts.

1. ARESLib checks the routine/autonomous schemas, lifecycle manager, control-scheme/profile
   validation, digital/analog/chord safety, deterministic Kotlin generator, and project CLI.
2. ARESLib checks both FTC and FRC gamepad-to-`InputFrame` platform adapters.
3. The exact tested ARESLib snapshots are published to Maven Local.
4. Analytics checks canonical project repositories and history, legacy migration, all routine node
   editor models, visual controller editing, and action-catalog discovery.
5. FTC explicitly verifies that checked-in generated Kotlin matches `.ares`, then runs its robot
   asset/runtime contract.
6. FRC explicitly verifies generated Kotlin, then checks catalog/runtime agreement, selection,
   alliance transforms, field bounds, and generated execution.

Run the complete test suites instead of the focused contract with:

```powershell
.\verify-autos.ps1 -Full
```

```bash
./verify-autos.sh --full
```

`-Full`/`--full` replaces the focused filters with each repository's complete relevant test suite,
while retaining explicit FTC/FRC generated-source checks and serialized build order.

The canonical GUI-to-robot pipeline is enabled for both FTC and FRC. Each season repository keeps
its source documents at the repository root under `.ares/` and commits deterministic
`GeneratedAresProject.kt`. Both leagues use the same catalog, routine, controller, validation,
code-generation, input-runtime, and task-lifecycle contracts. Legacy `.aresauto` files are
migration inputs, not the new authoring format.

## What the script does not test

Desktop verification cannot prove that a specific Flydigi Vader 5 Pro firmware/Driver Station
combination exposes every vendor button, that FTC Android forwards a vendor-only input, or that
physical drivetrain signs and dimensions match configuration. Verify per-platform raw mappings on
the target Driver Station, then run autonomous on restrained hardware with a stop operator ready.

## Updating the focused suite

Use fully qualified test class names that exist in the current source tree. When replacing a schema,
editor, or runtime contract, update both scripts in the same change. Keep one Gradle invocation per
repository phase; never parallelize these scripts as a speed optimization.

## GitHub Actions

`.github/workflows/verify-autos.yml` runs the same focused workflow on Windows. The manual workflow
form accepts a branch, tag, or commit for each repository, which is useful while a coordinated
change is still spread across four feature branches. Public repositories work with the default
GitHub token; private repositories should define an organization/repository secret named
`ARES_REPOS_TOKEN` with read access to all four code repositories.
