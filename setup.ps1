# Bootstraps a source-monorepo checkout without downloading or overwriting component source.
# Usage: .\setup.ps1

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$required = @(
    'ARESLib-Kotlin',
    'ARES-FTC',
    'ARES-FRC',
    'ARES-FTC-Starter',
    'ARES-FRC-Starter',
    'ARES-Analytics',
    'release/ares-versions.properties',
    'build-logic/ares-versioning.gradle'
)
$missing = $required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $root $_)) }
if ($missing) { throw "Incomplete ARES-Robotics checkout. Missing: $($missing -join ', ')" }

& (Join-Path $root 'scripts/verify-monorepo-policy.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "`nARES-Robotics source monorepo is ready." -ForegroundColor Green
Write-Host '  Full test matrix:       .\build.ps1 -Task Test'
Write-Host '  Studio compile only:    .\build.ps1 -Task Studio'
Write-Host '  Studio launch:          cd ARES-Analytics; .\gradlew.bat :app:run'
Write-Host '  Candidate validation:   .\build.ps1 -Task ReleaseValidation -CandidateVersion <version>-rc.<commit>'
