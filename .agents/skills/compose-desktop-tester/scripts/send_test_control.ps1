param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 65535)]
    [int]$Port,
    [int]$ClickX = -1,
    [int]$ClickY = -1,
    [int]$WheelX = -1,
    [int]$WheelY = -1,
    [int]$WheelRotation = 0,
    [string]$Text = "",
    [int]$Key = -1,
    [int]$KeyDown = -1,
    [int]$KeyUp = -1,
    [int]$Modifiers = 0,
    [string]$ChoosePath = "",
    [switch]$Capture,
    [switch]$Ping,
    [switch]$Close,
    [ValidateRange(1, 300)]
    [int]$TimeoutSeconds = 30
)

$actions = @(
    ($ClickX -ge 0 -or $ClickY -ge 0)
    ($WheelX -ge 0 -or $WheelY -ge 0 -or $WheelRotation -ne 0)
    (-not [string]::IsNullOrEmpty($Text))
    ($Key -ge 0)
    ($KeyDown -ge 0)
    ($KeyUp -ge 0)
    (-not [string]::IsNullOrEmpty($ChoosePath))
    $Capture.IsPresent
    $Ping.IsPresent
    $Close.IsPresent
) | Where-Object { $_ }

if ($actions.Count -ne 1) {
    Write-Error "Specify exactly one action: click, wheel, text, key, key-down, key-up, choose-path, capture, ping, or close."
    exit 2
}

if (($ClickX -ge 0) -ne ($ClickY -ge 0)) {
    Write-Error "-ClickX and -ClickY must be supplied together."
    exit 2
}

if (($WheelX -ge 0) -ne ($WheelY -ge 0) -or ($WheelX -ge 0 -and $WheelRotation -eq 0)) {
    Write-Error "-WheelX, -WheelY, and a nonzero -WheelRotation must be supplied together."
    exit 2
}

function ConvertTo-AresBase64([string]$Value) {
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
}

$command = if ($ClickX -ge 0) {
    "CLICK $ClickX $ClickY"
} elseif ($WheelX -ge 0) {
    "WHEEL $WheelX $WheelY $WheelRotation"
} elseif (-not [string]::IsNullOrEmpty($Text)) {
    "TEXT $(ConvertTo-AresBase64 $Text)"
} elseif ($Key -ge 0) {
    "KEY $Key $Modifiers"
} elseif ($KeyDown -ge 0) {
    "KEY_DOWN $KeyDown $Modifiers"
} elseif ($KeyUp -ge 0) {
    "KEY_UP $KeyUp $Modifiers"
} elseif (-not [string]::IsNullOrEmpty($ChoosePath)) {
    if (-not [IO.Path]::IsPathFullyQualified($ChoosePath)) {
        Write-Error "-ChoosePath must be an absolute path."
        exit 2
    }
    if (-not (Test-Path -LiteralPath $ChoosePath)) {
        Write-Error "The chooser path does not exist: $ChoosePath"
        exit 2
    }
    $resolvedPath = (Resolve-Path -LiteralPath $ChoosePath).Path
    "CHOOSE_PATH $(ConvertTo-AresBase64 $resolvedPath)"
} elseif ($Capture) {
    "CAPTURE"
} elseif ($Ping) {
    "PING"
} else {
    "CLOSE"
}

$client = [Net.Sockets.TcpClient]::new()
try {
    $connect = $client.ConnectAsync([Net.IPAddress]::Loopback, $Port)
    if (-not $connect.Wait([TimeSpan]::FromSeconds($TimeoutSeconds))) {
        throw "Timed out connecting to the ARES desktop test control on loopback port $Port."
    }

    $client.ReceiveTimeout = $TimeoutSeconds * 1000
    $client.SendTimeout = $TimeoutSeconds * 1000
    $stream = $client.GetStream()
    $utf8WithoutBom = [Text.UTF8Encoding]::new($false)
    $writer = [IO.StreamWriter]::new($stream, $utf8WithoutBom, 1024, $true)
    $writer.NewLine = "`n"
    $reader = [IO.StreamReader]::new($stream, $utf8WithoutBom, $false, 1024, $true)
    try {
        $writer.WriteLine($command)
        $writer.Flush()
        $response = $reader.ReadLine()
    } finally {
        $reader.Dispose()
        $writer.Dispose()
    }

    if ([string]::IsNullOrEmpty($response)) {
        throw "ARES desktop test control closed the connection without a response."
    }
    if (-not $response.StartsWith("OK ", [StringComparison]::Ordinal)) {
        Write-Error $response
        exit 3
    }
    Write-Output $response
} finally {
    $client.Dispose()
}
