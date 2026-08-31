# Publishing ARESLib

ARESLib publishes the coordinated artifacts listed by `publishedProjectPaths` in the root build under the verified `org.aresfirst.ares` namespace. The monorepo's `release/ares-versions.properties` is the final-version source of truth. Kotlin package names remain `com.areslib.*` for source and binary compatibility.

The primary release channel is the immutable ARES GitHub Maven repository at `https://raw.githubusercontent.com/ARES-23247/ARES-Robotics/maven`. Maven Central is an optional secondary channel and must never block local development or the primary GitHub release.

## Candidate validation

Before assigning final coordinates:

1. Update and review the public `.api` baselines with `./gradlew apiDump` when an intentional public API change was made.
2. Choose one unique candidate such as `<next-version>-rc.<commit>` and run `./gradlew clean test apiCheck publishReleaseValidation -ParesVersion=<candidate> --no-parallel`.
3. Build FTC, FRC, Analytics, and the starter repositories with composite substitution disabled and both `-ParesVersion=<candidate>` and `-ParesRepository=<absolute build/release-repository URI>`.
4. Merge the implementation through a protected pull request only after its build and CodeQL checks pass.

`publishReleaseValidation` writes a complete unsigned local Maven repository to `build/release-repository`. Ordinary validation rejects final release coordinates, so a developer repository cannot impersonate an immutable release.

## Primary GitHub Maven release

1. Bump `aresVersion` in `release/ares-versions.properties` to a new semantic version. Never reuse a version that exists on either release channel.
2. Merge that version through a protected pull request.
3. From the protected commit, confirm the requested version is absent from the remote `maven` branch.
4. Dispatch the protected root packaging workflow with the exact Studio version in the release manifest.
5. The workflow stages `publishGitHubRepository -ParesVersion=<final>`, rejects changes to existing version bytes, and packages Studio against those staged final coordinates.
6. Only after every consumer, starter, CodeQL, and native-package gate passes does it append the Maven content and create the combined Studio/starter release.
7. Resolve the BOM and representative modules from the remote GitHub URL in a clean consumer build before declaring the release complete.

The `maven` branch is append-only release storage. Every version identifies one immutable byte sequence. Repository ordering or caches must never produce two different binaries with the same coordinate.

## Optional Maven Central staging

Central publication is separate from the primary release. The protected `maven-central` GitHub Environment supplies `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, and `SIGNING_PASSWORD`. Do not place these values in Gradle files, logs, or source control.

When Central quota and authorization are available, dispatch **Stage Maven Central Release** for the same already-released version, approve the protected environment, review the validated deployment in the Central Portal, and publish it. Never change the artifacts or reuse a coordinate merely because Central staging failed.

## Student consumption

Season projects declare the ARES GitHub Maven repository and import the BOM once:

```kotlin
repositories {
    maven("https://raw.githubusercontent.com/ARES-23247/ARES-Robotics/maven")
    mavenCentral()
}

dependencies {
    implementation(platform("org.aresfirst.ares:ares-bom:13.0.0"))
    implementation("org.aresfirst.ares:core")
    implementation("org.aresfirst.ares:ftc-hardware")
}
```

Desktop simulation selects exactly one native runtime for its host OS. Checked-in FTC/FRC build logic performs that selection automatically. Library developers may opt into sibling source development with `-ParesUseSiblingLib=true`; student builds do not need an ARESLib checkout.
