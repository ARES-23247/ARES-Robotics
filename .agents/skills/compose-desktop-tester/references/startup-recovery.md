# ARES Robotics Studio startup and recovery

Use this decision tree when ARES Robotics Studio does not show a usable desktop window. The source
directory remains `ARES-Analytics/`. Preserve all unrelated worktree changes while diagnosing it.

## First establish which layer failed

| Evidence | Meaning | Next action |
|---|---|---|
| `:app:compileKotlin` fails | Source or dependency compilation failed before window creation. | Fix the reported compiler/dependency error. Do not change renderers or the instance lock. |
| A second launch prints `App is already running (failed to acquire app.lock). Exiting.` | Another process holds the OS lock. The new process intentionally exits without a window. | Identify the lock-owning ARES JVM and recover it as described below. |
| JVM/service logs continue, but strict capture reports no matching visible window | Compose/AWT presentation or rendering failed. | Check the Swing dispatcher and window/rendering invariants. |
| A real Compose HWND is logged, but the window is intermittent or stays behind the terminal | Windows denied foreground activation or the app demoted topmost status too early. | Preserve the bounded Compose-owned startup `alwaysOnTop` state and wait for the settled-state diagnostic. Never force activation with Win32 Z-order/focus calls. |
| `CRITICAL FAULT: Uncaught exception in thread 'AWT-EventQueue-0'` names a crash log | Application UI code crashed on the AWT event thread. The window can freeze or disappear while other threads keep the JVM and lock alive. | Read the named log and start with the first relevant application stack frame. Diagnose the UI defect before cleaning up the orphan. |
| A crash log reports `NoClassDefFoundError` / `ClassNotFoundException` for an application `*Kt` class whose source exists | Gradle's runtime class output is incomplete or stale. | Stop the ARES process and perform the no-cache clean compile below before relaunching. |
| A healthy window disappears exactly when another Gradle command starts | A concurrent task may have killed the PID or replaced mutable runtime bytecode. | Inspect the other command line. `clean`, compile, and test must not call `killExisting`; a normal `:app:run` must print its isolated temp classpath before window creation. |
| Strict capture returns a visible ARES window with rendered content | Desktop launch succeeded. | Treat offline NT4/Drive errors separately from window startup. The configured size is `1440 x 900 dp`; captured pixel dimensions vary with Windows display scaling. |

## Failure mode 1: orphaned single-instance lock owner

`DesktopInstanceLock` (acquired by the `Main.kt` composition root) opens `~/.ares-analytics/app.lock` and holds an operating-system file lock for the JVM lifetime. The file normally remains on disk after exit; that is harmless. The lock state, not file existence, determines whether another instance may start.

A JVM can outlive its window when an agent abandons a background Gradle session, interrupts shutdown, or leaves non-daemon services running. A later direct launch cannot acquire the lock, prints the already-running message, and exits without creating a window.

Diagnose before killing:

```powershell
Set-Location <monorepo-root>\ARES-Analytics
jps -lv | Select-String 'com\.ares\.analytics\.MainKt'
```

If the listed ARES process has no usable window, use the scoped repository task:

```powershell
.\gradlew.bat killExisting
```

Rules:

- Never delete `app.lock` as a repair; deleting the path does not safely revoke a live process's lock.
- Never kill every `java.exe`; Gradle daemons, IDEs, simulators, and robot tooling may also be Java processes.
- Report the verified ARES PID terminated by `killExisting`.
- `:app:run` depends on `killExisting`, but a packaged executable launched directly does not.

## Failure mode 2: missing Swing Main dispatcher

Compose Desktop state collection needs a Swing-backed `Dispatchers.Main`. `kotlinx-coroutines-core` does not install it.

