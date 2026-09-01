[CmdletBinding()]
param(
    [string]$DataDirectory,
    [string]$CaptureFile,
    [string]$AresRepository,
    [string]$AresVersion,
    [switch]$Fresh
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$diagnosticsRoot = Join-Path $repositoryRoot 'build\diagnostics'
if ([string]::IsNullOrWhiteSpace($DataDirectory)) {
    $DataDirectory = Join-Path $diagnosticsRoot 'desktop-e2e-profile'
}
$resolvedDataDirectory = [System.IO.Path]::GetFullPath($DataDirectory)
$resolvedDiagnosticsRoot = [System.IO.Path]::GetFullPath($diagnosticsRoot)

if ($Fresh) {
    $relative = [System.IO.Path]::GetRelativePath($resolvedDiagnosticsRoot, $resolvedDataDirectory)
    if ($relative.StartsWith('..') -or [System.IO.Path]::IsPathRooted($relative)) {
        throw "-Fresh only removes profiles inside $resolvedDiagnosticsRoot"
    }
    if (Test-Path -LiteralPath $resolvedDataDirectory) {
        Remove-Item -LiteralPath $resolvedDataDirectory -Recurse -Force
    }
}

New-Item -ItemType Directory -Path $resolvedDataDirectory -Force | Out-Null
Write-Host "[ARES-Analytics] Isolated app data: $resolvedDataDirectory"

$priorDataDirectory = $env:ARES_ANALYTICS_DATA_DIR
$priorCapture = $env:ARES_ANALYTICS_STARTUP_CAPTURE
$priorCaptureClose = $env:ARES_ANALYTICS_STARTUP_CAPTURE_CLOSE
try {
    $env:ARES_ANALYTICS_DATA_DIR = $resolvedDataDirectory
    if (-not [string]::IsNullOrWhiteSpace($CaptureFile)) {
        $env:ARES_ANALYTICS_STARTUP_CAPTURE = [System.IO.Path]::GetFullPath($CaptureFile)
        $env:ARES_ANALYTICS_STARTUP_CAPTURE_CLOSE = 'true'
    }
    # The isolated app-data root owns a different instance lock. Skip the normal replacement task
    # so an acceptance journey cannot close a student's installed Studio session.
    $gradleArguments = @(':app:run', '-PskipKill=true')
    if (-not [string]::IsNullOrWhiteSpace($AresRepository)) {
        $gradleArguments += "-ParesRepository=$AresRepository"
    }
    if (-not [string]::IsNullOrWhiteSpace($AresVersion)) {
        $gradleArguments += "-ParesVersion=$AresVersion"
    }
    & (Join-Path $repositoryRoot 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    $env:ARES_ANALYTICS_DATA_DIR = $priorDataDirectory
    $env:ARES_ANALYTICS_STARTUP_CAPTURE = $priorCapture
    $env:ARES_ANALYTICS_STARTUP_CAPTURE_CLOSE = $priorCaptureClose
}
