[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../../..")).Path
)

$ErrorActionPreference = 'Stop'
$workflow = Join-Path $RepositoryRoot '.github/workflows/ragent-ci-release.yml'
if (-not (Test-Path $workflow)) { throw "Missing workflow: $workflow" }

$text = Get-Content -Raw -Path $workflow
$required = @(
    'pull_request:',
    'branches: [master]',
    'push:',
    'workflow_dispatch:',
    'actions/checkout@v4',
    'actions/setup-java@v4',
    'java-version: ''17''',
    'actions/setup-node@v4',
    'node-version: ''20''',
    './mvnw',
    'npm ci',
    'npm run build',
    'docker compose',
    'deploy/compose/compose.runtime.yaml',
    'docker/build-push-action@v6',
    'docker/login-action@v3',
    'packages: write',
    'deploy/docker/Dockerfile.api',
    'deploy/docker/Dockerfile.web',
    'github.sha'
)

$missing = @($required | Where-Object { $text -notmatch [regex]::Escape($_) })
if ($missing.Count -gt 0) { throw "Workflow missing required fragments:`n - $($missing -join "`n - ")" }

Write-Host "PASS: release workflow has the required master, build, Compose, GHCR, and SHA-tag contract fragments."