Required dependency pair in `app/build.gradle.kts`:

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:<same-version>")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:<same-version>")
```

Keep and run the focused regression:

```powershell
.\gradlew.bat :app:test --tests com.ares.analytics.DesktopCoroutineDispatcherTest
```

Do not work around a missing Main dispatcher by moving UI state collection to an arbitrary background dispatcher.

## Failure mode 3: native window or renderer regression

Known-good policy:

- Let Compose/Skiko select the renderer.
- Do not add global `skiko.renderApi` or fallback JVM properties as a generic fix.
- Keep the floating, centered `1440 x 900 dp` Compose window and `visible = true`.
- Keep the `1100 x 700` AWT minimum size.
- Keep `toFront()`, `requestFocus()`, and the `Desktop window presented` diagnostic.
- Keep startup `alwaysOnTop` state owned by the Compose `Window`. Release it after the bounded interval and require `Desktop startup presentation settled: alwaysOnTop=false, focused=true, active=true, showing=true`.
- Identify the actual Compose/AWT peer with `Native.getWindowPointer(window)`, then require that exact handle to be present in `EnumWindows`, owned by the current PID, valid, and visible. Never accept an arbitrary same-process GLFW/helper HWND as proof of the desktop window.
- Treat native APIs as observation-only during normal startup. Do not use `AttachThreadInput`, `ShowWindow`, `SetWindowPos`, `BringWindowToTop`, `SetForegroundWindow`, or an AWT visibility off/on toggle. Compose owns visibility, peer creation, and topmost state; Windows may reject foreground requests without making the HWND invalid.
- Do not query HWND titles with `GetWindowTextLength` / `GetWindowText` from the AWT event thread; those calls can synchronously message AWT's toolkit window and deadlock presentation. Match by exact peer pointer internally; the external tester may match titles from its own process.
- Schedule initial presentation from `windowOpened` after the lifecycle callback returns. A generic startup `EventQueue.invokeLater` may run before `componentShown` / `windowOpened` and validate a transient peer. Keep the bounded delayed fallback for listeners attached after the opened event.

If a renderer-specific experiment is genuinely required, isolate it on a branch and collect before/after captures on the affected machine. A successful experiment must also pass a second launch after a clean shutdown.

For a read-only native-state probe, use `scripts/inspect_app_window.ps1` with either `-Handle <hwnd>` or `-OwnerProcessId <pid>`. It reports validity, bounds, styles, foreground ownership, DWM cloak state, monitor bounds, and virtual-desktop membership without restoring, focusing, moving, or closing the target. Add `-WatchSeconds <n>` to record HWND transitions. `EnumWindows` is scoped to the inspector's Windows desktop/window station, so a zero-window result from an isolated agent process is not evidence that an app on a different desktop destroyed its HWND; use the opt-in same-process capture below to resolve that boundary.

## Failure mode 4: AWT event-thread crash with a surviving JVM

An exception on `AWT-EventQueue-0` can stop or corrupt the desktop UI without stopping database, networking, executor, or other non-daemon threads. The resulting process may keep `app.lock`, so the next launch reports an already-running instance. In this sequence, the lock collision is secondary evidence; the AWT exception is the initiating failure.

When the console reports a critical AWT fault:

1. Open the exact `~/.ares-analytics/logs/crash-*.log` path named by the message, or inspect the newest file in that directory.
2. Find the first stack frame in ARES application code and investigate the state/input that reached it. Do not stop at framework frames or the later lock message.
3. Try the tester's native `-CloseWindow` action. If the damaged AWT thread cannot process `WM_CLOSE`, report the failed graceful shutdown and run `.\gradlew.bat killExisting` from `ARES-Analytics`.
4. After the fix, require two clean launch, strict-capture, native-close cycles with no remaining ARES JVM.

Do not automate shutdown with `SendKeys` Alt+F4. Synthetic key input can be delivered to the currently focused Compose text field and trigger application key-handling code instead of closing the native window. The tester posts `WM_CLOSE` directly to the verified ARES HWND.

## Failure mode 5: incomplete runtime class output

If a crash log contains `NoClassDefFoundError` or `ClassNotFoundException` for an ARES application class such as `SuperstructureStudioScreenKt`, first confirm that the corresponding `.kt` source still exists. This can occur when the runtime starts from an incomplete incremental output set after large source changes.

An ordinary `:app:compileKotlin` result may say `FROM-CACHE` while restoring the same bad output. Recover with a scoped, no-cache rebuild:

```powershell
Set-Location <monorepo-root>\ARES-Analytics
.\gradlew.bat killExisting
.\gradlew.bat :app:clean :app:compileKotlin --no-build-cache --rerun-tasks
```

Confirm the formerly missing class exists under `app/build/classes/kotlin/main/`, then perform the complete two-cycle launch verification. Do not delete source files, global Gradle caches, or unrelated repositories as part of this recovery.

The repository also disables Kotlin incremental compilation for Analytics. Do not remove `kotlin.incremental=false`: complete module output is intentional because Compose loads many screen and gesture classes lazily.

## Failure mode 6: concurrent build interference

Two separate mechanisms previously made other agents' builds look like random window failures:

1. Every subproject `clean` depended on `killExisting`, so `:app:clean` forcibly terminated a healthy visible ARES process.
2. Compose's development `run` task used mutable `app/build/classes` and `shared/build/classes` paths. A later compile/clean could remove a class that the running JVM had not loaded yet, producing a delayed `NoClassDefFoundError` when a screen or gesture was first used.

Required build contracts:

- Only `run` may depend on `killExisting`; `clean`, compile, and test must not terminate ARES.
- Within a replacement `:app:run`, `killExisting` must run after `:app:jar`; if the new source does not compile, the existing healthy app stays open.
- `:app:run` must print `Isolated desktop runtime classpath at ...ares-analytics-run-*` and launch project classes, project-owned artifacts, and `compose.application.resources.dir` against that unique snapshot.
- The snapshot cleanup finalizer must run after normal or failed app exit. Never reuse a snapshot between launches.
- Builds performed while the app is open affect the next launch only. Do not expect hot reload from `:app:run`.
- Do not launch `MainKt` directly against project `build/classes` directories as a workaround.

To confirm a suspected external kill, inspect active command lines before changing UI code:

```powershell
jps -lv
Get-CimInstance Win32_Process | Where-Object CommandLine -Match 'gradlew|com\.ares\.analytics\.MainKt' |
    Select-Object ProcessId, ParentProcessId, CommandLine
