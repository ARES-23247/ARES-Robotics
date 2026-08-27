[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$manifest = Join-Path $root 'release/ares-versions.properties'
if (-not (Test-Path -LiteralPath $manifest)) { throw 'Canonical release manifest is missing.' }
$release = ConvertFrom-StringData (Get-Content -Raw -LiteralPath $manifest)
foreach ($required in @('aresVersion', 'studioVersion', 'ftcStarterVersion', 'frcStarterVersion', 'githubMavenRepository')) {
    if ([string]::IsNullOrWhiteSpace($release[$required])) { throw "Release manifest is missing $required." }
}
foreach ($retiredHash in @('ftcStarterSha256', 'frcStarterSha256')) {
    if ($release.ContainsKey($retiredHash)) {
        throw "$retiredHash must remain outside the standalone dependency manifest."
    }
}

$starterArtifactsPath = Join-Path $root 'release/starter-artifacts.properties'
if (-not (Test-Path -LiteralPath $starterArtifactsPath)) { throw 'Starter artifact manifest is missing.' }
$starterArtifacts = ConvertFrom-StringData (Get-Content -Raw -LiteralPath $starterArtifactsPath)
foreach ($league in @('ftc', 'frc')) {
    $hashKey = "${league}StarterSha256"
    $expectedHash = $starterArtifacts[$hashKey]
    if ($expectedHash -notmatch '^[0-9a-fA-F]{64}$') {
        throw "Starter artifact manifest has no valid $hashKey."
    }
    $versionKey = "${league}StarterVersion"
    $displayLeague = $league.ToUpperInvariant()
    $archive = Join-Path $root "ARES-Analytics/app/src/main/resources/project-templates/ARES-$displayLeague-Starter-$($release[$versionKey]).zip"
    if (-not (Test-Path -LiteralPath $archive)) { throw "Bundled starter archive is missing: $archive" }
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash
    if ($actualHash -ne $expectedHash) {
        throw "Bundled $displayLeague starter archive hash is $actualHash, expected $expectedHash."
    }
}

$componentProperties = @(
    'ARESLib-Kotlin/gradle.properties',
    'ARES-FTC/gradle.properties',
    'ARES-FRC/gradle.properties',
    'ARES-FTC-Starter/gradle.properties',
    'ARES-FRC-Starter/gradle.properties',
    'ARES-Analytics/gradle.properties'
)
foreach ($path in $componentProperties) {
    $content = Get-Content -Raw -LiteralPath (Join-Path $root $path)
    if ($content -match '(?m)^aresVersion\s*=') { throw "$path duplicates the canonical ARES version." }
}

$buildFiles = Get-ChildItem -LiteralPath $root -Recurse -File |
    Where-Object {
        ($_.Name.EndsWith('.gradle') -or $_.Name.EndsWith('.gradle.kts')) -and
            $_.FullName -notmatch '[\\/]build[\\/]' -and
            $_.FullName -notmatch '[\\/]\.gradle[\\/]'
    }
foreach ($buildFile in $buildFiles) {
    $content = Get-Content -Raw -LiteralPath $buildFile.FullName
    if ($content -match '\bmavenLocal\s*\(') {
        throw "Ambient mavenLocal() is forbidden: $($buildFile.FullName)"
    }
}

$validationEntrypoints = @(
    (Join-Path $root 'verify-autos.ps1'),
    (Join-Path $root 'verify-autos.sh')
) + @(
    Get-ChildItem -LiteralPath (Join-Path $root 'scripts') -File |
        Where-Object {
            ($_.Extension -eq '.ps1' -or $_.Extension -eq '.sh') -and
                $_.Name -ne 'verify-monorepo-policy.ps1'
        } |
        Select-Object -ExpandProperty FullName
) + @(
    Get-ChildItem -LiteralPath (Join-Path $root '.github/workflows') -File |
        Where-Object { $_.Extension -eq '.yml' -or $_.Extension -eq '.yaml' } |
        Select-Object -ExpandProperty FullName
)
foreach ($entrypoint in $validationEntrypoints) {
    $content = Get-Content -Raw -LiteralPath $entrypoint
    if ($content -match 'publishToMavenLocal') {
        throw "Ambient Maven Local publication is forbidden in validation entrypoints: $entrypoint"
    }
}

foreach ($component in @('ARESLib-Kotlin', 'ARES-FTC', 'ARES-FRC', 'ARES-FTC-Starter', 'ARES-FRC-Starter', 'ARES-Analytics')) {
    if (Test-Path -LiteralPath (Join-Path $root "$component/.git")) {
        throw "$component is still a nested Git repository; source must be owned by the monorepo."
    }
}

$canonicalFtcRuntime = Join-Path $root 'templates/ftc/runtime/src/main/kotlin/org/firstinspires/ftc/teamcode/dsl/FtcGeneratedProjectRuntime.kt'
if (-not (Test-Path -LiteralPath $canonicalFtcRuntime)) {
    throw 'Canonical FTC generated-project runtime template is missing.'
}
foreach ($copiedRuntime in @(
    'ARES-FTC/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/dsl/FtcGeneratedProjectRuntime.kt',
    'ARES-FTC-Starter/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/dsl/FtcGeneratedProjectRuntime.kt',
    'ARES-FRC/src/main/kotlin/com/areslib/frc/generatedruntime/FrcGeneratedControlsRuntime.kt',
    'ARES-FRC-Starter/src/main/kotlin/com/areslib/frc/generatedruntime/FrcGeneratedControlsRuntime.kt'
)) {
    if (Test-Path -LiteralPath (Join-Path $root $copiedRuntime)) {
        throw "Generated platform runtime implementation is copied into a consumer: $copiedRuntime"
    }
}

Write-Host "Monorepo policy verified: ARES $($release.aresVersion), Studio $($release.studioVersion)." -ForegroundColor Green
