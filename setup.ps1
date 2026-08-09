# Clones the four ARES subprojects as siblings of this script.
# Idempotent: existing directories are skipped (local work is never overwritten).
#
# Usage:  .\setup.ps1

$ErrorActionPreference = 'Stop'

$Org = 'ARES-23247'
$Repos = @('ARESLib-Kotlin', 'ARES-FTC', 'ARES-FRC', 'ARES-Analytics')
$Root = $PSScriptRoot

foreach ($repo in $Repos) {
    $dest = Join-Path $Root $repo
    if (Test-Path -LiteralPath $dest) {
        Write-Host "skip   $repo (exists: $dest)" -ForegroundColor DarkGray
        continue
    }
    $url = "https://github.com/$Org/$repo.git"
    Write-Host "clone  $repo <- $url" -ForegroundColor Cyan
    git clone $url $dest
    if ($LASTEXITCODE -ne 0) { throw "Failed to clone $repo" }
}

Write-Host ""
Write-Host "Workspace ready. Next steps:" -ForegroundColor Green
Write-Host "  1. Build foundation first:  cd ARESLib-Kotlin ; .\gradlew.bat publishToMavenLocal"
Write-Host "  2. Per-project build/test/run commands: see AGENTS.md"
