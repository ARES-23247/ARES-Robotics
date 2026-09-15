# Graceful shutdown

Close through the window first so service disposal and shutdown watchdog behavior are exercised. The close action posts native `WM_CLOSE` to the verified ARES HWND and waits up to 25 seconds for the process to exit. Do not use `SendKeys` to simulate Alt+F4: a focused Compose field can receive that synthetic key input instead of a native window-close event.

   ```powershell
   & "<monorepo-root>\.agents\skills\compose-desktop-tester\scripts\interact_app.ps1" -WindowTitle "ARES Robotics Studio" -CloseWindow
   ```

   If graceful close fails, report it and clean up only the verified process owned by this task. Use `killExisting` only after confirming every matching ARES JVM is owned by this task; otherwise terminate only the verified owned PID. Confirm that PID exited, preserving other tasks' instances.
