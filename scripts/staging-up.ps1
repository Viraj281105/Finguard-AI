# Brings up an isolated containerised STAGING stack with SYNTHETIC data only (S0-08).
# Nothing is exposed beyond 127.0.0.1.
#   PostgreSQL 18 + AI + Backend (prod profile) + Frontend (nginx-unprivileged)
#   run inside Docker Compose project "finguard-staging" (--profile full).
# Secrets are random, generated per run into .staging/staging.env (git-ignored).
# Usage:  .\scripts\staging-up.ps1        Tear down with:  .\scripts\staging-down.ps1
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$state = Join-Path $root '.staging'
New-Item -ItemType Directory -Force $state | Out-Null

function New-Secret([int]$bytes) {
    $b = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
    [Convert]::ToBase64String($b).TrimEnd('=').Replace('+', 'A').Replace('/', 'B')
}

$pgPort = 15433; $aiPort = 18000; $apiPort = 18080; $webPort = 14173
$envFile = Join-Path $state 'staging.env'
if (-not (Test-Path $envFile)) {
    @(
        'POSTGRES_DB=finguard_staging',
        'POSTGRES_USER=finguard_staging',
        "POSTGRES_PASSWORD=$(New-Secret 24)",
        "AI_INTERNAL_TOKEN=$(New-Secret 32)",
        "JWT_SECRET=$(New-Secret 48)",
        "STAGING_PG_PORT=$pgPort",
        "STAGING_AI_PORT=$aiPort",
        "STAGING_API_PORT=$apiPort",
        "STAGING_WEB_PORT=$webPort"
    ) | Set-Content -Encoding ascii $envFile
}

Write-Host "Starting containerised staging stack (project: finguard-staging, profile: full)..." -ForegroundColor Yellow
Push-Location $root
try {
    docker compose -p finguard-staging --profile full --env-file $envFile -f docker-compose.yml -f docker-compose.staging.yml up -d --build --wait
    if ($LASTEXITCODE -ne 0) { throw 'docker compose staging up failed' }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Frontend: http://127.0.0.1:$webPort   Backend: http://127.0.0.1:$apiPort   AI: http://127.0.0.1:$aiPort/health" -ForegroundColor Cyan
Write-Host "Synthetic data only. State file: $envFile" -ForegroundColor DarkGray
