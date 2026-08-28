param(
    [long]$Handle = 0,
    [string]$WindowTitle = "ARES Robotics Studio",
    [int]$OwnerProcessId = 0,
    [int]$WatchSeconds = 0,
    [int]$PollMilliseconds = 100
)

$csharp = @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class NativeWindowInspector {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int X, Y; }

    [StructLayout(LayoutKind.Sequential)]
    public struct WINDOWPLACEMENT {
        public int length;
        public int flags;
        public int showCmd;
        public POINT ptMinPosition;
        public POINT ptMaxPosition;
        public RECT rcNormalPosition;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    public struct MONITORINFO {
        public int cbSize;
        public RECT rcMonitor;
        public RECT rcWork;
        public int dwFlags;
    }

    [ComImport]
    [Guid("A5CD92FF-29BE-454C-8D04-D82879FB3F1B")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IVirtualDesktopManager {
        [PreserveSig]
        int IsWindowOnCurrentVirtualDesktop(
            IntPtr topLevelWindow,
            [MarshalAs(UnmanagedType.Bool)] out bool onCurrentDesktop);
        [PreserveSig]
        int GetWindowDesktopId(IntPtr topLevelWindow, out Guid desktopId);
        [PreserveSig]
        int MoveWindowToDesktop(IntPtr topLevelWindow, ref Guid desktopId);
    }

    [DllImport("user32.dll")]
    static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
    [DllImport("user32.dll")]
    static extern bool IsWindow(IntPtr hWnd);
    [DllImport("user32.dll")]
    static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")]
    static extern bool IsWindowEnabled(IntPtr hWnd);
    [DllImport("user32.dll")]
    static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")]
    static extern bool IsHungAppWindow(IntPtr hWnd);
    [DllImport("user32.dll")]
    static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")]
    static extern bool GetClientRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")]
    static extern bool GetWindowPlacement(IntPtr hWnd, ref WINDOWPLACEMENT placement);
    [DllImport("user32.dll")]
    static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")]
    static extern IntPtr GetWindow(IntPtr hWnd, uint command);
    [DllImport("user32.dll")]
    static extern IntPtr GetAncestor(IntPtr hWnd, uint flags);
    [DllImport("user32.dll")]
    static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int maxCount);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern int GetClassName(IntPtr hWnd, StringBuilder className, int maxCount);
    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtr")]
    static extern IntPtr GetWindowLongPtr64(IntPtr hWnd, int index);
    [DllImport("user32.dll", EntryPoint = "GetWindowLong")]
    static extern IntPtr GetWindowLongPtr32(IntPtr hWnd, int index);
    [DllImport("user32.dll")]
    static extern IntPtr MonitorFromWindow(IntPtr hWnd, uint flags);
    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    static extern bool GetMonitorInfo(IntPtr monitor, ref MONITORINFO info);
    [DllImport("user32.dll")]
    static extern int GetSystemMetrics(int index);
    [DllImport("dwmapi.dll")]
    static extern int DwmGetWindowAttribute(IntPtr hWnd, int attribute, out int value, int size);
    [DllImport("dwmapi.dll")]
    static extern int DwmGetWindowAttribute(IntPtr hWnd, int attribute, out RECT value, int size);

    static IntPtr GetWindowLongPtr(IntPtr hWnd, int index) {
        return IntPtr.Size == 8 ? GetWindowLongPtr64(hWnd, index) : GetWindowLongPtr32(hWnd, index);
    }

    static string Text(IntPtr hWnd) {
        StringBuilder text = new StringBuilder(512);
        GetWindowText(hWnd, text, text.Capacity);
        return text.ToString();
    }

    static string ClassName(IntPtr hWnd) {
        StringBuilder text = new StringBuilder(256);
        GetClassName(hWnd, text, text.Capacity);
        return text.ToString();
    }

    static string RectText(RECT rect) {
        return String.Format(
            "{0},{1}-{2},{3} ({4}x{5})",
            rect.Left, rect.Top, rect.Right, rect.Bottom,
            rect.Right - rect.Left, rect.Bottom - rect.Top);
    }

    public static IntPtr FindByTitle(string query) {
        IntPtr match = IntPtr.Zero;
        EnumWindows((hWnd, ignored) => {
            if (IsWindowVisible(hWnd) && Text(hWnd).IndexOf(query, StringComparison.OrdinalIgnoreCase) >= 0) {
                match = hWnd;
                return false;
            }
            return true;
        }, IntPtr.Zero);
        return match;
    }

    public static string InspectProcessWindows(uint requestedProcessId) {
        StringBuilder result = new StringBuilder();
        int count = 0;
        EnumWindows((hWnd, ignored) => {
            uint processId;
            GetWindowThreadProcessId(hWnd, out processId);
            if (processId == requestedProcessId) {
                count++;
                result.AppendLine("--- top-level window " + count + " ---");
                result.Append(Inspect(hWnd));
            }
            return true;
        }, IntPtr.Zero);
        if (count == 0) {
            result.AppendLine("No top-level HWND is currently owned by process " + requestedProcessId + ".");
        }
        return result.ToString();
    }

    public static string ProcessWindowHandles(uint requestedProcessId) {
        StringBuilder result = new StringBuilder();
        EnumWindows((hWnd, ignored) => {
            uint processId;
            GetWindowThreadProcessId(hWnd, out processId);
            if (processId == requestedProcessId) {
                if (result.Length > 0) result.Append(",");
                result.Append(hWnd.ToInt64());
            }
            return true;
        }, IntPtr.Zero);
        return result.ToString();
    }

    public static string Inspect(IntPtr hWnd) {
        if (hWnd == IntPtr.Zero) return "Handle=0; no matching window was found.";

        uint processId;
        uint threadId = GetWindowThreadProcessId(hWnd, out processId);
        RECT windowRect;
        RECT clientRect;
        GetWindowRect(hWnd, out windowRect);
        GetClientRect(hWnd, out clientRect);

        WINDOWPLACEMENT placement = new WINDOWPLACEMENT();
        placement.length = Marshal.SizeOf(typeof(WINDOWPLACEMENT));
        bool placementOk = GetWindowPlacement(hWnd, ref placement);

        int cloaked = -1;
        int cloakHr = DwmGetWindowAttribute(hWnd, 14, out cloaked, sizeof(int));
        RECT frameRect;
        int frameHr = DwmGetWindowAttribute(hWnd, 9, out frameRect, Marshal.SizeOf(typeof(RECT)));

        IntPtr monitor = MonitorFromWindow(hWnd, 2);
        MONITORINFO monitorInfo = new MONITORINFO();
        monitorInfo.cbSize = Marshal.SizeOf(typeof(MONITORINFO));
        bool monitorOk = monitor != IntPtr.Zero && GetMonitorInfo(monitor, ref monitorInfo);

        bool onCurrentDesktop = false;
        int desktopHr = unchecked((int)0x80004005);
        Guid desktopId = Guid.Empty;
        int desktopIdHr = unchecked((int)0x80004005);
        try {
            Type managerType = Type.GetTypeFromCLSID(new Guid("AA509086-5CA9-4C25-8F95-589D3C07B48A"));
            IVirtualDesktopManager manager = (IVirtualDesktopManager)Activator.CreateInstance(managerType);
            desktopHr = manager.IsWindowOnCurrentVirtualDesktop(hWnd, out onCurrentDesktop);
            desktopIdHr = manager.GetWindowDesktopId(hWnd, out desktopId);
            Marshal.FinalReleaseComObject(manager);
        } catch (Exception exception) {
            return "Virtual desktop probe failed before full inspection: " + exception;
        }

        long style = GetWindowLongPtr(hWnd, -16).ToInt64();
        long exStyle = GetWindowLongPtr(hWnd, -20).ToInt64();
        IntPtr foreground = GetForegroundWindow();
        uint foregroundPid;
        GetWindowThreadProcessId(foreground, out foregroundPid);

        StringBuilder result = new StringBuilder();
        result.AppendLine("Handle=" + hWnd.ToInt64());
        result.AppendLine("Title=" + Text(hWnd));
        result.AppendLine("Class=" + ClassName(hWnd));
        result.AppendLine("ProcessId=" + processId + "; ThreadId=" + threadId);
        result.AppendLine("IsWindow=" + IsWindow(hWnd) + "; Visible=" + IsWindowVisible(hWnd) + "; Enabled=" + IsWindowEnabled(hWnd) + "; Minimized=" + IsIconic(hWnd) + "; Hung=" + IsHungAppWindow(hWnd));
        result.AppendLine("WindowRect=" + RectText(windowRect));
        result.AppendLine("ClientRect=" + RectText(clientRect));
        result.AppendLine("DwmFrame=" + (frameHr == 0 ? RectText(frameRect) : "HRESULT 0x" + frameHr.ToString("X8")));
        result.AppendLine("Placement=" + (placementOk ? "showCmd=" + placement.showCmd + "; normal=" + RectText(placement.rcNormalPosition) : "unavailable"));
        result.AppendLine("Style=0x" + style.ToString("X") + "; ExStyle=0x" + exStyle.ToString("X"));
        result.AppendLine("Root=" + GetAncestor(hWnd, 2).ToInt64() + "; Owner=" + GetWindow(hWnd, 4).ToInt64());
        result.AppendLine("Foreground=" + foreground.ToInt64() + "; ForegroundPid=" + foregroundPid + "; IsForeground=" + (foreground == hWnd));
        result.AppendLine("DwmCloaked=" + (cloakHr == 0 ? cloaked.ToString() : "HRESULT 0x" + cloakHr.ToString("X8")));
        result.AppendLine("OnCurrentVirtualDesktop=" + onCurrentDesktop + "; HRESULT=0x" + desktopHr.ToString("X8") + "; DesktopId=" + desktopId + "; DesktopIdHRESULT=0x" + desktopIdHr.ToString("X8"));
        result.AppendLine("Monitor=" + monitor.ToInt64() + "; MonitorRect=" + (monitorOk ? RectText(monitorInfo.rcMonitor) : "unavailable") + "; WorkRect=" + (monitorOk ? RectText(monitorInfo.rcWork) : "unavailable"));
        result.AppendLine("VirtualScreen=" + GetSystemMetrics(76) + "," + GetSystemMetrics(77) + " " + GetSystemMetrics(78) + "x" + GetSystemMetrics(79));
        return result.ToString();
    }
}
'@

