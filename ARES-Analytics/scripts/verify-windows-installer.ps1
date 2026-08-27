param(
    [Parameter(Mandatory = $true)][string]$MsiFile,
    [Parameter(Mandatory = $true)][string]$ExpectedProductName,
    [Parameter(Mandatory = $true)][string]$ExpectedProductVersion,
    [Parameter(Mandatory = $true)][string]$ExpectedUpgradeCode
)

$ErrorActionPreference = 'Stop'
$resolved = (Resolve-Path -LiteralPath $MsiFile).Path
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.GetType().InvokeMember(
    'OpenDatabase',
    'InvokeMethod',
    $null,
    $installer,
    @($resolved, 0)
)

function Read-MsiRows([string]$query, [int]$columnCount) {
    $view = $database.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $database, @($query))
    $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null) | Out-Null
    $rows = @()
    do {
        $record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
        if ($null -ne $record) {
            $row = for ($index = 1; $index -le $columnCount; $index++) {
                $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, $index)
            }
            $rows += ,$row
        }
    } while ($null -ne $record)
    return $rows
}

$properties = @{}
foreach ($row in Read-MsiRows 'SELECT * FROM `Property`' 2) {
    $properties[$row[0]] = $row[1]
}

if ($properties['ProductName'] -ne $ExpectedProductName) {
    throw "MSI ProductName '$($properties['ProductName'])' does not match '$ExpectedProductName'."
}
if ($properties['ProductVersion'] -ne $ExpectedProductVersion) {
    throw "MSI ProductVersion '$($properties['ProductVersion'])' does not match '$ExpectedProductVersion'."
}
if ($properties['UpgradeCode'] -ne $ExpectedUpgradeCode.ToUpperInvariant()) {
    throw "MSI UpgradeCode '$($properties['UpgradeCode'])' changed from compatibility identity '$ExpectedUpgradeCode'."
}
if ($properties['ARPNOREPAIR'] -eq '1') {
    throw 'MSI explicitly disables Windows Installer repair.'
}

$upgradeRows = Read-MsiRows 'SELECT `UpgradeCode`,`VersionMin`,`VersionMax`,`Attributes`,`ActionProperty` FROM `Upgrade`' 5
$upgradeRow = $upgradeRows | Where-Object {
    $_[0] -eq $ExpectedUpgradeCode.ToUpperInvariant() -and
    $_[2] -eq $ExpectedProductVersion -and
    $_[4] -eq 'JP_UPGRADABLE_FOUND'
}
if ($null -eq $upgradeRow) {
    throw 'MSI does not declare an in-place upgrade path from earlier ARES versions.'
}

# Query at least two columns so PowerShell preserves each result as a row array.
# A one-column row is otherwise unwrapped to a String and `$_[0]` becomes only
# its first character (for example, "M" instead of "MaintenanceTypeDlg").
$dialogs = (Read-MsiRows 'SELECT `Dialog`,`HCentering` FROM `Dialog`' 2) | ForEach-Object { $_[0] }
if ('MaintenanceTypeDlg' -notin $dialogs) {
    throw 'MSI is missing the maintenance dialog required for rerun/repair.'
}

$repairControls = Read-MsiRows "SELECT `Control`,`Type`,`Text` FROM `Control` WHERE `Dialog_`='MaintenanceTypeDlg'" 3
$repairButton = $repairControls | Where-Object { $_[0] -eq 'RepairButton' -and $_[1] -eq 'PushButton' }
if ($null -eq $repairButton -or $repairButton[2] -notmatch 'pair') {
    throw 'MSI maintenance dialog does not expose an enabled Repair button.'
}

Write-Output "WINDOWS_INSTALLER_MAINTENANCE_OK product='$ExpectedProductName' version=$ExpectedProductVersion"
