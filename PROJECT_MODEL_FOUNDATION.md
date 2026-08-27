# ARES Project Model Foundation

Status: implemented and validated on `codex/project-model-foundation`; not merged or released.

## Milestone outcome

Phase 1 establishes a physical dependency boundary between canonical robot-project documents, whole-project derivation, runtime/code generation, and the desktop authoring application. It intentionally preserves FTC/FRC runtime separation, FTC/FRC simulator separation, generated behavior, and every existing `.ares` document format.

```text
project-schema  -> canonical documents, codecs, stable typed IDs, platform targets
       |
       v
      core      -> runtime behavior and hardware-neutral robot state
       |
       v
project-model   -> raw project snapshot, effective model, cross-document validation
       |
       +-------> codegen / verifyAresProject
       |
       +-------> ARES Robotics Studio
```

The boundary is implemented as two publishable ARESLib modules:

- `project-schema`: descriptor formats, codecs, document-local validation, stable project/document/action IDs, and explicit FTC/FRC controller and simulator targets.
- `project-model`: the complete raw `RobotProjectSnapshot`, derived `EffectiveRobotProject`, and the single `RobotProjectAssembler` used by generation and Studio.

`core` now consumes `project-schema`; `codegen` consumes `project-model`. The ARES BOM publishes both modules under the existing immutable `org.aresfirst.ares` coordinates.

## Canonical versus effective state

`RobotProjectSnapshot` contains every canonical input needed to reason about a project: metadata, capability and autonomous catalogs, routines, control schemes, controller profiles, subsystems, superstructures, drivetrains, field configuration, tuning declarations/profiles, and loader failures.

`RobotProjectAssembler` derives one effective project and reports structured issues for:

- missing or invalid metadata/catalogs;
- duplicate stable IDs;
- FTC/FRC platform mismatches;
- multiple active drivetrains;
- unknown actions, routines, controller profiles, controls, and drivetrain axes;
- routine, controls, subsystem, drivetrain, superstructure, field, and tuning validation;
- capability ownership collisions;
- inconsistent tuning scopes.

The canonical project ID and the current tuning runtime scope are explicitly distinct. Existing tuning documents use a legacy `projectUid` such as `team23247.ftc.season2026.gobilda`; the canonical metadata ID is `team23247-gobilda`. The effective model exposes the former as `tuningScopeUid` and requires all tuning documents to agree without rewriting saved projects.

`ProjectSchemaVersions` makes every schema-owned document's current version explicit and fails closed for undeclared versions. Metadata schemas 1 and 2 are the only declared migration inputs because project-schema owns their deterministic migration to schema 3 and its golden legacy/current tests; no other codec is allowed to imply a migration it cannot perform.

## Consumers migrated

- `AresProjectCodegenCli` assembles and validates the shared effective model before generation.
- ARES Robotics Studio loads all canonical documents into the same raw snapshot and consumes the shared effective model.
- Studio no longer performs an independent capability-catalog load in Path Planner.
- Existing Studio adapters remain temporarily as compatibility views over the effective model; architecture tests prevent new direct catalog decoding outside the project/template boundary.
- Lightbot FTC and the representative FRC/starter projects require no descriptor or runtime changes.

No generated output changed. No user-owned source is overwritten, no runtime control behavior was altered, and no FTC/FRC implementation or simulator was merged.

## Verification evidence

Isolated candidate:

- Version: `10.0.0-rc.project-model.2`
- Repository: `ARESLib-Kotlin/build/release-repository`
- Provenance: explicit `-ParesVersion` and absolute `-ParesRepository` supplied to every consumer; no `mavenLocal()` dependency.

Passed checks:

- ARESLib full `test`, `apiCheck`, and `publishReleaseValidation` matrix.
- ARES-FTC Lightbot generation, `verifyAresProject`, TeamCode unit tests, simulator tests, and debug APK assembly.
- ARES-FRC generation, `verifyAresProject`, and full tests.
- ARES-FTC-Starter generation, verification, tests, and debug APK assembly.
- ARES-FRC-Starter generation, verification, and tests.
- ARES Robotics Studio shared, gateway, and app test suites.
- Studio architecture tests proving project screens consume the shared project boundary.
- Packaged-project validation proving Lightbot resolves to the typed FTC target and effective action catalog.
- Existing cross-screen action parity tests for Controller Bindings and Autonomous authoring.
- A second generation pass compared every generated file hash: Lightbot FTC (27 files), ARES-FRC (10), FTC Starter (11), and FRC Starter (10) were unchanged.

Generated-tree hashes remained byte-for-byte identical to the pre-refactor baseline:

| Consumer | Files | SHA-256 tree hash |
|---|---:|---|
| Lightbot FTC | 27 | `4ff71f90f6eaf31ac4c371476f80130c2294ddb1801d34f7de5a32bea1d30e2c` |
| ARES-FRC | 10 | `32862080cdb257048f5c72e8f6e2beaf7dffeee1d9a1b67e0c55a2c3a70157a5` |
| FTC Starter | 11 | `2935e85a47f3bd85f775259370d15edad91bf68e5a94400963aafd19e8e0606b` |
| FRC Starter | 10 | `298bbc0670c50119441e54e954770f23ba28c8bb9bb2a514a80961cb61701efc` |

Visible desktop evidence is stored under `ARES-Analytics/build/diagnostics/` (build output, not source control). Studio launched from the exact isolated candidate, presented a native Compose HWND at both 1440 x 900 and maximized resolution, loaded Lightbot without a project-model validation error, remained responsive, and closed cleanly through `WM_CLOSE`. Windows denied foreground activation from the automation terminal, so pointer traversal was not claimed; cross-screen authoring behavior is covered by the app tests above.

The FTC consumer also exposed and verified an important compatibility constraint: the FTC runtime's older Gson does not provide `JsonParser.parseString`. Shared codecs use `Gson.fromJson(..., JsonObject::class.java)`, and the full FTC generated contract now passes.

## Intentionally deferred

- Systemcore controller targets until real FTC/FRC APIs and lifecycle constraints exist.
- Any universal FTC/FRC simulator.
- A monolithic robot-project file.
- Runtime behavior changes or broad saved-project migrations.
- Deleting Studio compatibility views before remaining screens are migrated to direct effective-model queries.
- Physical hardware validation.

## Rollback and next phase

All repositories are on `codex/project-model-foundation`. Only ARESLib and ARES Robotics Studio contain implementation changes; FTC, FRC, and both starter repositories were validation consumers and remain source-clean. Rollback is a branch switch to the recorded baseline commits.

Phase 2 should replace the remaining compatibility views with focused effective-model query APIs, make every Builder screen consume those queries, and add explicit migrators plus golden fixtures whenever a non-metadata document actually changes version—before introducing any new controller target.
