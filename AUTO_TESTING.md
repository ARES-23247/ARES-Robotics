# Autonomous verification

Run one command from the workspace root before committing an auto-builder, trajectory, action,
or autonomous-runtime change:

```powershell
.\verify-autos.ps1
```

macOS and Linux use the equivalent command:

```bash
./verify-autos.sh
```

The default workflow is a fast cross-repository contract check. It runs sequentially to prevent
the four Gradle builds from overwriting ARESLib outputs while another build reads them.

1. ARESLib round-trips a visual auto document, compiles its trajectory and named actions, and
   deterministically executes the resulting task graph.
2. ARESLib is published to Maven Local so every consumer sees the tested implementation.
3. Analytics discovers offline actions, enforces robot-aware field bounds, saves atomically,
   reloads, versions, and deploy-loads an `.aresauto` document.
4. FTC checks every deployed native auto against the checked-in action manifest and the commands
   registered by the season robot, then compiles each document with the robot-side compiler.
5. FRC checks every deployed native auto against its offline/runtime action catalogs, compiles each
   document for both alliances, verifies alliance pose seeding and field-footprint rejection, and
   executes a command timeline through the production task runner.

Run the complete test suites instead of the focused contract with:

```powershell
.\verify-autos.ps1 -Full
```

```bash
./verify-autos.sh --full
```

The native GUI-to-robot `.aresauto` pipeline is enabled for both FTC and FRC. Each league keeps its
season action catalog and deploy assets in its conventional project asset directory, while both use
the same shared schema, compiler, trajectory providers, named-command registry, and task executor.

## GitHub Actions

`.github/workflows/verify-autos.yml` runs the same focused workflow on Windows. The manual workflow
form accepts a branch, tag, or commit for each repository, which is useful while a coordinated
change is still spread across four feature branches. Public repositories work with the default
GitHub token; private repositories should define an organization/repository secret named
`ARES_REPOS_TOKEN` with read access to all four code repositories.
