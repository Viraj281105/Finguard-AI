# Runs every automated check: backend tests (including the live Java <-> Python contract tests when the
# ai/.venv exists), frontend typecheck/lint/tests/build, AI service lint/tests/evaluation freshness.
# Needs no Docker: backend integration tests start an embedded PostgreSQL.
# Usage:  .\scripts\test-all.ps1
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$failures = @()

function Invoke-Step([string]$Name, [string]$Directory, [scriptblock]$Command) {
    Write-Host "`n=== $Name ===" -ForegroundColor Cyan
    Push-Location (Join-Path $root $Directory)
    try {
        & $Command
        if ($LASTEXITCODE -ne 0) { $script:failures += $Name }
    } finally {
        Pop-Location
    }
}

Invoke-Step 'backend: mvnw test'          'backend'  { .\mvnw -B test }
Invoke-Step 'frontend: typecheck'         'frontend' { npm run typecheck }
Invoke-Step 'frontend: lint'              'frontend' { npm run lint }
Invoke-Step 'frontend: unit tests'        'frontend' { npm test }
Invoke-Step 'frontend: production build'  'frontend' { npm run build }
Invoke-Step 'ai: ruff'                    'ai'       { .\.venv\Scripts\python -m ruff check . }
Invoke-Step 'ai: pytest'                  'ai'       { .\.venv\Scripts\python -m pytest -q }
Invoke-Step 'ai: evaluation metrics current' 'ai'    { .\.venv\Scripts\python -m evaluation.evaluate --check }

Write-Host ""
if ($failures.Count -eq 0) {
    Write-Host "All checks passed." -ForegroundColor Green
} else {
    Write-Host "FAILED: $($failures -join ', ')" -ForegroundColor Red
    exit 1
}
