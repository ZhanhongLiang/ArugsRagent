param(
    [string]$Root = ".",
    [switch]$SkipTests = $true
)

$ErrorActionPreference = "Stop"
Push-Location $Root
try {
    $mvnCmd = $null
    $mvnArgs = @()

    if (Test-Path ".\mvnw.cmd") {
        $mvnCmd = ".\mvnw.cmd"
    }
    elseif (Test-Path ".\mvnw") {
        $mvnCmd = "./mvnw"
    }
    else {
        $mvnCmd = "mvn"
    }

    if ($SkipTests) {
        $mvnArgs = @("-DskipTests", "clean", "compile")
    }
    else {
        $mvnArgs = @("clean", "test")
    }

    Write-Host "Running backend verification: $mvnCmd $($mvnArgs -join ' ')"
    & $mvnCmd @mvnArgs
}
finally {
    Pop-Location
}
