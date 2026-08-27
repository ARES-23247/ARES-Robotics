# Code-first and hybrid FTC projects

This repository is standalone. After ARES Robotics Studio creates it, Studio may be closed and the
project can be built, tested, simulated, and installed from Android Studio or a terminal. Android
Studio is the preferred FTC IDE; VS Code is supported for editing and the reviewed Gradle tasks.

The `authoringModel` in `.ares/project.json` has three explicit meanings:

- `GUI_OWNED`: `.ares` documents are authoritative and generated source/tests stay under Gradle's
  `build/generated/ares` directories.
- `CODE_FIRST`: Kotlin under `org.firstinspires.ftc.teamcode.extensions` is authoritative.
- `HYBRID`: Studio owns drivetrain and routines while registered Kotlin owns selected mechanisms.

Studio never infers behavior by scanning Kotlin. For every handwritten mechanism, choose
**Register existing Kotlin** and complete its `.aressubsystem` contract: source/module ownership,
runtime and IO classes, action keys, telemetry, typed tunables, safety and verification evidence,
and mock/simulator capability. Missing metadata is displayed as unavailable and fails closed.

The Gradle wrapper and pinned immutable ARES version are checked in. Useful commands are:

```powershell
.\gradlew.bat generateAresProject :TeamCode:verifyAresProject :TeamCode:testDebugUnitTest :simulator:test
.\gradlew.bat :TeamCode:runSim
.\gradlew.bat :TeamCode:assembleDebug
.\gradlew.bat :TeamCode:installDebug
```

Studio initializes local history for projects it creates. GitHub backup remains optional and can be
connected later; neither local development nor simulation requires a GitHub account.
