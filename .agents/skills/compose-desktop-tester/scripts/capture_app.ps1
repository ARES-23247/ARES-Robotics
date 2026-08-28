param(
    [string]$WindowTitle = "ARES Robotics Studio",
    [string]$OutputFile = (Join-Path $PSScriptRoot "../../../../ARES-Analytics/build/diagnostics/window.png"),
    [switch]$FullScreen,
    [switch]$NoActivate
)

Add-Type -AssemblyName System.Windows.Forms,System.Drawing

$csharp = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
using System.Collections.Generic;

public class NativeCapture {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")]
    public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")]
    public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);
    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")]
    public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdcBkgnd, uint nFlags);

    public struct RECT {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    public static IntPtr FindMatchingWindow(string titleQuery) {
        IntPtr result = IntPtr.Zero;
        EnumWindows((hWnd, lParam) => {
            if (IsWindowVisible(hWnd)) {
                StringBuilder sb = new StringBuilder(256);
                GetWindowText(hWnd, sb, 256);
                string title = sb.ToString();
                if (!string.IsNullOrEmpty(title) && title.IndexOf(titleQuery, StringComparison.OrdinalIgnoreCase) >= 0) {
                    result = hWnd;
                    return false; // stop enumeration
                }
            }
            return true;
        }, IntPtr.Zero);
        return result;
    }
}
'@
Add-Type -TypeDefinition $csharp -ErrorAction SilentlyContinue

$outputDir = [System.IO.Path]::GetDirectoryName($OutputFile)
if (-not [string]::IsNullOrEmpty($outputDir) -and -not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
}

$hWnd = [NativeCapture]::FindMatchingWindow($WindowTitle)

if ($hWnd -ne [IntPtr]::Zero -and -not $FullScreen) {
    if ([NativeCapture]::IsIconic($hWnd)) {
        [NativeCapture]::ShowWindow($hWnd, 9) | Out-Null # SW_RESTORE
    }
    if (-not $NoActivate) {
        [NativeCapture]::SetForegroundWindow($hWnd) | Out-Null
        Start-Sleep -Milliseconds 300
    }

    $rect = New-Object NativeCapture+RECT
    [NativeCapture]::GetWindowRect($hWnd, [ref]$rect)

    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top

    if ($width -gt 0 -and $height -gt 0) {
        $bitmap = New-Object System.Drawing.Bitmap($width, $height)
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)

        $hdc = $graphics.GetHdc()
        $pwSuccess = [NativeCapture]::PrintWindow($hWnd, $hdc, 2) # PW_RENDERFULLCONTENT
        $graphics.ReleaseHdc($hdc)

        if (-not $pwSuccess) {
            try {
                $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, (New-Object System.Drawing.Size($width, $height)))
            } catch {}
        }
        $bitmap.Save($OutputFile, [System.Drawing.Imaging.ImageFormat]::Png)
        $graphics.Dispose()
        $bitmap.Dispose()
        Write-Output "Captured window handle $hWnd ($width x $height) to: $OutputFile"
        exit 0
    }
}

# A full-desktop image must never be mistaken for evidence that ARES created a visible HWND.
if (-not $FullScreen) {
    if ($hWnd -eq [IntPtr]::Zero) {
        Write-Error "No visible top-level window matching '$WindowTitle' was found."
        exit 2
    }
    Write-Error "The window matching '$WindowTitle' has invalid or zero-sized bounds."
    exit 3
}

# Full-screen capture is explicit diagnostic behavior only.
try {
    $screen = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bitmap = New-Object System.Drawing.Bitmap($screen.Width, $screen.Height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.CopyFromScreen($screen.Left, $screen.Top, 0, 0, $screen.Size)
    $bitmap.Save($OutputFile, [System.Drawing.Imaging.ImageFormat]::Png)
    $graphics.Dispose()
    $bitmap.Dispose()
    Write-Output "Captured Full Primary Screen ($($screen.Width) x $($screen.Height)) to: $OutputFile"
} catch {
    Write-Error "Full-screen capture failed: $($_.Exception.Message)"
    exit 4
}
