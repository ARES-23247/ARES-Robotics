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

    $effectiveArguments = @($GradleArguments)
    if ($effectiveArguments -notcontains "--no-parallel") {
        $effectiveArguments += "--no-parallel"
    }
    if ($effectiveArguments -notcontains "--no-daemon") {
        $effectiveArguments += "--no-daemon"
    }

    Write-Host "`n==> $Repository $($effectiveArguments -join ' ')" -ForegroundColor Cyan
    Push-Location $repositoryPath
    try {
        & $wrapper @effectiveArguments
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
        "--tests", "com.areslib.routine.RoutineDocumentTest",
        "--tests", "com.areslib.routine.RoutineManagerTest",
        "--tests", "com.areslib.routine.AutonomousCatalogTest",
        "--tests", "com.areslib.controls.ControlSchemeValidationTest",
        "--tests", "com.areslib.controls.ControllerProfileDocumentTest",
        "--tests", "com.areslib.input.DigitalBindingTest",
        "--tests", "com.areslib.input.AnalogBindingTest",
        "--tests", "com.areslib.input.ButtonSuppressionTest",
        "--tests", "com.areslib.codegen.AresKotlinProjectGeneratorTest",
        "--tests", "com.areslib.codegen.AresProjectCodegenCliTest",
        "--console=plain"
    )
    Invoke-AresGradle "ARESLib-Kotlin" @(
        ":ftc-hardware:test",
        "--tests", "com.areslib.ftc.input.FtcInputFrameAdapterTest",
        "--console=plain"
    )
    Invoke-AresGradle "ARESLib-Kotlin" @(
        ":frc-hardware:test",
        "--tests", "com.areslib.frc.input.FrcInputFrameAdapterTest",
        "--console=plain"
    )
}

# Consumers must see the exact library implementation just verified above.
Invoke-AresGradle "ARESLib-Kotlin" @("publishToMavenLocal", "--console=plain")

if ($Full) {
    Invoke-AresGradle "ARES-Analytics" @(":app:test", "-ParesUseSiblingLib=true", "--console=plain")
    Invoke-AresGradle "ARES-FTC" @(":TeamCode:verifyAresProject", "--console=plain")
    Invoke-AresGradle "ARES-FTC" @(
        ":TeamCode:testDebugUnitTest",
        "--console=plain"
    )
    Invoke-AresGradle "ARES-FRC" @("verifyAresProject", "-Pares.usePublishedLib=true", "--console=plain")
    Invoke-AresGradle "ARES-FRC" @("test", "-Pares.usePublishedLib=true", "--console=plain")
} else {
    Invoke-AresGradle "ARES-Analytics" @(
        ":app:test",
        "-ParesUseSiblingLib=true",
        "--tests", "com.ares.analytics.viewmodel.project.ProjectDocumentRepositoriesTest",
        "--tests", "com.ares.analytics.viewmodel.routine.RoutineEditorModelTest",
        "--tests", "com.ares.analytics.viewmodel.controls.ControlsEditorViewModelTest",
        "--console=plain"
    )
    Invoke-AresGradle "ARES-FTC" @(":TeamCode:verifyAresProject", "--console=plain")
    Invoke-AresGradle "ARES-FTC" @(
        ":TeamCode:testDebugUnitTest",
        "--tests", "org.firstinspires.ftc.teamcode.FtcFieldAssetContractTest",
        "--tests", "org.firstinspires.ftc.teamcode.FtcAutoLifecycleTest",
        "--tests", "org.firstinspires.ftc.teamcode.FtcGeneratedRuntimeTest",
        "--tests", "org.firstinspires.ftc.teamcode.FtcAutonomousSelectorTest",
        "--console=plain"
    )
    Invoke-AresGradle "ARES-FRC" @("verifyAresProject", "-Pares.usePublishedLib=true", "--console=plain")
    Invoke-AresGradle "ARES-FRC" @(
        "test",
        "-Pares.usePublishedLib=true",
        "--tests", "com.areslib.frc.robot.FrcNativeAutoContractTest",
        "--tests", "com.areslib.frc.robot.FRCAutoAllianceMirroringContractTest",
        "--tests", "com.areslib.frc.generatedruntime.FrcGeneratedRoutineRuntimeTest",
        "--console=plain"
    )
}

Write-Host "`nARES routines, controls, code generation, and autonomous verification passed." -ForegroundColor Green
