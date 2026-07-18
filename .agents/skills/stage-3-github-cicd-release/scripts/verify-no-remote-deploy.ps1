[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../../..")).Path
)

$ErrorActionPreference = 'Stop'
$workflow = Join-Path $RepositoryRoot '.github/workflows/ragent-ci-release.yml'
if (-not (Test-Path $workflow)) { throw "Missing workflow: $workflow" }

$text = Get-Content -Raw -Path $workflow
$forbiddenPatterns = @(
    'appleboy/ssh-action',
    'ssh ',
    'scp ',
    'rsync ',
    'docker context',
    'DEPLOY_HOST',
    'DEPLOY_PORT',
    'DEPLOY_USER',
    'DEPLOY_SSH_PRIVATE_KEY',
    '/opt/ragent/bin/deploy-release.sh',
    'tencentcloud',
    'tccli'
)

$hits = @($forbiddenPatterns | Where-Object { $text -match [regex]::Escape($_) })
if ($hits.Count -gt 0) { throw "Workflow contains forbidden first-release remote deployment fragments:`n - $($hits -join "`n - ")" }

Write-Host "PASS: workflow has no SSH, server deployment, remote Docker, or Tencent Cloud action."
