param(
    [Parameter(Mandatory = $true)][string]$PreviousMsi,
    [Parameter(Mandatory = $true)][string]$CurrentMsi,
    [Parameter(Mandatory = $true)][string]$ExpectedProductName,
    [Parameter(Mandatory = $true)][string]$PreviousVersion,
    [Parameter(Mandatory = $true)][string]$CurrentVersion,
    [Parameter(Mandatory = $true)][string]$ExpectedUpgradeCode
)

$ErrorActionPreference = 'Stop'
$previousPath = (Resolve-Path -LiteralPath $PreviousMsi).Path
$currentPath = (Resolve-Path -LiteralPath $CurrentMsi).Path
$expectedUpgradeCodeNormalized = $ExpectedUpgradeCode.ToUpperInvariant()
$previousSemanticVersion = [version]$PreviousVersion
$currentSemanticVersion = [version]$CurrentVersion
$transactionTempDirectory = if ([string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) {
    [IO.Path]::GetTempPath()
} else {
    $env:RUNNER_TEMP
}

if ($currentSemanticVersion -le $previousSemanticVersion) {
    throw "Current MSI version $CurrentVersion must be newer than previous version $PreviousVersion."
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'The real MSI upgrade transaction must run from an elevated Windows process.'
}

$installer = New-Object -ComObject WindowsInstaller.Installer

function Read-MsiProperty([string]$path, [string]$propertyName) {
    $database = $installer.GetType().InvokeMember(
        'OpenDatabase',
        'InvokeMethod',
        $null,
        $installer,
        @($path, 0)
    )
    $query = "SELECT ``Value`` FROM ``Property`` WHERE ``Property``='$propertyName'"
    $view = $database.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $database, @($query))
    $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null) | Out-Null
    $record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
    if ($null -eq $record) { return $null }
    return $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, 1)
}

function Get-InstalledProducts {
    $roots = @(
        'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*'
    )
    $products = foreach ($root in $roots) {
        Get-ItemProperty -Path $root -ErrorAction SilentlyContinue |
            Where-Object {
                $_.WindowsInstaller -eq 1 -and
                $_.DisplayName -eq $ExpectedProductName -and
                $_.PSChildName -match '^\{[0-9A-Fa-f-]{36}\}$'
            } |
            ForEach-Object {
                [pscustomobject]@{
                    ProductCode = $_.PSChildName.ToUpperInvariant()
                    Version = [string]$_.DisplayVersion
                }
            }
    }
    return @($products | Sort-Object ProductCode -Unique)
}

function Invoke-Msi([string]$operation, [string]$target, [string]$logName, [string]$properties = '') {
    $logPath = Join-Path $transactionTempDirectory $logName
    $arguments = "$operation `"$target`" /qn /norestart /L*v `"$logPath`" $properties".Trim()
    $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -notin @(0, 1641, 3010)) {
        $tail = if (Test-Path -LiteralPath $logPath) {
            (Get-Content -LiteralPath $logPath -Tail 80) -join [Environment]::NewLine
        } else {
            '<no MSI log was created>'
        }
        throw "msiexec $operation failed with exit code $($process.ExitCode). Log: $logPath`n$tail"
    }
}

function Assert-SingleInstalledVersion([string]$expectedVersion, [string]$expectedProductCode) {
    $installed = @(Get-InstalledProducts)
    if ($installed.Count -ne 1) {
        $summary = ($installed | ForEach-Object { "$($_.ProductCode)@$($_.Version)" }) -join ', '
        throw "Expected exactly one installed '$ExpectedProductName' product, found $($installed.Count): $summary"
    }
    if ($installed[0].Version -ne $expectedVersion) {
        throw "Installed version '$($installed[0].Version)' does not match '$expectedVersion'."
    }
    if ($installed[0].ProductCode -ne $expectedProductCode) {
        throw "Installed product code '$($installed[0].ProductCode)' does not match '$expectedProductCode'."
    }
    return $installed[0]
}

$previousProductName = Read-MsiProperty $previousPath 'ProductName'
$currentProductName = Read-MsiProperty $currentPath 'ProductName'
$previousMsiVersion = Read-MsiProperty $previousPath 'ProductVersion'
$currentMsiVersion = Read-MsiProperty $currentPath 'ProductVersion'
$previousUpgradeCode = (Read-MsiProperty $previousPath 'UpgradeCode').ToUpperInvariant()
$currentUpgradeCode = (Read-MsiProperty $currentPath 'UpgradeCode').ToUpperInvariant()
$previousProductCode = (Read-MsiProperty $previousPath 'ProductCode').ToUpperInvariant()
$currentProductCode = (Read-MsiProperty $currentPath 'ProductCode').ToUpperInvariant()

if ($previousProductName -ne $ExpectedProductName -or $currentProductName -ne $ExpectedProductName) {
    throw "Both MSIs must identify as '$ExpectedProductName'."
}
if ($previousMsiVersion -ne $PreviousVersion -or $currentMsiVersion -ne $CurrentVersion) {
    throw "MSI versions do not match the requested transaction ($previousMsiVersion -> $currentMsiVersion)."
}
if ($previousUpgradeCode -ne $expectedUpgradeCodeNormalized -or $currentUpgradeCode -ne $expectedUpgradeCodeNormalized) {
    throw "Both MSIs must retain upgrade code $expectedUpgradeCodeNormalized."
}
if ($previousProductCode -eq $currentProductCode) {
    throw 'Different release versions must have different MSI product codes.'
}

$preexisting = @(Get-InstalledProducts)
if ($preexisting.Count -ne 0) {
    $summary = ($preexisting | ForEach-Object { "$($_.ProductCode)@$($_.Version)" }) -join ', '
    throw "Upgrade verification requires a clean runner; found: $summary"
}

$primaryFailure = $null
try {
    Invoke-Msi '/i' $previousPath 'ares-previous-install.log'
    Assert-SingleInstalledVersion $PreviousVersion $previousProductCode | Out-Null

    Invoke-Msi '/i' $currentPath 'ares-current-upgrade.log'
    Assert-SingleInstalledVersion $CurrentVersion $currentProductCode | Out-Null

    Invoke-Msi '/i' $currentPath 'ares-current-repair.log' 'REINSTALL=ALL REINSTALLMODE=vomus'
    Assert-SingleInstalledVersion $CurrentVersion $currentProductCode | Out-Null
} catch {
    $primaryFailure = $_
} finally {
    $cleanupFailures = @()
    foreach ($product in @(Get-InstalledProducts)) {
        try {
            Invoke-Msi '/x' $product.ProductCode "ares-cleanup-$($product.ProductCode.Trim('{}')).log"
        } catch {
            $cleanupFailures += $_
        }
    }
    $remaining = @(Get-InstalledProducts)
    if ($remaining.Count -ne 0) {
        $cleanupFailures += "Cleanup left $($remaining.Count) installed ARES product(s)."
    }
    if ($null -ne $primaryFailure) {
        foreach ($failure in $cleanupFailures) { Write-Warning $failure }
        throw $primaryFailure
    }
    if ($cleanupFailures.Count -ne 0) {
        throw ($cleanupFailures -join [Environment]::NewLine)
    }
}

Write-Output "WINDOWS_INSTALLER_UPGRADE_OK previous=$PreviousVersion current=$CurrentVersion repair=ok cleanup=ok"
