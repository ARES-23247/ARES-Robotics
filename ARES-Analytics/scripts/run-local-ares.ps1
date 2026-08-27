param(
    [string]$CandidateVersion = "",
    [switch]$FullValidation,
    [string]$IsolatedDesktopHome = ""
)

$ErrorActionPreference = "Stop"
$analyticsRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$aresLibRoot = (Resolve-Path (Join-Path $analyticsRoot "..\ARESLib-Kotlin")).Path
$aresLibGradle = Join-Path $aresLibRoot "gradlew.bat"
$analyticsGradle = Join-Path $analyticsRoot "gradlew.bat"

if ([string]::IsNullOrWhiteSpace($CandidateVersion)) {
    $baseVersionLine = Select-String -Path (Join-Path $aresLibRoot "gradle.properties") -Pattern '^aresVersion=' | Select-Object -First 1
    if ($null -eq $baseVersionLine) {
        throw "ARESLib gradle.properties does not declare aresVersion."
    }
    $baseVersion = ($baseVersionLine.Line -split '=', 2)[1].Trim()
    $stamp = [DateTime]::UtcNow.ToString("yyyyMMddHHmmss")
    $CandidateVersion = "$baseVersion-rc.local.$stamp"
}

$validationRepository = (Join-Path $aresLibRoot "build\release-repository")
$repositoryUri = ([Uri]$validationRepository).AbsoluteUri
$publishTasks = if ($FullValidation) {
    @("test", "apiCheck", "publishReleaseValidation")
} else {
    @("apiCheck", "publishReleaseValidation")
}

Write-Host "[ARES] Publishing local candidate $CandidateVersion"
Push-Location $aresLibRoot
try {
    & $aresLibGradle @publishTasks --no-parallel "-ParesVersion=$CandidateVersion"
    if ($LASTEXITCODE -ne 0) {
        throw "ARESLib validation publication failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

Write-Host "[ARES] Launching Robotics Studio and forwarding the same candidate to nested robot builds"
Push-Location $analyticsRoot
try {
    $launchArgs = @(
        ":app:run",
        "--no-parallel",
        "-ParesVersion=$CandidateVersion",
        "-ParesRepository=$repositoryUri"
    )
    if (-not [string]::IsNullOrWhiteSpace($IsolatedDesktopHome)) {
        $launchArgs += "-ParesIsolatedDesktopHome=$IsolatedDesktopHome"
    }
    & $analyticsGradle @launchArgs
    if ($LASTEXITCODE -ne 0) {
        throw "ARES Robotics Studio exited with code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
