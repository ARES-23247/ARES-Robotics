# Launch and capture

Use the dependency mode chosen for this task. Commands use the monorepo root as a placeholder.

1. Inspect the current worktree before launching:

   ```powershell
   Set-Location <monorepo-root>\ARES-Analytics
   git status --short --branch
   ```

2. Choose one dependency mode before compiling. Pass the same dependency properties to compile and run, including any candidate version/repository overrides. For released dependencies:

   A normal `:app:run` invokes `killExisting`, which matches every ARES Analytics JVM visible to JPS, regardless of checkout, isolated home, or window health. Inspect all matching processes first and use it only when they belong to this task. If another task owns an ARES instance, preserve it; use `-PskipKill` with a dedicated `-ParesIsolatedDesktopHome=...` for a separate test instance, or wait for its owner. `-PskipKill` does not bypass the application instance lock.

   ```powershell
   .\gradlew.bat :app:compileKotlin
   .\gradlew.bat :app:run
   ```

   For intentional sibling-source validation, add `"-ParesUseSiblingLib=true"` to both commands. Apply the ownership check above to every launch example below.

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

   This path waits until Compose has actually released startup topmost state, captures the 1440Ã—900 window from its own AWT desktop, and posts `WM_CLOSE` to the exact Compose HWND. It is inactive unless the variables are explicitly set. Inspect the PNG and confirm that this task's owned app PID exited.

For interaction, use [interaction.md](interaction.md). Finish with [shutdown.md](shutdown.md).
