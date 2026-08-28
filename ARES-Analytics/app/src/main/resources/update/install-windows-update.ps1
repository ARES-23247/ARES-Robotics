param(
    [Parameter(Mandatory = $true)][string]$MsiPath,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ExpectedSha256,
    [Parameter(Mandatory = $false)][ValidatePattern('^[A-Fa-f0-9]{40,128}$')][string]$ExpectedSignerThumbprint = '',
    [Parameter(Mandatory = $true)][long]$ParentPid,
    [Parameter(Mandatory = $true)][string]$RelaunchPath,
    [Parameter(Mandatory = $true)][string]$ResultFile
)

$ErrorActionPreference = 'Stop'

function Write-UpdateResult {
    param([string]$Status, [int]$ExitCode, [string]$Message)
    $temporary = "$ResultFile.tmp"
    @{
        schemaVersion = 1
        status = $Status
        exitCode = $ExitCode
        message = $Message
        completedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Compress | Set-Content -LiteralPath $temporary -Encoding UTF8
    Move-Item -LiteralPath $temporary -Destination $ResultFile -Force
}

try {
    if (-not [IO.Path]::IsPathFullyQualified($MsiPath) -or -not (Test-Path -LiteralPath $MsiPath -PathType Leaf)) {
        throw 'The staged MSI path is invalid.'
    }
    if (-not [IO.Path]::IsPathFullyQualified($RelaunchPath) -or -not (Test-Path -LiteralPath $RelaunchPath -PathType Leaf)) {
        throw 'The installed application path is invalid.'
    }

    $parent = Get-Process -Id $ParentPid -ErrorAction SilentlyContinue
    if ($null -ne $parent) {
        Wait-Process -Id $ParentPid -Timeout 120 -ErrorAction Stop
    }

    $actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $MsiPath).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $ExpectedSha256.ToLowerInvariant()) {
        throw 'The staged MSI digest changed before installation.'
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedSignerThumbprint)) {
        $signature = Get-AuthenticodeSignature -LiteralPath $MsiPath
        $actualThumbprint = if ($null -ne $signature.SignerCertificate) {
            ($signature.SignerCertificate.Thumbprint -replace '[^A-Fa-f0-9]', '').ToUpperInvariant()
        } else { '' }
        $expectedThumbprint = ($ExpectedSignerThumbprint -replace '[^A-Fa-f0-9]', '').ToUpperInvariant()
        if ($signature.Status -ne 'Valid' -or $actualThumbprint -ne $expectedThumbprint) {
            throw 'The staged MSI Authenticode signature is no longer trusted.'
        }
    }

    $installer = Start-Process -FilePath 'msiexec.exe' -ArgumentList @(
        '/i', ('"' + $MsiPath + '"'), '/passive', '/norestart'
    ) -Wait -PassThru
    if ($installer.ExitCode -notin @(0, 3010)) {
        Write-UpdateResult -Status 'failed' -ExitCode $installer.ExitCode -Message 'Windows Installer did not complete successfully.'
        exit $installer.ExitCode
    }

    $status = if ($installer.ExitCode -eq 3010) { 'restart-required' } else { 'succeeded' }
    Write-UpdateResult -Status $status -ExitCode $installer.ExitCode -Message 'The update was installed.'
    Start-Process -FilePath $RelaunchPath -WorkingDirectory (Split-Path -Parent $RelaunchPath)
    exit 0
} catch {
    Write-UpdateResult -Status 'recovery-required' -ExitCode 1 -Message $_.Exception.Message
    exit 1
}
