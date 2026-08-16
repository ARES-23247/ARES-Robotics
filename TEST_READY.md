# Test suite status

The test sources are checked in and runnable through the Gradle Wrapper. This file intentionally does not contain a fixed test count: the live test tasks and reports are authoritative as the suite evolves.

## Full verification

```powershell
.\gradlew.bat test
```

For a change to published ARESLib behavior, also run:

```powershell
.\gradlew.bat apiCheck publishReleaseValidation "-ParesVersion=8.0.0-rc.<commit>"
```

Then test every affected sibling repository against `build/release-repository`. See [TEST_INFRA.md](TEST_INFRA.md) for module commands, coverage areas, E2E scope, JNI notes, and port-owning tests.

A green historical run is not evidence for an untested working tree. Record the exact command and current result in the change or PR description rather than editing a permanent pass count into this file.
