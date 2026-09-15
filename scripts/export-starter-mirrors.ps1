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
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $standaloneReleaseManifestHash = [System.BitConverter]::ToString(
        $sha256.ComputeHash($standaloneReleaseManifestBytes)
    ).Replace('-', '').ToLowerInvariant()
} finally {
    $sha256.Dispose()
}
$outputRootPath = [System.IO.Path]::GetFullPath($OutputRoot)
$workspacePath = [System.IO.Path]::GetFullPath($workspaceRoot)
$workspacePrefix = $workspacePath.TrimEnd([char[]]'\/') + [System.IO.Path]::DirectorySeparatorChar
if ($outputRootPath.TrimEnd([char[]]'\/').Equals($workspacePath.TrimEnd([char[]]'\/'), [System.StringComparison]::OrdinalIgnoreCase) -or
    $outputRootPath.StartsWith($workspacePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Starter mirrors must be exported outside the source workspace or into an isolated CI temporary directory.'
}

$excludedDirectories = @('.git', '.gradle', 'build')
$excludedFiles = @('local.properties', '.ares-starter-mirror.json')
$templates = @(
    @{ Name = 'ARES-FTC-Starter'; Source = Join-Path $workspaceRoot 'ARES-FTC-Starter' },
    @{ Name = 'ARES-FRC-Starter'; Source = Join-Path $workspaceRoot 'ARES-FRC-Starter' },
    @{ Name = 'ARES-XRP-Starter'; Source = Join-Path $workspaceRoot 'ARES-XRP-Starter' },
    @{ Name = 'ARES-Lightbot-Example'; Source = Join-Path $workspaceRoot 'ARES-FTC' },
    @{ Name = 'ARES-BIOBUZZ-Example'; Source = Join-Path $workspaceRoot 'ARES-FTC-Starter'; Overlay = Join-Path $workspaceRoot 'ARES-FTC/biobuzz' }
)
$ftcRuntimeRelativePath = 'TeamCode/src/main/java/org/firstinspires/ftc/teamcode/dsl/FtcGeneratedProjectRuntime.kt'
$ftcRuntimeSource = Join-Path $workspaceRoot 'templates/ftc/runtime/src/main/kotlin/org/firstinspires/ftc/teamcode/dsl/FtcGeneratedProjectRuntime.kt'
$biobuzzFieldSource = Join-Path $workspaceRoot 'ARES-FTC/biobuzz/shared/src/main/resources/field-presets/ftc/2026-2027-biobuzz.json'
$xrpRuntimeSource = Join-Path $workspaceRoot 'ARESLib-Kotlin/ares-micro/ares_micro'

function Get-RelativeFileHashes([string]$Root) {
    $result = [ordered]@{}
    Get-ChildItem -LiteralPath $Root -Recurse -File -Force | ForEach-Object {
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
    # NUL-delimited output preserves spaces and Unicode without Git's quoted-path encoding.
    $trackedOutput = @(git -C $workspaceRoot ls-files -z -- $rootRelativeToWorkspace)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to enumerate tracked starter files under $rootRelativeToWorkspace."
    }
    $trackedFiles = ($trackedOutput -join "`n").Split([char]0, [System.StringSplitOptions]::RemoveEmptyEntries)
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
    $overlayHashes = @{}
    if ($template.Name -eq 'ARES-Lightbot-Example') {
        foreach ($key in @($sourceHashes.Keys)) {
            if ($key.StartsWith('biobuzz/')) { $sourceHashes.Remove($key) }
        }
    }
    if ($template.Overlay) {
        $overlayHashes = Get-TrackedRelativeFileHashes $template.Overlay
        foreach ($key in $overlayHashes.Keys) { $sourceHashes[$key] = $overlayHashes[$key] }
    }
    if ($template.Name -eq 'ARES-BIOBUZZ-Example') {
        $sourceHashes['TeamCode/src/main/assets/paths/field.json'] = (Get-FileHash -Algorithm SHA256 -LiteralPath $biobuzzFieldSource).Hash.ToLowerInvariant()
    }
    $sourceHashes['release/ares-versions.properties'] = $standaloneReleaseManifestHash
    $sourceHashes['build-logic/ares-versioning.gradle'] = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $workspaceRoot 'build-logic/ares-versioning.gradle')).Hash.ToLowerInvariant()
    if ($template.Name -in @('ARES-FTC-Starter', 'ARES-Lightbot-Example', 'ARES-BIOBUZZ-Example')) {
        $sourceHashes[$ftcRuntimeRelativePath] = (Get-FileHash -Algorithm SHA256 -LiteralPath $ftcRuntimeSource).Hash.ToLowerInvariant()
    }
    if ($template.Name -eq 'ARES-XRP-Starter') {
        $runtimeHashes = Get-TrackedRelativeFileHashes $xrpRuntimeSource
        foreach ($runtimeRelative in $runtimeHashes.Keys) {
            if ($runtimeRelative.Split('/') -contains '__pycache__' -or $runtimeRelative.EndsWith('.pyc')) { continue }
            $sourceHashes["lib/ares_micro/$runtimeRelative"] = $runtimeHashes[$runtimeRelative]
        }
    }

    if ($Check) {
        if (-not (Test-Path -LiteralPath $destination)) { throw "Missing generated mirror: $destination" }
        $destinationHashes = Get-RelativeFileHashes $destination
        # Compare actual keyed hashes, not dictionary-enumerator objects. Comparing
        # those objects does not compare their entries and allowed modified mirrors to pass.
        $differences = [System.Collections.Generic.List[string]]::new()
        foreach ($relative in $sourceHashes.Keys) {
            if (-not $destinationHashes.Contains($relative)) { $differences.Add("Missing: $relative") }
            elseif ($sourceHashes[$relative] -ne $destinationHashes[$relative]) { $differences.Add("Changed: $relative") }
        }
        foreach ($relative in $destinationHashes.Keys) {
            if (-not $sourceHashes.Contains($relative)) { $differences.Add("Unexpected: $relative") }
        }
        if ($differences.Count -gt 0) { throw "$($template.Name) mirror differs from canonical template.`n$($differences -join "`n")" }
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
        } elseif ($template.Name -in @('ARES-FTC-Starter', 'ARES-Lightbot-Example', 'ARES-BIOBUZZ-Example') -and $entry -eq $ftcRuntimeRelativePath) {
            $ftcRuntimeSource
        } elseif ($template.Name -eq 'ARES-XRP-Starter' -and $entry.StartsWith('lib/ares_micro/')) {
            Join-Path $xrpRuntimeSource $entry.Substring('lib/ares_micro/'.Length)
        } elseif ($template.Name -eq 'ARES-BIOBUZZ-Example' -and $entry -eq 'TeamCode/src/main/assets/paths/field.json') {
            $biobuzzFieldSource
        } elseif ($overlayHashes.Contains($entry)) {
            Join-Path $template.Overlay $entry
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
