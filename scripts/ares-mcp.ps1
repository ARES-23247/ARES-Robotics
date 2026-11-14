<#
.SYNOPSIS
Launch ARES Project MCP (`:mcp`) over stdio without requiring an installed Studio MSI.

.DESCRIPTION
Builds the `:mcp` installDist distribution with all Gradle output redirected cleanly to stderr,
then executes the launcher with `--mcp-stdio` so stdout remains strictly reserved for MCP JSON-RPC frames.
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$McpArgs
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AnalyticsDir = Join-Path $RepoRoot "ARES-Analytics"
$Gradlew = Join-Path $AnalyticsDir "gradlew.bat"
$Launcher = Join-Path $AnalyticsDir "mcp\build\install\mcp\bin\mcp.bat"

$GradleArgs = @(
    ":mcp:installDist",
    "--quiet",
    "--console=plain",
    "--no-daemon"
)

if ($env:ARES_VERSION) {
    $GradleArgs += "-ParesVersion=$($env:ARES_VERSION)"
}
if ($env:ARES_REPOSITORY) {
    $GradleArgs += "-ParesRepository=$($env:ARES_REPOSITORY)"
} elseif (Test-Path (Join-Path $RepoRoot "ARESLib-Kotlin\settings.gradle.kts")) {
    $LocalCandidateRepo = Join-Path $RepoRoot "ARESLib-Kotlin\build\release-repository"
    $Manifest = Join-Path $LocalCandidateRepo "ares-candidate.properties"
    if (Test-Path $Manifest) {
        $Props = Get-Content $Manifest | Where-Object { $_ -match "^aresVersion=" }
        if ($Props) {
            $CandidateVersion = ($Props -split "=", 2)[1].Trim()
            $RepoUri = ([System.Uri]$LocalCandidateRepo).AbsoluteUri
            $GradleArgs += "-ParesVersion=$CandidateVersion"
            $GradleArgs += "-ParesRepository=$RepoUri"
        }
    } else {
        $GradleArgs += "-ParesUseSiblingLib=true"
    }
}

Push-Location $AnalyticsDir
try {
    & $Gradlew @GradleArgs 1>&2
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

if (-not (Test-Path $Launcher)) {
    [Console]::Error.WriteLine("[ares-mcp] Expected MCP launcher not found at $Launcher")
    exit 1
}

$EffectiveArgs = @("--mcp-stdio")
$HasTemplateDir = $false
if ($McpArgs) {
    foreach ($Arg in $McpArgs) {
        if ($Arg -eq "--template-dir" -or $Arg.StartsWith("--template-dir=")) {
            $HasTemplateDir = $true
            break
        }
    }
    $EffectiveArgs += $McpArgs
}
$DefaultTemplateDir = Join-Path $AnalyticsDir "app\src\main\resources\project-templates"
if (-not $HasTemplateDir -and (Test-Path $DefaultTemplateDir)) {
    $EffectiveArgs += "--template-dir"
    $EffectiveArgs += $DefaultTemplateDir
}

& $Launcher @EffectiveArgs
exit $LASTEXITCODE
