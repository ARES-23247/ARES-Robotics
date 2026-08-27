[CmdletBinding()]
param(
    [ValidateSet('Test', 'ReleaseValidation', 'Studio')]
    [string]$Task = 'Test',
    [string]$CandidateVersion,
    [string]$ValidationRepository
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = $PSScriptRoot
$releaseManifest = Join-Path $workspaceRoot 'release/ares-versions.properties'
$release = ConvertFrom-StringData (Get-Content -Raw -LiteralPath $releaseManifest)

function Invoke-GradleBuild {
    param([string]$Project, [string[]]$Arguments)
    $projectRoot = Join-Path $workspaceRoot $Project
    $gradle = Join-Path $projectRoot 'gradlew.bat'
    Write-Host "`n==> $Project $($Arguments -join ' ')" -ForegroundColor Cyan
    & $gradle @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Project failed with exit code $LASTEXITCODE" }
}

function Ensure-AndroidSdkPath {
    param([string]$Project)
    $projectRoot = Join-Path $workspaceRoot $Project
    $localProperties = Join-Path $projectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) { return }
    $sdk = $env:ANDROID_HOME
    if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = $env:ANDROID_SDK_ROOT }
    if ([string]::IsNullOrWhiteSpace($sdk)) {
        $defaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
        if (Test-Path -LiteralPath $defaultSdk) { $sdk = $defaultSdk }
    }
    if ([string]::IsNullOrWhiteSpace($sdk) -or -not (Test-Path -LiteralPath $sdk)) {
        throw "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT."
    }
    "sdk.dir=$($sdk.Replace('\', '/'))" | Set-Content -LiteralPath $localProperties -Encoding Ascii
    Write-Host "Configured ignored $Project/local.properties from the detected Android SDK." -ForegroundColor DarkGray
}

$versionArguments = @("-ParesVersion=$($release.aresVersion)")
if ($Task -eq 'ReleaseValidation') {
    if ([string]::IsNullOrWhiteSpace($CandidateVersion)) {
        throw 'ReleaseValidation requires -CandidateVersion, for example 10.2.0-rc.<commit>.'
    }
    $versionArguments = @("-ParesVersion=$CandidateVersion")
}
if (-not [string]::IsNullOrWhiteSpace($ValidationRepository)) {
    $versionArguments += "-ParesRepository=$ValidationRepository"
}

if ($Task -eq 'Studio') {
    Invoke-GradleBuild 'ARES-Analytics' (@(':app:compileKotlin', '--no-parallel', '--console=plain') + $versionArguments)
    exit 0
}

if ($Task -eq 'ReleaseValidation') {
    Invoke-GradleBuild 'ARESLib-Kotlin' (@('test', 'apiCheck', 'publishReleaseValidation', '--no-parallel', '--console=plain') + $versionArguments)
    if ([string]::IsNullOrWhiteSpace($ValidationRepository)) {
        $ValidationRepository = (New-Object System.Uri((Join-Path $workspaceRoot 'ARESLib-Kotlin/build/release-repository'))).AbsoluteUri
        $versionArguments += "-ParesRepository=$ValidationRepository"
    }
} else {
    Invoke-GradleBuild 'ARESLib-Kotlin' (@('test', 'apiCheck', '--no-parallel', '--console=plain') + $versionArguments)
}

Ensure-AndroidSdkPath 'ARES-FTC'
Ensure-AndroidSdkPath 'ARES-FTC-Starter'
Invoke-GradleBuild 'ARES-FTC' (@(':TeamCode:testDebugUnitTest', ':simulator:test', ':TeamCode:assembleDebug', '--no-parallel', '--console=plain') + $versionArguments)
Invoke-GradleBuild 'ARES-FRC' (@('test', '--no-parallel', '--console=plain') + $versionArguments)
Invoke-GradleBuild 'ARES-FTC-Starter' (@(':TeamCode:testDebugUnitTest', ':simulator:test', ':TeamCode:assembleDebug', '--no-parallel', '--console=plain') + $versionArguments)
Invoke-GradleBuild 'ARES-FRC-Starter' (@('test', '--no-parallel', '--console=plain') + $versionArguments)
Invoke-GradleBuild 'ARES-Analytics' (@(':shared:test', ':app:test', ':gateway:test', '--no-parallel', '--console=plain') + $versionArguments)

Write-Host "`nARES source-monorepo $Task matrix passed." -ForegroundColor Green
