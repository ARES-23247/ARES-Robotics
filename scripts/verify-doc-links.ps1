[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$historical = @('ARESLib-Kotlin/audit_report_areslib_kotlin.md', 'CLEAN_SLATE_ARCHITECTURE_COMPLETION.md')
$historicalPrefixes = @('.planning/', 'ARES-Analytics/docs/cycles/', 'ARES-Analytics/reports/')
$tracked = git -C $root ls-files '*.md'
if ($LASTEXITCODE -ne 0) { throw 'Unable to enumerate tracked Markdown files.' }
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
    $insideFence = $false
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $file) {
        $lineNumber++
        if ($line -match '^\s*(```|~~~)') { $insideFence = -not $insideFence; continue }
        if ($insideFence) { continue }
        $withoutInlineCode = [regex]::Replace($line, '`[^`]*`', '')
        foreach ($match in [regex]::Matches($withoutInlineCode, '!??\[[^\]]*\]\((?<target>[^)]+)\)')) {
            $target = $match.Groups['target'].Value.Trim()
            if ($target.StartsWith('<') -and $target.EndsWith('>')) { $target = $target.Substring(1, $target.Length - 2) }
            if ($target -match '^([^\s]+)\s+["''].*["'']$') { $target = $matches[1] }
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
