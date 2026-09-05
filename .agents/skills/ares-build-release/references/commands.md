# Commands and coordinates

## Published coordinates

Use BOM `org.aresfirst.ares:ares-bom:<aresVersion>` and unversioned modules constrained by it, including `core`, `codegen`, `ftc-hardware`, `ftc-mocks`, `frc-hardware`, `simulator`, and the platform simulator runtimes. Read the exact version from the monorepo's `release/ares-versions.properties`; never copy a version snapshot from this reference.

Normal consumers resolve these coordinates from Maven Central and the monorepo's GitHub-hosted ARES Maven branch at `https://raw.githubusercontent.com/ARES-23247/ARES-Robotics/maven`. Do not add old group IDs, hard-coded fallback versions, or ambient `mavenLocal()` artifacts to make resolution appear successful.

## Local shared-source development

```powershell
cd ARES-Analytics
.\gradlew.bat :app:run "-ParesUseSiblingLib=true"
```

Apply the same property to consumer tests when the sibling ARESLib checkout should substitute published modules.

## Isolated release validation

```powershell
cd ARESLib-Kotlin
.\gradlew.bat test apiCheck publishReleaseValidation --no-parallel "-ParesVersion=<candidate>-rc.<commit>"

cd ..\ARES-Analytics
.\gradlew.bat :app:test `
  "-ParesVersion=<candidate>-rc.<commit>" `
  "-ParesRepository=file:///C:/absolute/path/ARESLib-Kotlin/build/release-repository"
```

Use one candidate version throughout the matrix. The validation publisher rejects a final release version. Use an absolute file URI on Windows; relative repository paths may resolve from a subproject unexpectedly.

## Typical consumer verification

- FTC: generated-project verification as applicable, `:TeamCode:testDebugUnitTest`, `:simulator:test`, and `:TeamCode:assembleDebug`.
- FRC: generated-project verification as applicable and `test`; run simulation when affected.
- Analytics: focused UI/service tests, then `:shared:test :gateway:test :app:test`.
