[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'verify-doc-links.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Markdown link verification failed.' }
$manifest = Join-Path $root 'release/ares-versions.properties'
if (-not (Test-Path -LiteralPath $manifest)) { throw 'Canonical release manifest is missing.' }
$release = ConvertFrom-StringData (Get-Content -Raw -LiteralPath $manifest)
foreach ($required in @('aresVersion', 'studioVersion', 'ftcStarterVersion', 'frcStarterVersion', 'xrpStarterVersion', 'lightbotExampleVersion', 'githubMavenRepository')) {
    if ([string]::IsNullOrWhiteSpace($release[$required])) { throw "Release manifest is missing $required." }
}
$aresSourceTreePath = Join-Path $root 'release/ares-source-tree.txt'
if (-not (Test-Path -LiteralPath $aresSourceTreePath -PathType Leaf)) {
    throw 'Canonical ARES source-tree identity is missing.'
}
$expectedAresTree = (Get-Content -Raw -LiteralPath $aresSourceTreePath).Trim()
if ($expectedAresTree -notmatch '^[0-9a-f]{40}$') {
    throw 'release/ares-source-tree.txt must contain one lowercase 40-character Git tree ID.'
}
$actualAresTree = (& git -C $root rev-parse 'HEAD:ARESLib-Kotlin').Trim()
if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve the current ARESLib source tree.' }
if ($actualAresTree -ne $expectedAresTree) {
    throw "ARESLib source tree is $actualAresTree, but release/ares-source-tree.txt records $expectedAresTree. Bump aresVersion and update the source-tree identity together."
}
foreach ($retiredHash in @('ftcStarterSha256', 'frcStarterSha256', 'xrpStarterSha256', 'lightbotExampleSha256')) {
    if ($release.ContainsKey($retiredHash)) {
        throw "$retiredHash must remain outside the standalone dependency manifest."
    }
}

