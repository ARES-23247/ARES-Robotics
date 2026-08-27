[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$LegacyWorkspace,
    [Parameter(Mandatory = $true)][string]$Destination,
    [string]$OutputReport = 'monorepo-migration-inventory.txt'
)

$ErrorActionPreference = 'Stop'
$legacy = [System.IO.Path]::GetFullPath($LegacyWorkspace)
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
if (-not (Test-Path -LiteralPath $legacy -PathType Container)) { throw "Legacy workspace not found: $legacy" }
if ($destinationPath.Equals($legacy, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Destination must differ from the legacy workspace.' }
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('ARES clean-monorepo migration inventory')
$lines.Add("Legacy workspace: $legacy")
$lines.Add("Proposed destination: $destinationPath")
$lines.Add("Generated: $([DateTimeOffset]::Now.ToString('O'))")
$lines.Add('')
$repositories = @($legacy) + @(Get-ChildItem -LiteralPath $legacy -Directory -Force | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName '.git') } | Select-Object -ExpandProperty FullName)
foreach ($repository in $repositories | Select-Object -Unique) {
    if (-not (Test-Path -LiteralPath (Join-Path $repository '.git'))) { continue }
    $branch = (git -C $repository branch --show-current).Trim()
    $head = (git -C $repository rev-parse HEAD).Trim()
    $status = @(git -C $repository status --short)
    $lines.Add("Repository: $repository")
    $lines.Add("  Branch: $branch")
    $lines.Add("  HEAD: $head")
    $lines.Add("  Dirty entries: $($status.Count)")
    foreach ($entry in $status) { $lines.Add("    $entry") }
}
$lines.Add('')
$lines.Add('Safe next step: clone ARES-23247/ARES-Robotics into the proposed destination.')
$lines.Add('Do not delete or reset the legacy workspace. Copy only reviewed user-owned/untracked files.')
$lines.Add('Run setup.ps1 and the protected validation matrix before switching daily work.')
$report = if ([System.IO.Path]::IsPathRooted($OutputReport)) { $OutputReport } else { Join-Path (Get-Location) $OutputReport }
$lines | Set-Content -LiteralPath $report -Encoding UTF8
Write-Host "Wrote non-destructive migration inventory: $report" -ForegroundColor Green

