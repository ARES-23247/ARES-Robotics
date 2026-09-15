[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$root = Split-Path -Parent $PSScriptRoot
$historical = @('ARESLib-Kotlin/audit_report_areslib_kotlin.md', 'CLEAN_SLATE_ARCHITECTURE_COMPLETION.md')
$historicalPrefixes = @('.planning/', 'ARES-Analytics/docs/cycles/', 'ARES-Analytics/reports/')
$trackedOutput = @(git -C $root ls-files -z -- '*.md')
if ($LASTEXITCODE -ne 0) { throw 'Unable to enumerate tracked Markdown files.' }
$tracked = ($trackedOutput -join "`n").Split([char[]]@([char]0), [System.StringSplitOptions]::RemoveEmptyEntries)
$fencePattern = [regex]::new('^ {0,3}(?<fence>`{3,}|~{3,})(?<info>.*)$')
$errors = [System.Collections.Generic.List[string]]::new()
$checked = 0
$skipped = 0
foreach ($relativePath in $tracked) {
    $normalized = $relativePath.Replace('\', '/')
    if ($historical -contains $normalized -or $normalized.Contains('/.planning/') -or ($historicalPrefixes | Where-Object { $normalized.StartsWith($_) })) {
        $skipped++
        continue
    }
    $file = Join-Path $root $relativePath
    $directory = Split-Path -Parent $file
    $fenceMarker = $null
    $fenceLength = 0
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $file -Encoding UTF8) {
        $lineNumber++
        $fenceMatch = $fencePattern.Match($line)
        if ($fenceLength -gt 0) {
            if ($fenceMatch.Success) {
                $candidateFence = $fenceMatch.Groups['fence'].Value
                if ($candidateFence[0] -eq $fenceMarker -and $candidateFence.Length -ge $fenceLength -and
                    $fenceMatch.Groups['info'].Value -match '^[ \t]*$') { $fenceLength = 0 }
            }
            continue
        }
        if ($fenceMatch.Success) {
            $candidateFence = $fenceMatch.Groups['fence'].Value
            if ($candidateFence[0] -ne [char]96 -or -not $fenceMatch.Groups['info'].Value.Contains('`')) {
                $fenceMarker = $candidateFence[0]
                $fenceLength = $candidateFence.Length
                continue
            }
        }
        if (-not $line.Contains('](')) { continue }
        $withoutInlineCode = [regex]::Replace($line, '`[^`]*`', '')
        foreach ($match in [regex]::Matches($withoutInlineCode, '!??\[[^\]]*\]\((?<target>[^)]+)\)')) {
            $target = $match.Groups['target'].Value.Trim()
            if ($target -match '^<(?<path>[^<>]*)>(?:[ \t]+(?:"[^"]*"|''[^'']*''))?$') { $target = $matches['path'] }
            elseif ($target -match '^([^\s]+)\s+["''].*["'']$') { $target = $matches[1] }
            if ($target -match '^(https?://|mailto:|#|chatgpt-conversation:|skill:|app:)') { continue }
            if ($target -match '^file:') { $errors.Add("$normalized`:$lineNumber uses a machine-local file URL: $target"); continue }
            $target = ($target -split '[?#]', 2)[0]
            if ([string]::IsNullOrWhiteSpace($target) -or $target -match '[{}<>*]') { continue }
            try { $target = [Uri]::UnescapeDataString($target) } catch { }
            $resolved = [System.IO.Path]::GetFullPath((Join-Path $directory $target))
            if (-not (Test-Path -LiteralPath $resolved)) { $errors.Add("$normalized`:$lineNumber points to missing local target: $target") }
        }
    }
    $checked++
}
if ($errors.Count -gt 0) { throw "Markdown link verification failed:`n$($errors -join "`n")" }
Write-Host "Verified local Markdown links in $checked current documents; skipped $skipped explicitly historical records." -ForegroundColor Green
