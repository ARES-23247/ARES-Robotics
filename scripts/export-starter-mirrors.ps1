[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputRoot,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$outputRootPath = [System.IO.Path]::GetFullPath($OutputRoot)
$workspacePath = [System.IO.Path]::GetFullPath($workspaceRoot)
if ($outputRootPath.StartsWith($workspacePath, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Starter mirrors must be exported outside the source workspace or into an isolated CI temporary directory.'
}

$excludedDirectories = @('.git', '.gradle', 'build')
$excludedFiles = @('local.properties', '.ares-starter-mirror.json')
$templates = @(
    @{ Name = 'ARES-FTC-Starter'; Source = Join-Path $workspaceRoot 'ARES-FTC-Starter' },
    @{ Name = 'ARES-FRC-Starter'; Source = Join-Path $workspaceRoot 'ARES-FRC-Starter' }
)
$ftcRuntimeRelativePath = 'TeamCode/src/main/java/org/firstinspires/ftc/teamcode/dsl/FtcGeneratedProjectRuntime.kt'
$ftcRuntimeSource = Join-Path $workspaceRoot 'templates/ftc/runtime/src/main/kotlin/org/firstinspires/ftc/teamcode/dsl/FtcGeneratedProjectRuntime.kt'

function Get-RelativeFileHashes([string]$Root) {
    $result = [ordered]@{}
    Get-ChildItem -LiteralPath $Root -Recurse -File | ForEach-Object {
        $relative = [System.IO.Path]::GetRelativePath($Root, $_.FullName).Replace('\', '/')
        $segments = $relative.Split('/')
        if ($excludedFiles -contains $_.Name -or ($segments | Where-Object { $excludedDirectories -contains $_ })) { return }
        $result[$relative] = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
    }
    $result
}

foreach ($template in $templates) {
    $destination = Join-Path $outputRootPath $template.Name
    $sourceHashes = Get-RelativeFileHashes $template.Source
    $sourceHashes['release/ares-versions.properties'] = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $workspaceRoot 'release/ares-versions.properties')).Hash.ToLowerInvariant()
    $sourceHashes['build-logic/ares-versioning.gradle'] = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $workspaceRoot 'build-logic/ares-versioning.gradle')).Hash.ToLowerInvariant()
    if ($template.Name -eq 'ARES-FTC-Starter') {
        $sourceHashes[$ftcRuntimeRelativePath] = (Get-FileHash -Algorithm SHA256 -LiteralPath $ftcRuntimeSource).Hash.ToLowerInvariant()
    }

    if ($Check) {
        if (-not (Test-Path -LiteralPath $destination)) { throw "Missing generated mirror: $destination" }
        $destinationHashes = Get-RelativeFileHashes $destination
        $difference = Compare-Object $sourceHashes.GetEnumerator() $destinationHashes.GetEnumerator() -Property Name, Value
        if ($difference) { throw "$($template.Name) mirror differs from canonical template.`n$($difference | Out-String)" }
        Write-Host "verified $($template.Name)" -ForegroundColor Green
        continue
    }

    if (Test-Path -LiteralPath $destination) {
        throw "Refusing to replace existing mirror directory: $destination"
    }
    New-Item -ItemType Directory -Path $destination | Out-Null
    foreach ($entry in $sourceHashes.Keys) {
        $source = if ($entry -eq 'release/ares-versions.properties') {
            Join-Path $workspaceRoot $entry
        } elseif ($entry -eq 'build-logic/ares-versioning.gradle') {
            Join-Path $workspaceRoot $entry
        } elseif ($template.Name -eq 'ARES-FTC-Starter' -and $entry -eq $ftcRuntimeRelativePath) {
            $ftcRuntimeSource
        } else {
            Join-Path $template.Source $entry
        }
        $target = Join-Path $destination $entry
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        Copy-Item -LiteralPath $source -Destination $target
    }
    $manifest = [ordered]@{
        schemaVersion = 1
        sourceRepository = 'ARES-23247/ARES-Robotics'
        sourceCommit = (git -C $workspaceRoot rev-parse HEAD)
        templatePath = $template.Name
        files = $sourceHashes
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destination '.ares-starter-mirror.json') -Encoding UTF8
    Write-Host "exported $($template.Name) -> $destination" -ForegroundColor Cyan
}
