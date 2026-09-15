# Owned-window interaction

Launch with the ownership and isolation checks in [launch-capture.md](launch-capture.md).
Choose the input method needed for this task; no need to exercise every method.

Interact only through a verified ARES window owned by this task. The title-based capture/interaction helpers do not accept a PID filter; when multiple matching windows exist, use this instance's dedicated loopback test-control port or same-process capture instead of choosing a window by title. Coordinates are relative to that window; the script fails instead of clicking the desktop when no window is found.

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

Finish with [shutdown.md](shutdown.md).
