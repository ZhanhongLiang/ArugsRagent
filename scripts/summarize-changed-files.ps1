param(
    [string]$Root = "."
)

$ErrorActionPreference = "Stop"
Push-Location $Root
try {
    Write-Host "==== Git status ===="
    git status --short

    Write-Host ""
    Write-Host "==== Changed files ===="
    git diff --name-only

    Write-Host ""
    Write-Host "==== Diff stat ===="
    git diff --stat

    Write-Host ""
    Write-Host "==== Staged diff stat ===="
    git diff --cached --stat

    Write-Host ""
    Write-Host "==== Recent changed lines mentioning Argus/Ragent/ragent/nageoffer ===="
    $diff = git diff --unified=0
    $diff -split "`n" | Where-Object {
        $_ -match "^\+" -and $_ -notmatch "^\+\+\+" -and ($_ -match "Argus|Ragent|ragent|nageoffer")
    } | Select-Object -First 200
}
finally {
    Pop-Location
}
