param(
    [string]$Root = ".",
    [string]$Output = ""
)

$ErrorActionPreference = "Stop"

$terms = @(
    "Ragent",
    "Ragent AI",
    "ragent",
    "RAGENT",
    "nageoffer",
    "拿个 offer",
    "马哥",
    "星球",
    "open8gu"
)

$excludeDirs = @(
    ".git",
    ".idea",
    ".vscode",
    "node_modules",
    "target",
    "dist",
    "build",
    ".next",
    "coverage",
    "logs"
)

$excludeFilePatterns = @(
    "*.lock",
    "*.png",
    "*.jpg",
    "*.jpeg",
    "*.gif",
    "*.webp",
    "*.ico",
    "*.jar",
    "*.class",
    "*.pdf",
    "*.zip",
    "*.gz",
    "*.7z"
)

function Test-IsExcludedPath {
    param([string]$Path)

    foreach ($dir in $excludeDirs) {
        if ($Path -match "([\\/]|^)$([regex]::Escape($dir))([\\/]|$)") {
            return $true
        }
    }
    foreach ($pattern in $excludeFilePatterns) {
        if ([System.Management.Automation.WildcardPattern]::new($pattern).IsMatch([System.IO.Path]::GetFileName($Path))) {
            return $true
        }
    }
    return $false
}

$files = Get-ChildItem -Path $Root -Recurse -File | Where-Object {
    -not (Test-IsExcludedPath $_.FullName)
}

$results = @()

foreach ($term in $terms) {
    $matches = $files | Select-String -Pattern ([regex]::Escape($term)) -SimpleMatch:$false -ErrorAction SilentlyContinue
    foreach ($m in $matches) {
        $relative = Resolve-Path -Path $m.Path -Relative
        $category = "REVIEW"
        if ($relative -match "frontend[\\/]" -or $relative -match "README|docs[\\/]") {
            $category = "CHECK_VISIBLE"
        }
        if ($relative -match "resources[\\/]docker|resources[\\/]database|application.*\.ya?ml|pom\.xml|src[\\/]main[\\/]java|mvnw|mvnw\.cmd") {
            $category = "LIKELY_TECHNICAL_PRESERVE"
        }

        $results += [PSCustomObject]@{
            Category = $category
            Term = $term
            Path = $relative
            Line = $m.LineNumber
            Text = ($m.Line.Trim() -replace "\s+", " ")
        }
    }
}

if ($results.Count -eq 0) {
    Write-Host "No teaching/original-brand traces found."
    exit 0
}

$results | Sort-Object Category, Path, Line | Format-Table -AutoSize -Wrap

if ($Output -ne "") {
    $results | Export-Csv -Path $Output -NoTypeInformation -Encoding UTF8
    Write-Host "Saved trace report to $Output"
}

Write-Host ""
Write-Host "Legend:"
Write-Host "  CHECK_VISIBLE: inspect and usually replace if user-visible."
Write-Host "  LIKELY_TECHNICAL_PRESERVE: preserve unless explicitly approved."
Write-Host "  REVIEW: inspect manually."