```

Preserve other agents' edits. If their compile caught a half-written feature, wait for their coherent edit boundary and compile the combined tree; do not revert their files merely to make startup green.

Do not run two Gradle compilers against the same Analytics module simultaneously. Runtime isolation protects the open app, not competing writers to `app/build/classes`; concurrent compiler processes can fail with `Could not delete ...build\classes\kotlin\main`. Inspect wrapper command lines and wait for the active build to finish.

## Expected warnings that do not mean startup failed

When offline or signed out, these may appear after the window renders:

- NT4 `Connection refused` / timeout errors for the selected robot.
- `DriveDestinationAccessException` asking for Google sign-in.

They are service-state messages. Do not suppress them as a window fix, and do not report launch failure if a strict screenshot shows rendered ARES UI.

## Required verification sequence

```powershell
Set-Location <monorepo-root>\ARES-Analytics
git status --short --branch
.\gradlew.bat :app:compileKotlin
jps -lv | Select-String 'com\.ares\.analytics\.MainKt'
.\gradlew.bat :app:run
```

After `Isolated desktop runtime classpath`, require `Desktop window shown` and `Desktop window opened` before `Desktop window presented after windowOpened` (or the explicit startup fallback). The presentation log must report `showing=true, nativeVisible=true, hwnd=<value>`. Then require `Desktop startup presentation settled: alwaysOnTop=false, focused=true, active=true, showing=true`. Capture strictly and confirm the script reports that same HWND:

```powershell
& "<monorepo-root>\.agents\skills\compose-desktop-tester\scripts\capture_app.ps1" `
  -WindowTitle "ARES Robotics Studio" `
  -OutputFile "<monorepo-root>\ARES-Analytics\build\diagnostics\startup.png" `
  -NoActivate
```

Inspect the PNG. If the exact ARES HWND has a black client area on the first capture, keep the same process alive, check the console for an AWT/render error, wait one paint interval, and recapture that same HWND. A rendered recapture is delayed painting; a persistently black/blank client area is a startup failure. Then close gracefully by posting `WM_CLOSE` to the verified ARES HWND. If the console has reported an AWT critical fault, read its crash log before cleanup:

If the app and external tester run on different Windows desktops/window stations, the tester cannot enumerate the app's HWND even when both processes share a login session. Use the app's opt-in same-desktop verifier for that run:

```powershell
$env:ARES_ANALYTICS_STARTUP_CAPTURE = "<monorepo-root>\ARES-Analytics\build\diagnostics\startup.png"
$env:ARES_ANALYTICS_STARTUP_CAPTURE_CLOSE = "true"
.\gradlew.bat :app:run
Remove-Item Env:ARES_ANALYTICS_STARTUP_CAPTURE
Remove-Item Env:ARES_ANALYTICS_STARTUP_CAPTURE_CLOSE
```

The app waits for settled `alwaysOnTop=false`, uses `java.awt.Robot` to capture its own window rectangle, and posts `WM_CLOSE` to the exact verified HWND. Normal launches do not perform this capture or auto-close behavior. Inspect the image and confirm `cleanupDesktopRunSnapshot`, `BUILD SUCCESSFUL`, and no remaining `MainKt`.

```powershell
& "<monorepo-root>\.agents\skills\compose-desktop-tester\scripts\interact_app.ps1" `
  -WindowTitle "ARES Robotics Studio" `
  -CloseWindow

jps -lv | Select-String 'com\.ares\.analytics\.MainKt'
```

For startup-related edits, repeat the launch, strict capture, graceful close, and no-process check once. Do not claim success until both cycles pass.
