param(
    [string]$Root = ".",
    [switch]$InstallIfMissing = $true
)

$ErrorActionPreference = "Stop"

$frontend = Join-Path $Root "frontend"
if (-not (Test-Path $frontend)) {
    throw "frontend directory not found at $frontend"
}

Push-Location $frontend
try {
    if (-not (Test-Path "package.json")) {
        throw "frontend/package.json not found"
    }

    $pkg = Get-Content "package.json" -Raw | ConvertFrom-Json
    $scripts = @{}
    if ($pkg.scripts) {
        $pkg.scripts.PSObject.Properties | ForEach-Object { $scripts[$_.Name] = $_.Value }
    }

    if ($InstallIfMissing -and -not (Test-Path "node_modules")) {
        Write-Host "node_modules not found. Running npm install..."
        npm install
    }

    if ($scripts.ContainsKey("type-check")) {
        Write-Host "Running npm run type-check"
        npm run type-check
    }
    else {
        Write-Host "Skip type-check: script not found."
    }

    if ($scripts.ContainsKey("lint")) {
        Write-Host "Running npm run lint"
        npm run lint
    }
    else {
        Write-Host "Skip lint: script not found."
    }

    if ($scripts.ContainsKey("build")) {
        Write-Host "Running npm run build"
        npm run build
    }
    else {
        throw "frontend package.json has no build script"
    }
}
finally {
    Pop-Location
}
