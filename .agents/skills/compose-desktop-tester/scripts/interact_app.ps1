param(
    [string]$WindowTitle = "ARES Analytics",
    [int]$ClickX = -1,
    [int]$ClickY = -1,
    [string]$Text = "",
    [string]$Key = "",
    [string]$HoldKeys = "",
    [int]$HoldMilliseconds = 1000,
    [int]$WheelDelta = 0,
    [int]$ResizeWidth = 0,
    [int]$ResizeHeight = 0,
    [int]$MoveX = -1,
    [int]$MoveY = -1,
    [switch]$MaximizeWindow,
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
public static extern bool MoveWindow(IntPtr hWnd, int X, int Y, int nWidth, int nHeight, bool bRepaint);
[DllImport("user32.dll")]
public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
[DllImport("user32.dll", SetLastError = true)]
public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);
[DllImport("user32.dll")]
public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

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
    -not [string]::IsNullOrEmpty($HoldKeys) -or
    $WheelDelta -ne 0 -or
    $ResizeWidth -gt 0 -or
    $ResizeHeight -gt 0 -or
    $MoveX -ge 0 -or
    $MoveY -ge 0 -or
    $MaximizeWindow -or
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

if ($MaximizeWindow) {
    [AresNative.User32Interact]::ShowWindow($proc.MainWindowHandle, 3) | Out-Null # SW_MAXIMIZE
    Start-Sleep -Milliseconds 500
    Write-Output "Maximized ARES window PID $($proc.Id)"
}

if ($ResizeWidth -gt 0 -or $ResizeHeight -gt 0 -or $MoveX -ge 0 -or $MoveY -ge 0) {
    if (($ResizeWidth -gt 0) -ne ($ResizeHeight -gt 0)) {
        Write-Error "-ResizeWidth and -ResizeHeight must be supplied together."
        exit 6
    }
    [AresNative.User32Interact]::ShowWindow($proc.MainWindowHandle, 9) | Out-Null # SW_RESTORE
    Start-Sleep -Milliseconds 200
    $rect = New-Object 'AresNative.User32Interact+RECT'
    [AresNative.User32Interact]::GetWindowRect($proc.MainWindowHandle, [ref]$rect) | Out-Null
    $targetX = if ($MoveX -ge 0) { $MoveX } else { $rect.Left }
    $targetY = if ($MoveY -ge 0) { $MoveY } else { $rect.Top }
    $targetWidth = if ($ResizeWidth -gt 0) { $ResizeWidth } else { $rect.Right - $rect.Left }
    $targetHeight = if ($ResizeHeight -gt 0) { $ResizeHeight } else { $rect.Bottom - $rect.Top }
    if (-not [AresNative.User32Interact]::MoveWindow(
        $proc.MainWindowHandle,
        $targetX,
        $targetY,
        $targetWidth,
        $targetHeight,
        $true
    )) {
        Write-Error "Failed to resize ARES window PID $($proc.Id)."
        exit 7
    }
    Start-Sleep -Milliseconds 500
    Write-Output "Moved/resized ARES window PID $($proc.Id) to ($targetX,$targetY) $targetWidth x $targetHeight"
}

# Handle Click
if ($ClickX -ge 0 -and $ClickY -ge 0) {
    $rect = New-Object 'AresNative.User32Interact+RECT'
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

if ($WheelDelta -ne 0) {
    # MOUSEEVENTF_WHEEL = 0x0800. Positive values scroll up; negative values scroll down.
    $wheelData = [BitConverter]::ToUInt32([BitConverter]::GetBytes([int]$WheelDelta), 0)
    [AresNative.User32Interact]::mouse_event(0x0800, 0, 0, $wheelData, 0)
    Write-Output "Sent mouse wheel delta $WheelDelta"
    Start-Sleep -Milliseconds 300
}

# Handle Keys
if (-not [string]::IsNullOrEmpty($Key)) {
    [System.Windows.Forms.SendKeys]::SendWait("{$Key}")
    Write-Output "Sent key {$Key}"
}

# Hold a comma-separated key chord (for example SPACE,W) long enough to exercise controls that
# intentionally depend on simultaneous key state. Always release in reverse order, even if the
# wait is interrupted, so a failed UI test cannot leave a movement key latched.
if (-not [string]::IsNullOrEmpty($HoldKeys)) {
    $virtualKeys = @(
        $HoldKeys.Split(',') | ForEach-Object {
            $keyName = $_.Trim()
            if ([string]::IsNullOrEmpty($keyName)) { return }
            try {
                [byte][System.Enum]::Parse([System.Windows.Forms.Keys], $keyName, $true)
            } catch {
                Write-Error "Unknown key '$keyName' in -HoldKeys."
                exit 5
            }
        }
    )
    try {
        foreach ($virtualKey in $virtualKeys) {
            [AresNative.User32Interact]::keybd_event($virtualKey, 0, 0, [UIntPtr]::Zero)
        }
        Start-Sleep -Milliseconds ([Math]::Max(1, $HoldMilliseconds))
    } finally {
        [Array]::Reverse($virtualKeys)
        foreach ($virtualKey in $virtualKeys) {
            [AresNative.User32Interact]::keybd_event($virtualKey, 0, 2, [UIntPtr]::Zero)
        }
    }
    Write-Output "Held keys {$HoldKeys} for $HoldMilliseconds ms"
}

# Handle Text
if (-not [string]::IsNullOrEmpty($Text)) {
    [System.Windows.Forms.SendKeys]::SendWait($Text)
    Write-Output "Sent text '$Text'"
}
