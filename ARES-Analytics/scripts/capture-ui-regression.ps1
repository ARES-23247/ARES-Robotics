param(
    [string]$WindowTitle = "ARES Robotics Studio",
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $projectRoot
$testerRoot = Join-Path $workspaceRoot ".agents\skills\compose-desktop-tester\scripts"
$capture = Join-Path $testerRoot "capture_app.ps1"
$inspect = Join-Path $testerRoot "inspect_app_window.ps1"

foreach ($tool in @($capture, $inspect)) {
    if (-not (Test-Path -LiteralPath $tool)) {
        throw "Required Compose desktop tester was not found: $tool"
    }
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $projectRoot "build\diagnostics\ui-regression"
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

Add-Type -AssemblyName System.Windows.Forms
$nativeSource = @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class AresVisualRegressionWindow {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr state);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int command);
    [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr hWnd, int x, int y, int width, int height, bool repaint);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint x, uint y, uint data, UIntPtr extraInfo);

    public struct RECT { public int Left, Top, Right, Bottom; }

    public static IntPtr Find(string titleQuery) {
        IntPtr match = IntPtr.Zero;
        EnumWindows((hWnd, state) => {
            if (!IsWindowVisible(hWnd)) return true;
            StringBuilder title = new StringBuilder(256);
            GetWindowText(hWnd, title, title.Capacity);
            if (title.ToString().IndexOf(titleQuery, StringComparison.OrdinalIgnoreCase) < 0) return true;
            match = hWnd;
            return false;
        }, IntPtr.Zero);
        return match;
    }
}
'@
Add-Type -TypeDefinition $nativeSource -ErrorAction SilentlyContinue

$hWnd = [AresVisualRegressionWindow]::Find($WindowTitle)
if ($hWnd -eq [IntPtr]::Zero) {
    throw "No visible ARES window matching '$WindowTitle' was found."
}

function Send-RelativeWheel([int]$relativeX, [int]$relativeY, [int]$delta) {
    $rect = New-Object AresVisualRegressionWindow+RECT
    [AresVisualRegressionWindow]::GetWindowRect($hWnd, [ref]$rect) | Out-Null
    [AresVisualRegressionWindow]::SetForegroundWindow($hWnd) | Out-Null
    [AresVisualRegressionWindow]::SetCursorPos($rect.Left + $relativeX, $rect.Top + $relativeY) | Out-Null
    $wheelData = [BitConverter]::ToUInt32([BitConverter]::GetBytes($delta), 0)
    [AresVisualRegressionWindow]::mouse_event(0x0800, 0, 0, $wheelData, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 500
}

[AresVisualRegressionWindow]::ShowWindow($hWnd, 3) | Out-Null # SW_MAXIMIZE
Start-Sleep -Milliseconds 500
Send-RelativeWheel 800 500 7200
& $inspect -WindowTitle $WindowTitle
& $capture -WindowTitle $WindowTitle -OutputFile (Join-Path $OutputDirectory "desktop-1920x1080-maximized.png")

[AresVisualRegressionWindow]::ShowWindow($hWnd, 9) | Out-Null # SW_RESTORE
Start-Sleep -Milliseconds 200
if (-not [AresVisualRegressionWindow]::MoveWindow($hWnd, 240, 66, 1440, 900, $true)) {
    throw "Failed to resize the ARES window to 1440 x 900."
}
Start-Sleep -Milliseconds 500
& $inspect -WindowTitle $WindowTitle
& $capture -WindowTitle $WindowTitle -OutputFile (Join-Path $OutputDirectory "desktop-1440x900.png")

if (-not [AresVisualRegressionWindow]::MoveWindow($hWnd, 360, 144, 1100, 700, $true)) {
    throw "Failed to resize the ARES window to 1100 x 700."
}
Start-Sleep -Milliseconds 500
& $inspect -WindowTitle $WindowTitle
& $capture -WindowTitle $WindowTitle -OutputFile (Join-Path $OutputDirectory "desktop-1100x700.png")

# Scroll the active minimum-size Dashboard so the capture also covers its lower responsive cards.
# For controller acceptance, open the live Dashboard with the Gamepad Monitor before running this.
Send-RelativeWheel 800 700 -7200
& $capture -WindowTitle $WindowTitle -OutputFile (Join-Path $OutputDirectory "dashboard-narrow.png")

Write-Output "Captured UI regression set to $OutputDirectory"
Write-Output "Review every PNG for real rendered content; dimensions and a live HWND alone are not a visual pass."
