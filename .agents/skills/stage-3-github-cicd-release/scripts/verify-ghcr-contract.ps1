[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../../..")).Path
)

$ErrorActionPreference = 'Stop'
$workflow = Join-Path $RepositoryRoot '.github/workflows/ragent-ci-release.yml'
if (-not (Test-Path $workflow)) { throw "Missing workflow: $workflow" }

$text = Get-Content -Raw -Path $workflow
$mustContain = @(
    'arugsragent-api',
    'arugsragent-web',
    '${{ github.sha }}',
    'GITHUB_REPOSITORY_OWNER,,',
    'org.opencontainers.image.source',
    'org.opencontainers.image.revision'
)
$missing = @($mustContain | Where-Object { $text -notmatch [regex]::Escape($_) })
if ($missing.Count -gt 0) { throw "GHCR contract missing:`n - $($missing -join "`n - ")" }

$forbidden = @('latest', ':master', 'refs/heads/master:', 'github.sha::')
$found = @($forbidden | Where-Object { $text -match [regex]::Escape($_) })
if ($found.Count -gt 0) { throw "Mutable or malformed tag fragments found:`n - $($found -join "`n - ")" }

Write-Host "PASS: GHCR image contract uses API/Web package names, lowercase owner resolution, and full SHA tags."
