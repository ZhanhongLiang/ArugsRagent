[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../../..")).Path
)

$ErrorActionPreference = 'Stop'
Push-Location $RepositoryRoot
try {
    if (-not (Test-Path '.git')) {
        Write-Warning 'Not a Git checkout; cannot summarize changed files.'
        exit 0
    }
    $status = git status --short
    if (-not $status) {
        Write-Host 'No uncommitted changes.'
        exit 0
    }
    Write-Host 'Changed files:'
    $status
} finally {
    Pop-Location
}
