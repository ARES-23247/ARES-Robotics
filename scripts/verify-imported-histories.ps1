[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$imports = @(
    @{ Path = 'ARESLib-Kotlin'; Import = 'f11d9cb38232e209d412944477ceb887149e3851'; Source = '13599358be7fb87d44e9804e798c7f27c6e84b21' },
    @{ Path = 'ARES-FTC'; Import = '03e2bdf25086e1ca1ee5613b075e81b51e3dadc4'; Source = '0cb74896a60fd7a3b46bb70ca04440aaac91fc6a' },
    @{ Path = 'ARES-FRC'; Import = 'ed3b5575046d08612464b6d91ecd3468a454d924'; Source = '98e2ab05e3a864e9738e9eb56965997412f8b581' },
    @{ Path = 'ARES-FTC-Starter'; Import = '4b27c2ee501afffb5155e200183d1a2ea3794ae6'; Source = '8db9f3651cea58cc0af038919f9b2b1f48fb67e3' },
    @{ Path = 'ARES-FRC-Starter'; Import = '17411eceaeac6e88ab4ed5b08f5f4c6f1c298005'; Source = 'eab3a8db8a19f7f9153a6eaa12192f78da673c8a' },
    @{ Path = 'ARES-Analytics'; Import = '242fa8ab176bff1e4a3951b44d76fd3ef6425f25'; Source = '09e00086c3d0ec29bcd2c11f6805c35d42e7267d' }
)

Push-Location -LiteralPath $root
try {
    foreach ($entry in $imports) {
        git cat-file -e "$($entry.Import)^{commit}"
        if ($LASTEXITCODE -ne 0) { throw "Missing import commit $($entry.Import)." }
        git cat-file -e "$($entry.Source)^{commit}"
        if ($LASTEXITCODE -ne 0) { throw "Missing source history $($entry.Source)." }
        git merge-base --is-ancestor $entry.Source HEAD
        if ($LASTEXITCODE -ne 0) { throw "$($entry.Path) source history is not an ancestor of HEAD." }

        $importedTree = (git rev-parse "$($entry.Import):$($entry.Path)").Trim()
        $sourceTree = (git rev-parse "$($entry.Source)^{tree}").Trim()
        if ($importedTree -ne $sourceTree) {
            throw "$($entry.Path) import tree differs from its recorded source commit."
        }
        Write-Host "verified $($entry.Path) history and import tree" -ForegroundColor Green
    }
} finally {
    Pop-Location
}
