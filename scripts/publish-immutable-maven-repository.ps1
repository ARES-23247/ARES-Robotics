[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$StagedRepository,
    [Parameter(Mandatory = $true)]
    [string]$RepositoryRoot,
    [string]$Branch = 'maven',
    [switch]$Push
)

$ErrorActionPreference = 'Stop'
$staged = [System.IO.Path]::GetFullPath($StagedRepository)
$sourceRepository = [System.IO.Path]::GetFullPath($RepositoryRoot)
if (-not (Test-Path -LiteralPath $staged -PathType Container)) {
    throw "Staged Maven repository does not exist: $staged"
}
if (-not (Test-Path -LiteralPath (Join-Path $sourceRepository '.git'))) {
    throw "RepositoryRoot is not a Git checkout: $sourceRepository"
}
$stagedFiles = @(Get-ChildItem -LiteralPath $staged -Recurse -File)
if ($stagedFiles.Count -eq 0) {
    throw "Staged Maven repository contains no files: $staged"
}

$temporary = Join-Path ([System.IO.Path]::GetTempPath()) "ares-maven-publish-$([guid]::NewGuid())"
try {
    $upstream = (git -C $sourceRepository remote get-url origin).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($upstream)) {
        throw 'RepositoryRoot has no origin remote.'
    }
    git clone --no-checkout --quiet $sourceRepository $temporary
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create the isolated Maven publication clone.' }
    git -C $temporary config core.autocrlf false
    git -C $temporary remote set-url origin $upstream
    $githubExtraHeader = (git -C $sourceRepository config --get http.https://github.com/.extraheader 2>$null)
    if ($githubExtraHeader) {
        git -C $temporary config http.https://github.com/.extraheader $githubExtraHeader
    }

    git -C $temporary fetch --quiet origin $Branch
    if ($LASTEXITCODE -eq 0) {
        git -C $temporary checkout --quiet -B $Branch "origin/$Branch"
    } else {
        git -C $temporary checkout --quiet --orphan $Branch
        git -C $temporary rm -r --cached --ignore-unmatch . | Out-Null
        $resolvedTemporary = [System.IO.Path]::GetFullPath($temporary)
        $resolvedSystemTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        if (-not $resolvedTemporary.StartsWith($resolvedSystemTemp, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to clear an orphan-branch worktree outside the system temporary directory.'
        }
        Get-ChildItem -LiteralPath $temporary -Force |
            Where-Object Name -ne '.git' |
            Remove-Item -Recurse -Force
    }
    if ($LASTEXITCODE -ne 0) { throw "Unable to prepare the $Branch branch." }

    $conflicts = [System.Collections.Generic.List[string]]::new()
    $stagedFiles | ForEach-Object {
        $relative = [System.IO.Path]::GetRelativePath($staged, $_.FullName)
        $target = Join-Path $temporary $relative
        if (Test-Path -LiteralPath $target) {
            $fileName = [System.IO.Path]::GetFileName($relative)
            if ($fileName.StartsWith('maven-metadata', [System.StringComparison]::Ordinal)) {
                Copy-Item -LiteralPath $_.FullName -Destination $target -Force
                return
            }
            $incoming = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash
            $existing = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash
            if ($incoming -ne $existing) { $conflicts.Add($relative.Replace('\', '/')) }
        } else {
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
            Copy-Item -LiteralPath $_.FullName -Destination $target
        }
    }
    if ($conflicts.Count -gt 0) {
        throw "Immutable Maven content differs from the published branch:`n$($conflicts -join "`n")"
    }

    git -C $temporary add --all
    $changes = git -C $temporary status --porcelain
    if (-not $changes) {
        Write-Host "Maven branch already contains every staged byte." -ForegroundColor Green
        return
    }
    git -C $temporary -c user.name='github-actions[bot]' -c user.email='41898282+github-actions[bot]@users.noreply.github.com' commit --quiet -m 'release: publish immutable ARES Maven artifacts'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to commit the staged Maven artifacts.' }
    if ($Push) {
        git -C $temporary push origin "HEAD:refs/heads/$Branch"
        if ($LASTEXITCODE -ne 0) { throw "Unable to publish the $Branch branch." }
    } else {
        $changeCount = @($changes).Count
        Write-Host "Dry run passed; immutable Maven content would add or update $changeCount files." -ForegroundColor Green
    }
} finally {
    $resolved = [System.IO.Path]::GetFullPath($temporary)
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolved.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}
