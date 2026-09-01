---
name: compose-desktop-tester
description: Automated visual testing, screen capture, and UI interaction workflow for the ARES Robotics Studio Kotlin Compose Desktop application on Windows.
---

# Compose Desktop Visual Tester

Launch, capture, inspect, interact with, and cleanly close **ARES Robotics Studio** on Windows. The
source product directory remains `ARES-Analytics/` for repository continuity. A running JVM is not
sufficient evidence: this workflow requires a visible top-level window containing rendered app UI.

## Read the recovery guide when startup is involved

Read [references/startup-recovery.md](references/startup-recovery.md) before acting when:

- the app launches with no window, an intermittent/blank window, or an `app.lock` message;
- another agent may have left a background GUI process;
- changing `Main.kt`, `ServiceRegistry`, Compose/coroutines dependencies, or Skiko settings;
- validating a fix for any desktop startup or shutdown failure.

The guide distinguishes orphaned lock owners, a missing Swing Main dispatcher, native-window/rendering regressions, AWT event-thread crashes, incomplete runtime class output, and expected offline-service warnings. Do not treat them as one generic "Compose failed" problem.

## Core workflow

1. Preserve the current worktree and compile before launching:

   ```powershell
   Set-Location <monorepo-root>\ARES-Analytics
   git status --short --branch
   .\gradlew.bat :app:compileKotlin
   ```

2. Choose one dependency mode deliberately. Use ordinary `:app:run` for released dependencies. Add `"-ParesUseSiblingLib=true"` only while intentionally validating sibling ARESLib source.

   ```powershell
   .\gradlew.bat :app:run
   ```

3. Wait for `Desktop window presented`, then require an exact visible-window capture. The script exits nonzero when no matching ARES HWND exists; it no longer substitutes a full-desktop image.

   ```powershell
   & "<monorepo-root>\.agents\skills\compose-desktop-tester\scripts\capture_app.ps1" `
     -WindowTitle "ARES Robotics Studio" `
     -OutputFile "<monorepo-root>\ARES-Analytics\build\diagnostics\window.png" `
     -NoActivate
   ```

4. Inspect the saved image with the available image-viewing tool. Verify actual ARES content, window dimensions, layout, contrast, canvas rendering, and the state relevant to the task.

   Some agent GUI runners assign each tool process a different Windows desktop/window station. If the app logs an exact HWND and `Desktop startup presentation settled: alwaysOnTop=false, focused=true, active=true, showing=true` but an external capture process cannot enumerate it, use the opt-in same-process capture instead of claiming the window vanished:

   ```powershell
   $env:ARES_ANALYTICS_STARTUP_CAPTURE = "<monorepo-root>\ARES-Analytics\build\diagnostics\window.png"
   $env:ARES_ANALYTICS_STARTUP_CAPTURE_CLOSE = "true"
   .\gradlew.bat :app:run
   Remove-Item Env:ARES_ANALYTICS_STARTUP_CAPTURE
   Remove-Item Env:ARES_ANALYTICS_STARTUP_CAPTURE_CLOSE
   ```

   This path waits until Compose has actually released startup topmost state, captures the 1440×900 window from its own AWT desktop, and posts `WM_CLOSE` to the exact Compose HWND. It is inactive unless the variables are explicitly set. Inspect the PNG and still confirm no `MainKt` remains.

5. Interact only through a verified ARES window. Coordinates are relative to that window; the script fails instead of clicking the desktop when no window is found.

   ```powershell
   & "<monorepo-root>\.agents\skills\compose-desktop-tester\scripts\interact_app.ps1" -WindowTitle "ARES Robotics Studio" -ClickX 350 -ClickY 60
   & "<monorepo-root>\.agents\skills\compose-desktop-tester\scripts\interact_app.ps1" -WindowTitle "ARES Robotics Studio" -Text "MyStatePreset"
   & "<monorepo-root>\.agents\skills\compose-desktop-tester\scripts\interact_app.ps1" -WindowTitle "ARES Robotics Studio" -Key "ENTER"
   ```

   For deterministic product journeys, prefer the opt-in loopback test control. It sends input
   through the real AWT/Skia surface and works when Windows blocks cross-process input injection.
   Every command is an explicit test action; the server binds only to loopback and exists only
   when `ARES_ANALYTICS_TEST_CONTROL_PORT` is set before launch.

   ```powershell
   $env:ARES_ANALYTICS_TEST_CONTROL_PORT = "49321"
   $env:ARES_ANALYTICS_TEST_CAPTURE_DIR = "$PWD\build\diagnostics\visible-e2e"
   .\gradlew.bat :app:run "-ParesIsolatedDesktopHome=build/visible-e2e-home"

   & "<monorepo-root>\.agents\skills\compose-desktop-tester\scripts\send_test_control.ps1" -Port 49321 -Ping
   & "<monorepo-root>\.agents\skills\compose-desktop-tester\scripts\send_test_control.ps1" -Port 49321 -ClickX 350 -ClickY 60
   & "<monorepo-root>\.agents\skills\compose-desktop-tester\scripts\send_test_control.ps1" -Port 49321 -Text "Lightbot"
   & "<monorepo-root>\.agents\skills\compose-desktop-tester\scripts\send_test_control.ps1" -Port 49321 -Capture
   ```

   A native file or folder chooser is modal. Start the click that opens it in one PowerShell job,
   wait until the chooser is visible, then select an explicit existing path through a second
   connection. The concurrent server ensures the chooser selection can complete the first click.

   ```powershell
   $openChooser = Start-Job {
     & "<monorepo-root>\.agents\skills\compose-desktop-tester\scripts\send_test_control.ps1" `
       -Port 49321 -ClickX 350 -ClickY 60 -TimeoutSeconds 120
   }
   & "<monorepo-root>\.agents\skills\compose-desktop-tester\scripts\send_test_control.ps1" `
     -Port 49321 -ChoosePath "<absolute-existing-fixture-or-folder>"
   Receive-Job $openChooser -Wait -AutoRemoveJob
   ```

   Never point a visible E2E launch at a normal user data directory or select a sensitive fixture.
   Use a dedicated isolated home, an explicit test workspace, and the app's graceful close path.

6. Close through the window first so service disposal and shutdown watchdog behavior are exercised. The close action posts native `WM_CLOSE` to the verified ARES HWND and waits up to 25 seconds for the process to exit. Do not use `SendKeys` to simulate Alt+F4: a focused Compose field can receive that synthetic key input instead of a native window-close event.

   ```powershell
   & "<monorepo-root>\.agents\skills\compose-desktop-tester\scripts\interact_app.ps1" -WindowTitle "ARES Robotics Studio" -CloseWindow
   ```

   If graceful close fails, report it and then run `.\gradlew.bat killExisting` as cleanup. Confirm `jps -lv` no longer lists `com.ares.analytics.MainKt`.

## Evidence standard

- `BUILD SUCCESSFUL` proves a task completed, not that a window exists.
- `MainScreen` or service logs prove coroutines ran, not that an HWND is visible.
- `Desktop window presented` proves the peer existed at one instant; require the later settled-state diagnostic and rendered capture for startup work.
- A full-screen screenshot is not launch evidence.
- An `AWT-EventQueue-0` crash can leave service threads and the single-instance lock alive. Read the named crash log before treating the surviving JVM as the cause.
- NT4 connection failures and Google Drive sign-in errors do not invalidate a successfully rendered offline window.
- After startup-related changes, perform two launch → capture → graceful-close cycles. One successful launch can hide an orphan-process or one-launch-only regression.