Add-Type -TypeDefinition $csharp

if ($OwnerProcessId -ne 0) {
    if ($WatchSeconds -gt 0) {
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $lastHandles = $null
        while ($stopwatch.Elapsed.TotalSeconds -lt $WatchSeconds) {
            $handles = [NativeWindowInspector]::ProcessWindowHandles([uint32]$OwnerProcessId)
            if ($handles -ne $lastHandles) {
                Write-Output (
                    "[{0:O}] elapsedMs={1}; handles={2}" -f `
                        [DateTimeOffset]::Now, $stopwatch.ElapsedMilliseconds, `
                        $(if ($handles) { $handles } else { "<none>" })
                )
                if ($handles) {
                    [NativeWindowInspector]::InspectProcessWindows([uint32]$OwnerProcessId)
                }
                $lastHandles = $handles
            }
            Start-Sleep -Milliseconds ([Math]::Max(25, $PollMilliseconds))
        }
    } else {
        [NativeWindowInspector]::InspectProcessWindows([uint32]$OwnerProcessId)
    }
} else {
    $hWnd = if ($Handle -ne 0) {
        [IntPtr]::new($Handle)
    } else {
        [NativeWindowInspector]::FindByTitle($WindowTitle)
    }

    [NativeWindowInspector]::Inspect($hWnd)
}