$starterArtifactsPath = Join-Path $root 'release/starter-artifacts.properties'
if (-not (Test-Path -LiteralPath $starterArtifactsPath)) { throw 'Starter artifact manifest is missing.' }
$starterArtifacts = ConvertFrom-StringData (Get-Content -Raw -LiteralPath $starterArtifactsPath)
foreach ($league in @('ftc', 'frc', 'xrp')) {
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
$lightbotHash = $starterArtifacts['lightbotExampleSha256']
if ($lightbotHash -notmatch '^[0-9a-fA-F]{64}$') {
    throw 'Starter artifact manifest has no valid lightbotExampleSha256.'
}
$lightbotArchive = Join-Path $root "ARES-Analytics/app/src/main/resources/project-templates/ARES-Lightbot-Example-$($release['lightbotExampleVersion']).zip"
if (-not (Test-Path -LiteralPath $lightbotArchive)) { throw "Bundled Lightbot example archive is missing: $lightbotArchive" }
$actualLightbotHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $lightbotArchive).Hash
if ($actualLightbotHash -ne $lightbotHash) {
    throw "Bundled Lightbot example archive hash is $actualLightbotHash, expected $lightbotHash."
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

# Required checks run on the reviewed pull-request or merge-queue tree. Re-running the same suites
# after that tree lands on main wastes runner time and cannot strengthen the already sealed candidate.
$reviewGateWorkflows = @(
    '.github/workflows/analytics-validation.yml',
    '.github/workflows/build-distributions.yml',
    '.github/workflows/codeql.yml',
    '.github/workflows/monorepo-ci.yml',
    '.github/workflows/verify-autos.yml'
)
foreach ($relativePath in $reviewGateWorkflows) {
    $content = Get-Content -Raw -LiteralPath (Join-Path $root $relativePath)
    if ($content -notmatch '(?m)^\s{2}pull_request:\s*$' -or $content -notmatch '(?m)^\s{2}merge_group:\s*$') {
        throw "$relativePath must validate both pull-request and merge-queue trees."
    }
    if ($content -match '(?ms)^\s{2}push:\s*\r?\n\s{4}branches:\s*\[main\]') {
        throw "$relativePath must not rerun reviewed checks after merge to main."
    }
}

foreach ($component in @('ARESLib-Kotlin', 'ARES-FTC', 'ARES-FRC', 'ARES-FTC-Starter', 'ARES-FRC-Starter', 'ARES-Analytics')) {
    if (Test-Path -LiteralPath (Join-Path $root "$component/.git")) {
        throw "$component is still a nested Git repository; source must be owned by the monorepo."
    }
}
$localLauncher = Get-Content -Raw -LiteralPath (Join-Path $root 'ARES-Analytics/scripts/run-local-ares.ps1')
if ($localLauncher -notmatch 'release\\ares-versions\.properties' -or $localLauncher -match 'ARESLib.*gradle\.properties') {
    throw 'The local Studio launcher must derive its candidate base version from the canonical release manifest.'
}

# Pre-rollout source cleanup is intentionally breaking. Keep retired implementation files and the
# old FRC product namespace from returning as accidental compatibility layers.
$retiredProductionTypes = @(
    'ActionLogEnvelope', 'ActionReplaySession', 'AresRobotBuilder', 'ARESController',
    'ControlBarrierFunction', 'DiagnosticRingBuffer', 'FtcMecanumPathFollower',
    'GoBildaMotor', 'GridCostmapInflator', 'LocalTelemetry', 'LogArchivePackager',
    'LQRController', 'NT4BufferPool', 'PathChainer', 'PathOptimizationService',
    'PathSafetyEvaluator', 'ReplayPublisher', 'RobotInputsFrame', 'SensoryReplayRunner',
    'SwerveConstants', 'SwerveRobotDouble', 'TrajectoryGenerator', 'VisionHardware',
    'VisionMeasurementBuffer'
)
$productionSources = Get-ChildItem -LiteralPath $root -Recurse -File |
    Where-Object {
        $_.FullName -match '[\\/]src[\\/]main[\\/]' -and
            $_.Extension -in @('.kt', '.java') -and
            $_.FullName -notmatch '[\\/]build[\\/]'
    }

# Large files are not automatically bad, but crossing four figures makes review ownership and
# regression isolation materially harder. ARES-owned production files must be decomposed before
# they exceed this boundary. Upstream FTC controller sources and generated bindings are excluded.
$maxAresOwnedSourceLines = 1000
$oversizedAresSources = $productionSources |
    Where-Object {
        $_.FullName -notmatch '[\\/]FtcRobotController[\\/]' -and
            $_.FullName -notmatch '[\\/]generated[\\/]'
    } |
    ForEach-Object {
        $lineCount = (Get-Content -LiteralPath $_.FullName | Measure-Object -Line).Lines
        if ($lineCount -gt $maxAresOwnedSourceLines) {
            [PSCustomObject]@{
                Path = $_.FullName.Substring($root.Length + 1)
                Lines = $lineCount
            }
        }
    }
if ($oversizedAresSources) {
    $summary = $oversizedAresSources |
        Sort-Object Lines -Descending |
        ForEach-Object { "$($_.Path) ($($_.Lines) lines)" }
    throw "ARES-owned production files exceed $maxAresOwnedSourceLines lines: $($summary -join ', ')"
}
foreach ($retiredType in $retiredProductionTypes) {
    $matches = $productionSources | Where-Object { $_.BaseName -eq $retiredType }
    if ($matches) {
        throw "Retired production type $retiredType returned: $($matches.FullName -join ', ')"
    }
}

$frcProductSources = Get-ChildItem -LiteralPath (Join-Path $root 'ARES-FRC/src') -Recurse -File |
    Where-Object { $_.Extension -in @('.kt', '.java') }
foreach ($source in $frcProductSources) {
    if ((Get-Content -Raw -LiteralPath $source.FullName) -match '(?m)^\s*package\s+com\.areslib\.frc(?:\.|\s|$)') {
        throw "ARES-FRC product source returned to the retired library namespace: $($source.FullName)"
    }
}

# Operational agent guidance and tester skills are executable contracts. Keep machine-specific paths,
# retired branch names, and the former product title out of the files agents actually follow. Dated
# cycle logs and branding/migration documents intentionally remain historical evidence and are not
# scanned here.
$currentGuidance = @(
    'AGENTS.md',
    'ARESLib-Kotlin/GEMINI.md',
    'ARES-FTC-Starter/AGENTS.md',
    'ARES-FRC-Starter/AGENTS.md',
    '.agents/skills/compose-desktop-tester/SKILL.md',
    '.agents/skills/compose-desktop-tester/references/startup-recovery.md',
    '.agents/skills/compose-desktop-tester/scripts/capture_app.ps1',
    '.agents/skills/compose-desktop-tester/scripts/inspect_app_window.ps1',
    '.agents/skills/compose-desktop-tester/scripts/interact_app.ps1',
    'ARES-Analytics/docs/admin/GOOGLE_CLOUD_OAUTH.md',
    'ARES-Analytics/docs/VALIDATION.md'
)
foreach ($relativePath in $currentGuidance) {
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path)) { throw "Current guidance file is missing: $relativePath" }
    $content = Get-Content -Raw -LiteralPath $path
    if ($content -match 'C:\\Users\\david\\dev\\robotics\\ares') {
        throw "Current guidance contains a developer-specific workspace path: $relativePath"
    }
}

$rootGuide = Get-Content -Raw -LiteralPath (Join-Path $root 'AGENTS.md')
if ($rootGuide -match 'clone all four subprojects' -or $rootGuide -match 'commit directly to `master`') {
    throw 'AGENTS.md describes the pre-monorepo checkout or branch workflow.'
}
if ($rootGuide -match 'visible `ARES Analytics` window') {
    throw 'AGENTS.md uses the retired desktop window title in active launch guidance.'
}

$testerFiles = $currentGuidance | Where-Object { $_ -like '.agents/skills/compose-desktop-tester/*' }
foreach ($relativePath in $testerFiles) {
    $content = Get-Content -Raw -LiteralPath (Join-Path $root $relativePath)
    if ($content -match 'WindowTitle\s+(=|\")?\s*\"ARES Analytics\"') {
        throw "Compose tester uses the retired window title: $relativePath"
    }
}

$geminiGuide = Get-Content -Raw -LiteralPath (Join-Path $root 'ARESLib-Kotlin/GEMINI.md')
if ($geminiGuide -match 'all four repositories' -or $geminiGuide -match 'ARESLib-Kotlin/maven') {
    throw 'ARESLib-Kotlin/GEMINI.md contains a pre-monorepo product count or Maven endpoint.'
}

$oauthGuide = Get-Content -Raw -LiteralPath (Join-Path $root 'ARES-Analytics/docs/admin/GOOGLE_CLOUD_OAUTH.md')
$validationGuide = Get-Content -Raw -LiteralPath (Join-Path $root 'ARES-Analytics/docs/VALIDATION.md')
if ($oauthGuide -match 'from `master`' -or $validationGuide -match 'pushes to `master`') {
    throw 'Current Studio operations documentation names the retired default branch.'
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
