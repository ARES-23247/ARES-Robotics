# Publishing ARESLib to Maven Central

ARESLib publishes ten coordinated artifacts under the verified `org.aresfirst.ares` namespace. `aresVersion` in `gradle.properties` is the local source of truth; the protected release workflow overrides it with its reviewed input. Kotlin package names remain `com.areslib.*` for source and binary compatibility.

## Release gate

Before staging a release:

1. Update and review the public `.api` baselines with `./gradlew apiDump` when an intentional public API change was made.
2. Run `./gradlew clean test apiCheck publishReleaseValidation --no-parallel`.
3. Build FTC, FRC, and Analytics with composite substitution disabled and `-ParesRepository=<ARESLib-Kotlin>/build/release-repository`.
4. Confirm the release version is semantic and does not end in `-SNAPSHOT`.

`publishReleaseValidation` writes a complete unsigned local Maven repository to `build/release-repository`. In the protected workflow, signing is enabled, so this step also proves that the in-memory key can sign every required file before any upload occurs.

## Protected GitHub environment

The `maven-central` GitHub Environment supplies exactly these secrets:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_KEY` (the complete ASCII-armored private key)
- `SIGNING_PASSWORD`

Do not add these values to repository secrets, Gradle files, logs, or local source control. Configure required reviewers on the environment so an accidental workflow dispatch cannot upload a deployment.

## Stage and publish

1. Open **Actions → Stage Maven Central Release → Run workflow**.
2. Enter the exact semantic version, such as `7.0.0`.
3. Approve the protected `maven-central` environment deployment.
4. Wait for tests, API checks, signed local publication, and `stageMavenCentral` to succeed.
5. Open Maven Central Portal **Deployments**, wait for the status to become **Validated**, review the deployment, and click **Publish**.

The workflow deliberately stages rather than automatically publishing. Maven Central releases are immutable, so the human review is the final safety gate. If validation fails, fix the source/configuration and stage a new deployment; never reuse a version that Central has already published.

## Student consumption

Season projects import the BOM once and omit versions from individual modules:

```kotlin
dependencies {
    implementation(platform("org.aresfirst.ares:ares-bom:7.0.0"))
    implementation("org.aresfirst.ares:core")
    implementation("org.aresfirst.ares:ftc-hardware")
}
```

Desktop simulation also selects exactly one native runtime for its host OS. The checked-in FTC/FRC build logic performs that selection automatically. Library developers may opt into the sibling source build with `-ParesUseSiblingLib=true`; student builds do not need the ARESLib checkout.
