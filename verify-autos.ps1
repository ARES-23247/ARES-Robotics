param(
    [switch]$Full
)

$ErrorActionPreference = "Stop"
$workspaceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

function Invoke-AresGradle {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string[]]$GradleArguments
    )

    $repositoryPath = Join-Path $workspaceRoot $Repository
    $wrapper = Join-Path $repositoryPath "gradlew.bat"
    if (-not (Test-Path -LiteralPath $wrapper)) {
        throw "Missing Gradle wrapper: $wrapper"
    }

    Write-Host "`n==> $Repository $($GradleArguments -join ' ')" -ForegroundColor Cyan
    Push-Location $repositoryPath
    try {
        & $wrapper @GradleArguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Repository verification failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

if ($Full) {
    Invoke-AresGradle "ARESLib-Kotlin" @("test", "--console=plain")
} else {
    Invoke-AresGradle "ARESLib-Kotlin" @(
        ":core:test",
        "--tests", "com.areslib.auto.AutoAuthoringRuntimeAcceptanceTest",
        "--console=plain"
    )
}

# Consumers must see the exact library implementation just verified above.
Invoke-AresGradle "ARESLib-Kotlin" @("publishToMavenLocal", "--console=plain")

if ($Full) {
    Invoke-AresGradle "ARES-Analytics" @(":app:test", "--console=plain")
    Invoke-AresGradle "ARES-FTC" @(
        ":TeamCode:testDebugUnitTest",
        "--no-daemon",
        "-Dorg.gradle.jvmargs=-XX:ReservedCodeCacheSize=512m",
        "--console=plain"
    )
    Invoke-AresGradle "ARES-FRC" @("test", "-Pares.usePublishedLib=true", "--console=plain")
} else {
    Invoke-AresGradle "ARES-Analytics" @(
        ":app:test",
        "--tests", "com.ares.analytics.viewmodel.pathing.AutoAuthoringAcceptanceTest",
        "--console=plain"
    )
    Invoke-AresGradle "ARES-FTC" @(
        ":TeamCode:testDebugUnitTest",
        "--tests", "org.firstinspires.ftc.teamcode.AutoAssetContractTest",
        "--no-daemon",
        "-Dorg.gradle.jvmargs=-XX:ReservedCodeCacheSize=512m",
        "--console=plain"
    )
    Invoke-AresGradle "ARES-FRC" @(
        "test",
        "-Pares.usePublishedLib=true",
        "--tests", "com.areslib.frc.robot.FrcNativeAutoContractTest",
        "--tests", "com.areslib.frc.robot.FRCAutoAllianceMirroringContractTest",
        "--console=plain"
    )
}

Write-Host "`nARES autonomous verification passed." -ForegroundColor Green
