[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputRoot,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$releaseProperties = ConvertFrom-StringData (
    Get-Content -Raw -LiteralPath (Join-Path $workspaceRoot 'release/ares-versions.properties')
)
$standaloneReleaseManifest = @"
# Standalone robot dependency identity. Desktop and template versions intentionally stay outside
# this file so an unchanged robot archive remains byte-identical across Studio-only releases.
aresVersion=$($releaseProperties['aresVersion'])
githubMavenRepository=$($releaseProperties['githubMavenRepository'])
"@.Replace("`r`n", "`n")
if (-not $standaloneReleaseManifest.EndsWith("`n")) { $standaloneReleaseManifest += "`n" }
$standaloneReleaseManifestBytes = [System.Text.UTF8Encoding]::new($false).GetBytes($standaloneReleaseManifest)
$standaloneReleaseManifestHash = [System.BitConverter]::ToString(
    [System.Security.Cryptography.SHA256]::HashData($standaloneReleaseManifestBytes)
).Replace('-', '').ToLowerInvariant()
$outputRootPath = [System.IO.Path]::GetFullPath($OutputRoot)
$workspacePath = [System.IO.Path]::GetFullPath($workspaceRoot)
if ($outputRootPath.StartsWith($workspacePath, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Starter mirrors must be exported outside the source workspace or into an isolated CI temporary directory.'
}

$excludedDirectories = @('.git', '.gradle', 'build')
$excludedFiles = @('local.properties', '.ares-starter-mirror.json')
$templates = @(
    @{ Name = 'ARES-FTC-Starter'; Source = Join-Path $workspaceRoot 'ARES-FTC-Starter' },
    @{ Name = 'ARES-FRC-Starter'; Source = Join-Path $workspaceRoot 'ARES-FRC-Starter' },
    @{ Name = 'ARES-Lightbot-Example'; Source = Join-Path $workspaceRoot 'ARES-FTC' }
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

function Get-TrackedRelativeFileHashes([string]$Root) {
    $result = [ordered]@{}
    $rootRelativeToWorkspace = [System.IO.Path]::GetRelativePath($workspaceRoot, $Root).Replace('\', '/')
    $trackedFiles = @(git -C $workspaceRoot ls-files -- $rootRelativeToWorkspace)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to enumerate tracked starter files under $rootRelativeToWorkspace."
    }
    foreach ($trackedPath in $trackedFiles | Sort-Object) {
        $fullPath = Join-Path $workspaceRoot $trackedPath
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            throw "Tracked starter file is missing from the worktree: $trackedPath"
        }
        $relative = [System.IO.Path]::GetRelativePath($Root, $fullPath).Replace('\', '/')
        $segments = $relative.Split('/')
        if ($excludedFiles -contains [System.IO.Path]::GetFileName($relative) -or
            ($segments | Where-Object { $excludedDirectories -contains $_ })) {
            continue
        }
        $result[$relative] = (Get-FileHash -Algorithm SHA256 -LiteralPath $fullPath).Hash.ToLowerInvariant()
    }
    $result
}

foreach ($template in $templates) {
    $destination = Join-Path $outputRootPath $template.Name
    # Release mirrors are made only from canonical, tracked source. Ignored
    # simulator logs, IDE state, caches, and other local files must never leak
    # into an installer or public starter archive.
    $sourceHashes = Get-TrackedRelativeFileHashes $template.Source
    $sourceHashes['release/ares-versions.properties'] = $standaloneReleaseManifestHash
    $sourceHashes['build-logic/ares-versioning.gradle'] = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $workspaceRoot 'build-logic/ares-versioning.gradle')).Hash.ToLowerInvariant()
    if ($template.Name -eq 'ARES-FTC-Starter' -or $template.Name -eq 'ARES-Lightbot-Example') {
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
        $source = if ($entry -eq 'build-logic/ares-versioning.gradle') {
            Join-Path $workspaceRoot $entry
        } elseif (($template.Name -eq 'ARES-FTC-Starter' -or $template.Name -eq 'ARES-Lightbot-Example') -and $entry -eq $ftcRuntimeRelativePath) {
            $ftcRuntimeSource
        } else {
            Join-Path $template.Source $entry
        }
        $target = Join-Path $destination $entry
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        if ($entry -eq 'release/ares-versions.properties') {
            [System.IO.File]::WriteAllBytes($target, $standaloneReleaseManifestBytes)
        } else {
            Copy-Item -LiteralPath $source -Destination $target
        }
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
