[CmdletBinding()]
param(
  [switch]$BuildImages
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repoRoot

$envFile = 'deploy/compose/.env.example'
$runtime = 'deploy/compose/compose.runtime.yaml'
$local = 'deploy/compose/compose.local.yaml'

Write-Host '== Stage 1 local smoke =='
Write-Host '> docker compose config --quiet'
docker compose --env-file $envFile -f $runtime -f $local config --quiet
if ($LASTEXITCODE -ne 0) {
  throw "Compose render failed with exit code $LASTEXITCODE."
}

if ($BuildImages) {
  Write-Host '> docker compose build api web'
  docker compose --env-file $envFile -f $runtime -f $local build api web
  if ($LASTEXITCODE -ne 0) {
    throw "Image build failed with exit code $LASTEXITCODE."
  }
} else {
  Write-Host 'Image build skipped. Pass -BuildImages to build local images.'
}

Write-Host 'Local smoke completed. No containers were started or stopped.'