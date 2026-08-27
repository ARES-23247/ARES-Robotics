[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$releasePropertiesPath = Join-Path $workspaceRoot 'release/ares-versions.properties'
$releaseProperties = [ordered]@{}
Get-Content -LiteralPath $releasePropertiesPath | ForEach-Object {
    if ($_ -match '^\s*([^#=][^=]*)=(.*)$') {
        $releaseProperties[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$versions = @{
    'ARES-FTC-Starter' = $releaseProperties['ftcStarterVersion']
    'ARES-FRC-Starter' = $releaseProperties['frcStarterVersion']
}
if ($versions.Values | Where-Object { -not $_ -or $_ -notmatch '^\d+\.\d+\.\d+$' }) {
    throw 'Starter archive versions must be explicit MAJOR.MINOR.PATCH values.'
}

$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) "ares-starter-archives-$([guid]::NewGuid())"
$mirrorRoot = Join-Path $temporaryRoot 'mirrors'
$fixedTimestamp = [DateTimeOffset]::new(2000, 1, 1, 0, 0, 0, [TimeSpan]::Zero)

try {
    & (Join-Path $PSScriptRoot 'export-starter-mirrors.ps1') -OutputRoot $mirrorRoot

    Add-Type -AssemblyName System.IO.Compression
    foreach ($templateName in $versions.Keys | Sort-Object) {
        $version = $versions[$templateName]
        $source = Join-Path $mirrorRoot $templateName
        $archive = Join-Path $outputPath "$templateName-$version.zip"
        if (Test-Path -LiteralPath $archive) {
            throw "Refusing to replace existing starter archive: $archive"
        }

        $fileStream = [System.IO.File]::Open(
            $archive,
            [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
        try {
            $zip = [System.IO.Compression.ZipArchive]::new(
                $fileStream,
                [System.IO.Compression.ZipArchiveMode]::Create,
                $false
            )
            try {
                Get-ChildItem -LiteralPath $source -Recurse -File |
                    Where-Object Name -ne '.ares-starter-mirror.json' |
                    Sort-Object { [System.IO.Path]::GetRelativePath($source, $_.FullName).Replace('\', '/') } |
                    ForEach-Object {
                        $relative = [System.IO.Path]::GetRelativePath($source, $_.FullName).Replace('\', '/')
                        $entryName = "$templateName-$version/$relative"
                        # NoCompression is intentional. Deflate output is allowed to vary between
                        # .NET runtime/zlib implementations even when file order and timestamps are
                        # fixed, which made the release SHA machine-dependent. Stored ZIP entries
                        # make the complete archive byte-for-byte reproducible across supported
                        # Windows build agents.
                        $entry = $zip.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::NoCompression)
                        $entry.LastWriteTime = $fixedTimestamp
                        $isExecutable = $_.Name -eq 'gradlew' -or $_.Extension -eq '.sh'
                        $unixMode = if ($isExecutable) { 0x81ED } else { 0x81A4 } # 100755 / 100644
                        $entry.ExternalAttributes = $unixMode -shl 16
                        $input = [System.IO.File]::OpenRead($_.FullName)
                        $output = $entry.Open()
                        try {
                            $input.CopyTo($output)
                        } finally {
                            $output.Dispose()
                            $input.Dispose()
                        }
                    }
            } finally {
                $zip.Dispose()
            }
        } finally {
            $fileStream.Dispose()
        }
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
        Write-Host "$templateName $version $hash" -ForegroundColor Green
    }
} finally {
    $resolvedTemp = [System.IO.Path]::GetFullPath($temporaryRoot)
    $systemTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolvedTemp.StartsWith($systemTemp, [System.StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
