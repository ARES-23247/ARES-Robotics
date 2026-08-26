# Publishing ARESLib

ARESLib publishes ten coordinated artifacts under the verified `org.aresfirst.ares` namespace. `aresVersion` in `gradle.properties` is the source of truth. Kotlin package names remain `com.areslib.*` for source and binary compatibility.

The primary release channel is the immutable ARES GitHub Maven repository at `https://raw.githubusercontent.com/ARES-23247/ARESLib-Kotlin/maven`. Maven Central is an optional secondary channel and must never block local development or the primary GitHub release.

## Candidate validation

Before assigning final coordinates:

1. Update and review the public `.api` baselines with `./gradlew apiDump` when an intentional public API change was made.
2. Choose one unique candidate such as `9.13.0-rc.<commit>` and run `./gradlew clean test apiCheck publishReleaseValidation -ParesVersion=<candidate> --no-parallel`.
3. Build FTC, FRC, Analytics, and the starter repositories with composite substitution disabled and both `-ParesVersion=<candidate>` and `-ParesRepository=<absolute build/release-repository URI>`.
4. Merge the implementation through a protected pull request only after its build and CodeQL checks pass.

`publishReleaseValidation` writes a complete unsigned local Maven repository to `build/release-repository`. Ordinary validation rejects final release coordinates, so a developer repository cannot impersonate an immutable release.

## Primary GitHub Maven release

1. Bump `aresVersion` to a new semantic version. Never reuse a version that exists on either release channel.
2. Merge that version through a protected pull request.
3. From the protected commit, confirm the requested version is absent from the remote `maven` branch.
4. Run `./gradlew clean test apiCheck publishGitHubRepository -ParesVersion=<final> --no-parallel`.
5. Add the generated `build/github-repository` content to the existing `maven` branch without deleting prior artifacts.
6. Commit and push the repository update, then resolve the BOM and representative modules from the remote GitHub URL in a clean consumer build.
7. Tag the protected source commit only after remote resolution succeeds.

The `maven` branch is append-only release storage. Every version identifies one immutable byte sequence. Repository ordering or caches must never produce two different binaries with the same coordinate.

## Optional Maven Central staging

Central publication is separate from the primary release. The protected `maven-central` GitHub Environment supplies `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, and `SIGNING_PASSWORD`. Do not place these values in Gradle files, logs, or source control.

When Central quota and authorization are available, dispatch **Stage Maven Central Release** for the same already-released version, approve the protected environment, review the validated deployment in the Central Portal, and publish it. Never change the artifacts or reuse a coordinate merely because Central staging failed.

## Student consumption

Season projects declare the ARES GitHub Maven repository and import the BOM once:

```kotlin
repositories {
    maven("https://raw.githubusercontent.com/ARES-23247/ARESLib-Kotlin/maven")
    mavenCentral()
}

dependencies {
    implementation(platform("org.aresfirst.ares:ares-bom:9.13.0"))
    implementation("org.aresfirst.ares:core")
    implementation("org.aresfirst.ares:ftc-hardware")
}
```

Desktop simulation selects exactly one native runtime for its host OS. Checked-in FTC/FRC build logic performs that selection automatically. Library developers may opt into sibling source development with `-ParesUseSiblingLib=true`; student builds do not need an ARESLib checkout.
