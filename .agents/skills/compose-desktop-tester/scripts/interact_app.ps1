param(
    [string]$WindowTitle = "ARES Analytics",
    [int]$ClickX = -1,
    [int]$ClickY = -1,
    [string]$Text = "",
    [string]$Key = "",
    [switch]$CloseWindow,
    [int]$CloseTimeoutSeconds = 25
)

Add-Type -AssemblyName System.Windows.Forms

$Signature = @"
[DllImport("user32.dll")]
public static extern bool SetForegroundWindow(IntPtr hWnd);
[DllImport("user32.dll")]
public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
[DllImport("user32.dll")]
public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, int dwExtraInfo);
[DllImport("user32.dll")]
public static extern bool SetCursorPos(int x, int y);
[DllImport("user32.dll", SetLastError = true)]
public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

public struct RECT {
    public int Left;
    public int Top;
    public int Right;
    public int Bottom;
}
"@
$User32 = Add-Type -MemberDefinition $Signature -Name "User32Interact" -Namespace "AresNative" -PassThru -ErrorAction SilentlyContinue

$proc = Get-Process | Where-Object { $_.MainWindowTitle -like "*$WindowTitle*" } | Select-Object -First 1
$interactionRequested =
    $ClickX -ge 0 -or
    $ClickY -ge 0 -or
    -not [string]::IsNullOrEmpty($Text) -or
    -not [string]::IsNullOrEmpty($Key) -or
    $CloseWindow

if ($interactionRequested -and -not $proc) {
    Write-Error "No visible ARES window matching '$WindowTitle' was found; no input was sent."
    exit 2
}

if ($proc) {
    [AresNative.User32Interact]::SetForegroundWindow($proc.MainWindowHandle) | Out-Null
    Start-Sleep -Milliseconds 200
}

if ($CloseWindow) {
    $closePosted = [AresNative.User32Interact]::PostMessage(
        $proc.MainWindowHandle,
        0x0010, # WM_CLOSE
        [IntPtr]::Zero,
        [IntPtr]::Zero
    )
    if (-not $closePosted) {
        Write-Error "Failed to post WM_CLOSE to ARES window PID $($proc.Id)."
        exit 4
    }
    Write-Output "Posted WM_CLOSE to ARES window PID $($proc.Id); waiting for graceful shutdown."

    $deadline = [DateTime]::UtcNow.AddSeconds($CloseTimeoutSeconds)
    while (-not $proc.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 250
        $proc.Refresh()
    }

    if (-not $proc.HasExited) {
        Write-Error "ARES window PID $($proc.Id) did not exit within $CloseTimeoutSeconds seconds. Run the scoped Gradle killExisting task as cleanup and report the shutdown failure."
        exit 3
    }

    Write-Output "ARES window PID $($proc.Id) exited after graceful close."
    exit 0
}

# Handle Click
if ($ClickX -ge 0 -and $ClickY -ge 0) {
    $rect = New-Object AresNative.RECT
    [AresNative.User32Interact]::GetWindowRect($proc.MainWindowHandle, [ref]$rect)
    $targetX = $rect.Left + $ClickX
    $targetY = $rect.Top + $ClickY

    [AresNative.User32Interact]::SetCursorPos($targetX, $targetY)
    Start-Sleep -Milliseconds 100
    # MOUSEEVENTF_LEFTDOWN = 0x0002, MOUSEEVENTF_LEFTUP = 0x0004
    [AresNative.User32Interact]::mouse_event(0x0002, 0, 0, 0, 0)
    Start-Sleep -Milliseconds 50
    [AresNative.User32Interact]::mouse_event(0x0004, 0, 0, 0, 0)
    Write-Output "Clicked at ($targetX, $targetY)"
}

# Handle Keys
if (-not [string]::IsNullOrEmpty($Key)) {
    [System.Windows.Forms.SendKeys]::SendWait("{$Key}")
    Write-Output "Sent key {$Key}"
}

# Handle Text
if (-not [string]::IsNullOrEmpty($Text)) {
    [System.Windows.Forms.SendKeys]::SendWait($Text)
    Write-Output "Sent text '$Text'"
}
