param(
    [string]$WebsiteRoot = (Join-Path $PSScriptRoot "..\..\ARESWEB")
)

$ErrorActionPreference = "Stop"

$source = Join-Path $WebsiteRoot "design\ares-design-tokens.json"
$destination = Join-Path $PSScriptRoot "..\app\src\main\resources\design\ares-design-tokens.json"

if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
    throw "ARESWEB design-token contract was not found at $source. Pass -WebsiteRoot explicitly."
}

$document = Get-Content -LiteralPath $source -Raw | ConvertFrom-Json
if ($document.schemaVersion -ne 1 -or -not $document.brand -or -not $document.semanticDark) {
    throw "The ARESWEB design-token contract is missing the supported schemaVersion 1 structure."
}

Copy-Item -LiteralPath $source -Destination $destination -Force
Write-Host "Synchronized ARES design-token snapshot to $destination"
Write-Host "Run .\gradlew.bat :app:test --tests 'com.ares.analytics.ui.theme.*' before committing."
